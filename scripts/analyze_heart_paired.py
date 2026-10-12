#!/usr/bin/env python3
"""Validate HEART paired evidence and evaluate each frozen headless gate independently."""
import argparse
import csv
import hashlib
import json
import math
from pathlib import Path
import re
import statistics

from analyze_heart_baseline import HEART_FORBIDDEN, HEART_REQUIRED, PROTOCOL, nearest_rank, seed_set, wilson95

SCHEMA = "heart-paired-v1"
TIMING_SCOPE = "agent.plan-through-validated-terminal-placement"


def digest(value):
    """Hash configuration with canonical UTF-8 JSON, independent of input formatting."""
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":"),
                                     ensure_ascii=False, allow_nan=False).encode()).hexdigest()


def require(condition, message):
    if not condition:
        raise ValueError(message)


def integer(value, minimum=0):
    require(type(value) is int and value >= minimum, "invalid integer")
    return value


def number(value, minimum=0):
    require(type(value) in (int, float) and math.isfinite(value) and value >= minimum, "invalid finite number")
    return value


def flag(value):
    require(type(value) is bool, "invalid boolean")
    return value


def sha(value, length=40):
    require(isinstance(value, str) and re.fullmatch(f"[0-9a-f]{{{length}}}", value), "invalid SHA")


def exact_clean_p(candidate_only, baseline_only):
    """One-sided exact McNemar: P[Binomial(discordant, 1/2) >= candidate-only]."""
    total = candidate_only + baseline_only
    return sum(math.comb(total, k) for k in range(candidate_only, total + 1)) / 2 ** total


def validate_game(game, horizon, order):
    seed = integer(game["seed"], -(2 ** 63))
    require(seed < 2 ** 63, "seed outside signed 64-bit range")
    require(integer(game["run_order"]) == order, "paired run order must alternate by seed parity")
    require(integer(game["piece_limit"]) == horizon, "inconsistent horizon")
    pieces = integer(game["pieces_placed"])
    require(integer(game["decisions"]) == pieces <= horizon, "invalid decision/piece count")
    reached = flag(game["reached_piece_limit"])
    require(reached == (pieces == horizon), "incorrect horizon outcome")
    failures = integer(game["primary_failures"])
    fallback = integer(game["fallback_decisions"])
    illegal = integer(game["illegal_plans"])
    require(max(failures, fallback, illegal) <= pieces, "failure count exceeds decisions")
    rows = game["decision_facts"]
    require(isinstance(rows, list) and len(rows) == pieces, "missing timing/decision facts")
    costs, clean = [], []
    best, coverage, intrusion = HEART_REQUIRED, 0, -1
    for index, row in enumerate(rows, 1):
        require(integer(row["decision"]) == index, "non-contiguous or duplicate decision")
        costs.append(integer(row["decision_nanos"]))
        matched = integer(row["selected_matched_required_cells"])
        forbidden = integer(row["selected_forbidden_occupied_cells"])
        error = integer(row["selected_visual_error_cells"])
        require(matched <= HEART_REQUIRED and forbidden <= HEART_FORBIDDEN
                and error == HEART_REQUIRED - matched + forbidden, "invalid HEART occupancy facts")
        complete = flag(row["clean_completion"])
        require(complete == (matched == HEART_REQUIRED and forbidden == 0), "false clean outcome")
        if complete:
            clean.append(index)
        best = min(best, error)
        if forbidden == 0:
            coverage = max(coverage, matched)
        if matched == HEART_REQUIRED:
            intrusion = forbidden if intrusion == -1 else min(intrusion, forbidden)
    first = min(clean, default=-1)
    streak = longest = 0
    clean_set = set(clean)
    for index in range(1, pieces + 1):
        streak = streak + 1 if index in clean_set else 0
        longest = max(longest, streak)
    require(integer(game["first_clean_piece"], -1) == first
            and integer(game["clean_turns"]) == len(clean)
            and integer(game["max_clean_streak"]) == longest
            and flag(game["clean_at_end"]) == (pieces in clean_set)
            and integer(game["post_clean_pieces"]) == (pieces - first if first != -1 else 0),
            "outcome summary disagrees with decision facts")
    require(integer(game["best_visual_error_cells"]) == best
            and integer(game["max_required_with_zero_forbidden"]) == coverage
            and integer(game["min_forbidden_at_full_required"], -1) == intrusion,
            "shape summary disagrees with decision facts")
    elapsed = number(game["elapsed_game_ms"])
    first_ms = number(game["first_clean_elapsed_ms"], -1)
    require((first == -1 and first_ms == -1) or (first > 0 and 0 <= first_ms <= elapsed),
            "invalid first-clean elapsed time")
    require(elapsed + 0.000001 >= sum(costs) / 1e6
            and (first == -1 or first_ms + 0.000001 >= sum(costs[:first]) / 1e6),
            "elapsed time shorter than decision costs")
    require(integer(game["total_decision_nanos"]) == sum(costs)
            and integer(game["max_decision_nanos"]) == max(costs, default=0), "timing aggregate mismatch")
    return game, costs


def summarize(games, costs, limit):
    completed = [g for g in games if 0 < g["first_clean_piece"] <= limit]
    any_clean = [g for g in games if g["first_clean_piece"] > 0]
    pieces = [g["pieces_placed"] for g in games]
    intrusion = [g["min_forbidden_at_full_required"] for g in games if g["min_forbidden_at_full_required"] != -1]
    nanos = [n for row in costs for n in row]
    slowest = max(range(len(games)), key=lambda i: max(costs[i], default=0))
    return {
        "games": len(games), "clean_by_limit": len(completed), "clean_rate": len(completed) / len(games),
        "clean_wilson95": wilson95(len(completed), len(games)), "any_clean_games": len(any_clean),
        "not_clean_by_limit": len(games) - len(completed),
        "first_clean_denominator": len(any_clean),
        "first_clean_piece_median_any_clean": statistics.median(g["first_clean_piece"] for g in any_clean) if any_clean else None,
        "first_clean_ms_median_any_clean": statistics.median(g["first_clean_elapsed_ms"] for g in any_clean) if any_clean else None,
        "completed_by_limit_denominator": len(completed),
        "first_clean_piece_median_by_limit": statistics.median(g["first_clean_piece"] for g in completed) if completed else None,
        "first_clean_ms_median_by_limit": statistics.median(g["first_clean_elapsed_ms"] for g in completed) if completed else None,
        "post_clean_pieces_any_clean": [g["post_clean_pieces"] for g in any_clean],
        "clean_and_horizon_games": sum(g["reached_piece_limit"] for g in completed),
        "clean_then_died_seeds": [g["seed"] for g in any_clean if not g["reached_piece_limit"]],
        "horizon_games": sum(g["reached_piece_limit"] for g in games),
        "horizon_rate": sum(g["reached_piece_limit"] for g in games) / len(games),
        "death_games": sum(not g["reached_piece_limit"] for g in games),
        "pieces_min": min(pieces), "pieces_median": statistics.median(pieces),
        "pieces_p95": nearest_rank(pieces), "pieces_mean": statistics.mean(pieces),
        "decisions": len(nanos), "decision_p95_ms": nearest_rank(nanos) / 1e6 if nanos else None,
        "decision_max_ms": max(nanos) / 1e6 if nanos else None,
        "per_game_max_ms": {str(g["seed"]): max(c, default=0) / 1e6 for g, c in zip(games, costs)},
        "slowest_seed": games[slowest]["seed"] if nanos else None,
        "best_error_mean": statistics.mean(g["best_visual_error_cells"] for g in games),
        "zero_forbidden_coverage_max": max(g["max_required_with_zero_forbidden"] for g in games),
        "full_required_intrusion_min": min(intrusion) if intrusion else None,
        "never_full_required_games": len(games) - len(intrusion),
        **{k: sum(g[k] for g in games) for k in ("primary_failures", "fallback_decisions", "illegal_plans")},
    }


def analyze(data, protocol_bytes):
    protocol = json.loads(protocol_bytes)
    require(isinstance(data, dict), "paired envelope must be an object")
    # This tool implements v1.1 only. A changed file requires a separately reviewed protocol/tool.
    require(hashlib.sha256(protocol_bytes).hexdigest() == "23dea1ad0d74ddfda04d443266d3cca9583d790aaceba6bc3afd98cd36896280",
            "unsupported or altered frozen protocol")
    require(data["schema"] == SCHEMA and data["protocol_sha256"] == hashlib.sha256(protocol_bytes).hexdigest(),
            "schema/protocol mismatch")
    population = data["population"]
    require(population in protocol["populations"], "unknown population")
    require(data["evidence_kind"] in ("synthetic", "measured"), "unknown evidence kind")
    require(data["timing_scope"] == TIMING_SCOPE, "wrong full-decision timing scope")
    require(data["input_boundary"] == "post-spawn-gravity-known-preview"
            and flag(data["reset_strategy_per_game"]), "wrong input/reset boundary")
    environment = data["environment"]
    require(isinstance(environment, dict) and all(isinstance(environment[k], str) and environment[k].strip()
            for k in ("os", "arch", "cpu", "ram", "jdk", "javafx", "jvm_flags", "clock")), "missing environment")
    expected = seed_set(protocol["populations"][population])
    require(isinstance(data["arms"], dict) and set(data["arms"]) == {"baseline", "candidate"}, "expected exactly two arms")
    arms, indexed, costs = {}, {}, {}
    for name, arm in data["arms"].items():
        require(isinstance(arm, dict), "arm must be an object")
        sha(arm["source_sha"])
        sha(arm["config_sha256"], 64)
        config = arm["config"]
        require(isinstance(config, dict), "configuration must be an object")
        require(arm["config_sha256"] == digest(config), "config hash mismatch")
        require(config["rows"] == 20 and config["cols"] == 10 and config["target"] == "heart"
                and type(config["rows"]) is int and type(config["cols"]) is int
                and integer(config["piece_horizon"]) == protocol["piece_horizon"], "config board/horizon mismatch")
        require(isinstance(config["agent"], str) and bool(config["agent"].strip()), "missing agent identity")
        flag(config["preview_rescue"])
        if name == "baseline":
            require(config["agent"] == protocol["baseline_agent"] and not config["preview_rescue"]
                    and arm["production_base_sha"] == protocol["production_base_sha"], "wrong frozen baseline")
        warmup = arm["warmup"]
        require(warmup == {**protocol["warmup"], "decisions": 100}
                and all(type(v) is int for v in warmup.values()), "incomplete/mismatched warmup")
        require(isinstance(arm["games"], list), "missing games")
        indexed[name], costs[name] = {}, {}
        for game in arm["games"]:
            seed = integer(game["seed"], -(2 ** 63))
            require(seed in expected and seed not in indexed[name], "duplicate/unexpected paired seed")
            validated, nanos = validate_game(game, protocol["piece_horizon"],
                                              (seed % 2) if name == "baseline" else 1 - seed % 2)
            indexed[name][seed], costs[name][seed] = validated, nanos
        require(indexed[name].keys() == expected, "missing paired seed")
        arms[name] = summarize([indexed[name][s] for s in sorted(expected)],
                               [costs[name][s] for s in sorted(expected)], protocol["completion_piece_limit"])
    pairs = []
    for seed in sorted(expected):
        pair = {"seed": seed}
        for name in ("baseline", "candidate"):
            g = indexed[name][seed]
            pair[name] = {k: g[k] for k in (
                "pieces_placed", "first_clean_piece", "first_clean_elapsed_ms", "post_clean_pieces",
                "clean_turns", "max_clean_streak", "clean_at_end", "reached_piece_limit",
                "best_visual_error_cells", "max_required_with_zero_forbidden", "min_forbidden_at_full_required")}
            pair[name]["clean_by_limit"] = 0 < g["first_clean_piece"] <= protocol["completion_piece_limit"]
            pair[name]["any_clean"] = g["first_clean_piece"] > 0
        pair["pieces_delta"] = pair["candidate"]["pieces_placed"] - pair["baseline"]["pieces_placed"]
        pairs.append(pair)
    deltas = [p["pieces_delta"] for p in pairs]
    won = sum(p["candidate"]["clean_by_limit"] and not p["baseline"]["clean_by_limit"] for p in pairs)
    lost = sum(p["baseline"]["clean_by_limit"] and not p["candidate"]["clean_by_limit"] for p in pairs)
    survivor_losses = [p["seed"] for p in pairs if p["baseline"]["reached_piece_limit"] and not p["candidate"]["reached_piece_limit"]]
    paired = {"candidate_only_clean": won, "baseline_only_clean": lost, "clean_p_value": exact_clean_p(won, lost),
              "pieces_delta_mean": statistics.mean(deltas), "pieces_delta_median": statistics.median(deltas),
              "wins": sum(d > 0 for d in deltas), "losses": sum(d < 0 for d in deltas), "ties": sum(d == 0 for d in deltas),
              "regressed_seed_fraction": sum(d < 0 for d in deltas) / len(pairs),
              "maximum_piece_loss": max(0, -min(deltas)), "worst_loss_seeds": [p["seed"] for p in pairs if p["pieces_delta"] == min(deltas) and min(deltas) < 0],
              "baseline_survivors_lost": survivor_losses,
              "horizon_rate_loss": (arms["baseline"]["horizon_games"] - arms["candidate"]["horizon_games"]) / len(pairs)}
    gates = {}
    def gate(name, value, threshold, passed):
        gates[name] = {"observed": value, "threshold": threshold, "pass": bool(passed)}
    adoption = protocol["adoption"]
    candidate = arms["candidate"]
    for key, value in (("minimum_clean_rate", candidate["clean_rate"]),
                       ("minimum_wilson95_lower_bound", candidate["clean_wilson95"][0]),
                       ("minimum_mean_piece_delta", paired["pieces_delta_mean"]),
                       ("minimum_median_piece_delta", paired["pieces_delta_median"])):
        gate(key, value, adoption[key], value >= adoption[key])
    gate("maximum_paired_clean_p_value", paired["clean_p_value"], adoption["maximum_paired_clean_p_value"],
         won > lost and paired["clean_p_value"] <= adoption["maximum_paired_clean_p_value"])
    for key, value in (("maximum_regressed_seed_fraction", paired["regressed_seed_fraction"]),
                       ("maximum_piece_loss", paired["maximum_piece_loss"]),
                       ("maximum_baseline_survivors_lost", len(survivor_losses)),
                       ("maximum_horizon_rate_loss", paired["horizon_rate_loss"])):
        gate(key, value, adoption[key], value <= adoption[key])
    # Check each arm: a broken/slow baseline cannot establish a valid paired comparison.
    for name, summary in arms.items():
        for key in ("decision_p95_ms", "decision_max_ms", "primary_failures", "fallback_decisions", "illegal_plans"):
            threshold = adoption.get(key, 0)
            value = summary[key]
            gate(name + "." + key, value, threshold, value is not None and value <= threshold)
    passed = all(g["pass"] for g in gates.values())
    validation = population == "validation" and data["evidence_kind"] == "measured"
    return {"schema": SCHEMA, "protocol_sha256": data["protocol_sha256"], "population": population,
            "evidence_kind": data["evidence_kind"], "environment": environment, "timing_scope": data["timing_scope"],
            "input_boundary": data["input_boundary"], "reset_strategy_per_game": data["reset_strategy_per_game"],
            "baseline_production_base_sha": data["arms"]["baseline"]["production_base_sha"],
            "arm_metadata": {n: {k: a[k] for k in ("source_sha", "config_sha256", "config", "warmup")}
                             for n, a in data["arms"].items()},
            "arms": arms, "paired": paired, "pairs": pairs, "gates": gates,
            "all_headless_gates_pass": passed,
            "eligible_for_runtime_validation": validation and passed,
            "conclusion": ("headless-pass-runtime-validation-only" if passed else "headless-gates-failed")
                          if validation else "diagnostic-only-no-adoption"}


def unique_object(pairs):
    obj = {}
    for key, value in pairs:
        require(key not in obj, "duplicate JSON field")
        obj[key] = value
    return obj


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path)
    parser.add_argument("--protocol", type=Path, default=PROTOCOL)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--pairs-csv", type=Path, required=True)
    args = parser.parse_args()
    try:
        report = analyze(json.loads(args.input.read_text(encoding="utf-8"), object_pairs_hook=unique_object),
                         args.protocol.read_bytes())
    except (ValueError, KeyError, TypeError, OverflowError) as error:
        parser.exit(2, f"Invalid paired evidence: {error}\n")
    for path in (args.output, args.pairs_csv):
        path.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2, ensure_ascii=False, allow_nan=False) + "\n", encoding="utf-8")
    flat = [{"seed": p["seed"], "pieces_delta": p["pieces_delta"],
             **{n + "." + k: v for n in ("baseline", "candidate") for k, v in p[n].items()}}
            for p in report["pairs"]]
    with args.pairs_csv.open("w", encoding="utf-8", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=list(flat[0]))
        writer.writeheader()
        writer.writerows(flat)
    print(f"{report['population']}: {report['conclusion']}")
    for name, gate in report["gates"].items():
        print(f"{'PASS' if gate['pass'] else 'FAIL'} {name}: {gate['observed']} (threshold {gate['threshold']})")
    # Diagnostic runs record failures without treating them as an adoption decision.
    return 1 if report["population"] == "validation" and report["evidence_kind"] == "measured" and not report["all_headless_gates_pass"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
