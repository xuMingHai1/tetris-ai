#!/usr/bin/env python3
"""Validate fixed HEART baseline tables; summarize observed facts without adopting a strategy."""
import argparse
import csv
import hashlib
import json
import math
from pathlib import Path
import re
import statistics

PROTOCOL = Path(__file__).resolve().parents[1] / "docs/research/heart-acceptance-v1.json"
# Fixed HEART mask in ShapeTarget: 32 required and 16 forbidden cells (support excluded).
HEART_REQUIRED = 32
HEART_FORBIDDEN = 16


def nearest_rank(values, fraction=0.95):
    if not values:
        return None
    return sorted(values)[math.ceil(len(values) * fraction) - 1]


def wilson95(successes, total):
    z = 1.959963984540054
    p = successes / total
    denominator = 1 + z * z / total
    center = (p + z * z / (2 * total)) / denominator
    half = z * math.sqrt(p * (1 - p) / total + z * z / (4 * total * total)) / denominator
    return [max(0, center - half), min(1, center + half)]


def boolean(value):
    if value not in ("true", "false"):
        raise ValueError(f"invalid boolean: {value}")
    return value == "true"


def seed_set(population):
    return set(range(population["seed_start"], population["seed_start"] + population["games"]))


def validate_protocol(protocol):
    if (protocol["baseline_agent"] != "build-shape" or protocol["preview_rescue"]
            or (protocol["rows"], protocol["cols"], protocol["target"]) != (20, 10, "heart")):
        raise ValueError("baseline must use production 20x10 greedy HEART without rescue")
    populations = [seed_set(value) for value in protocol["populations"].values()]
    warmup = seed_set(protocol["warmup"])
    for index, population in enumerate(populations):
        if not population or population & warmup or any(population & other for other in populations[:index]):
            raise ValueError("empty or overlapping seed populations")
    if not 0 < protocol["completion_piece_limit"] <= protocol["piece_horizon"]:
        raise ValueError("invalid completion horizon")


def parse_tables(text):
    headers = {}
    tables = {"agent": [], "build_shape_game": [], "build_shape_outcome": [],
              "build_shape_timing": [], "build_shape_decision": []}
    for line in text.splitlines():
        row = next(csv.reader([line]))
        if not row:
            continue
        table = "agent" if row[0] == "build-shape-heart" else row[0]
        if table not in tables:
            # Maven/comment text is harmless; another CSV game is benchmark contamination,
            # even when its agent is unknown or its row has the wrong number of columns.
            if not row[0].startswith(("#", "[")) and len(row) > 1 and re.fullmatch(r"-?\d+", row[1]):
                raise ValueError("unexpected benchmark agent in HEART baseline")
            continue
        if row[0] == "agent" or row[1:2] == ["strategy"]:
            if table in headers:
                raise ValueError(f"duplicate header: {table}")
            headers[table] = row
        else:
            if table not in headers or len(row) != len(headers[table]):
                raise ValueError(f"missing header or malformed row: {table}")
            tables[table].append(dict(zip(headers[table], row)))
    if headers.keys() != tables.keys():
        raise ValueError("missing required HEART tables")
    return tables


def index_games(rows, expected):
    indexed = {}
    for row in rows:
        seed = int(row["seed"])
        if seed in indexed or seed not in expected:
            raise ValueError("duplicate or unexpected game seed")
        indexed[seed] = row
    if indexed.keys() != expected:
        raise ValueError("missing game seeds")
    return indexed


def analyze(text, protocol):
    validate_protocol(protocol)
    expected = seed_set(protocol["populations"]["development"]) | seed_set(protocol["populations"]["calibration"])
    warmup = protocol["warmup"]
    records = re.findall(r"^# build_shape_warmup seed=(-?\d+) games=(\d+) pieces=(\d+) decisions=(\d+)$", text, re.M)
    correct = (warmup["seed_start"], warmup["games"], warmup["max_pieces"], warmup["games"] * warmup["max_pieces"])
    if len(records) != 1 or tuple(map(int, records[0])) != correct:
        raise ValueError("warmup does not match the frozen protocol")
    tables = parse_tables(text)
    games = index_games(tables["agent"], expected)
    shape = index_games(tables["build_shape_game"], expected)
    outcomes = index_games(tables["build_shape_outcome"], expected)
    costs = {seed: {} for seed in expected}
    clean = {seed: [] for seed in expected}
    diagnostics = {seed: {} for seed in expected}
    for table in ("build_shape_game", "build_shape_outcome", "build_shape_timing", "build_shape_decision"):
        for row in tables[table]:
            if row["strategy"] != "build-shape-heart" or row["target"] != "heart":
                raise ValueError("wrong strategy or target in HEART results")
    for row in tables["build_shape_timing"]:
        seed, decision, nanos = int(row["seed"]), int(row["decision"]), int(row["decision_nanos"])
        if seed not in expected or decision in costs[seed] or nanos < 0:
            raise ValueError("invalid timing seed, duplicate decision or negative cost")
        costs[seed][decision] = nanos
    for row in tables["build_shape_decision"]:
        seed, decision = int(row["seed"]), int(row["decision"])
        if seed not in expected or decision in diagnostics[seed]:
            raise ValueError("invalid diagnostic seed or duplicate decision")
        diagnostics[seed][decision] = row
        matched = int(row["selected_matched_required_cells"])
        forbidden = int(row["selected_forbidden_occupied_cells"])
        error = int(row["selected_visual_error_cells"])
        rate = float(row["required_completion_rate"])
        if (not 0 <= matched <= HEART_REQUIRED or not 0 <= forbidden <= HEART_FORBIDDEN
                or error != HEART_REQUIRED - matched + forbidden
                or not math.isfinite(rate) or not 0 <= rate <= 1
                or abs(rate - matched / HEART_REQUIRED) > 0.000051):
            raise ValueError("invalid HEART diagnostic coverage, intrusion or visual error")
        complete = boolean(row["clean_completion"])
        if complete != (forbidden == 0 and matched == HEART_REQUIRED):
            raise ValueError("clean diagnostic disagrees with coverage/intrusion")
        if complete:
            clean[seed].append(decision)
    for seed in expected:
        game, outcome = games[seed], outcomes[seed]
        decisions = int(game["decisions"])
        first = min(clean[seed], default=-1)
        streak = longest = 0
        for decision in range(1, decisions + 1):
            streak = streak + 1 if decision in clean[seed] else 0
            longest = max(longest, streak)
        facts = list(diagnostics[seed].values())
        # Match BuildShapeGameTelemetry's initial empty-board values and aggregation.
        best_error = min([HEART_REQUIRED] + [int(row["selected_visual_error_cells"]) for row in facts])
        zero_forbidden_coverage = max([0] + [int(row["selected_matched_required_cells"]) for row in facts
                                           if int(row["selected_forbidden_occupied_cells"]) == 0])
        full_required_intrusion = min([int(row["selected_forbidden_occupied_cells"]) for row in facts
                                      if int(row["selected_matched_required_cells"]) == HEART_REQUIRED], default=-1)
        if (int(shape[seed]["best_visual_error_cells"]) != best_error
                or int(shape[seed]["max_required_with_zero_forbidden"]) != zero_forbidden_coverage
                or int(shape[seed]["min_forbidden_at_full_required"]) != full_required_intrusion):
            raise ValueError(f"shape summary disagrees with decision facts at seed {seed}")
        if (int(game["piece_limit"]) != protocol["piece_horizon"]
                or int(game["pieces_placed"]) != decisions or not 0 <= decisions <= protocol["piece_horizon"]
                or int(outcome["decisions"]) != decisions
                or costs[seed].keys() != set(range(1, decisions + 1))
                or diagnostics[seed].keys() != costs[seed].keys()
                or int(outcome["first_clean_piece"]) != first
                or boolean(shape[seed]["clean_completion"]) != bool(clean[seed])
                or int(outcome["clean_turns"]) != len(clean[seed])
                or int(outcome["max_clean_streak"]) != longest
                or boolean(outcome["clean_at_end"]) != (decisions in clean[seed])
                or int(outcome["post_clean_pieces"]) != (decisions - first if first != -1 else 0)
                or boolean(game["reached_piece_limit"]) != (decisions == protocol["piece_horizon"])
                or int(game["primary_failures"]) != 0 or int(game["fallback_decisions"]) != 0):
            raise ValueError(f"inconsistent game outcome at seed {seed}")
        first_ms, elapsed_ms = float(outcome["first_clean_elapsed_ms"]), float(outcome["elapsed_game_ms"])
        if (not math.isfinite(first_ms) or not math.isfinite(elapsed_ms) or elapsed_ms < 0
                or (first == -1 and first_ms != -1) or (first != -1 and not 0 <= first_ms <= elapsed_ms)):
            raise ValueError(f"invalid first-clean time at seed {seed}")
        # Legacy aggregate milliseconds are rounded to 3 decimals; per-turn nanoseconds retain precision.
        maximum, average = float(game["max_decision_ms"]), float(game["avg_decision_ms"])
        if (not math.isfinite(maximum) or not math.isfinite(average)
                or abs(max(costs[seed].values(), default=0) / 1e6 - maximum) > 0.000501
                or abs(sum(costs[seed].values()) / max(1, decisions) / 1e6 - average) > 0.000501
                or elapsed_ms + 0.000001 < sum(costs[seed].values()) / 1e6
                or (first != -1 and first_ms + 0.000001 < sum(value for index, value in costs[seed].items() if index <= first) / 1e6)):
            raise ValueError(f"timing maximum disagrees with game result at seed {seed}")
    report = {"protocol": protocol["protocol"], "baseline_only": True, "validation_run": False,
              "populations": {}}
    for name in ("development", "calibration"):
        seeds = sorted(seed_set(protocol["populations"][name]))
        total = len(seeds)
        completed = [seed for seed in seeds if 0 < int(outcomes[seed]["first_clean_piece"]) <= protocol["completion_piece_limit"]]
        all_clean = [seed for seed in seeds if int(outcomes[seed]["first_clean_piece"]) > 0]
        nanos = [value for seed in seeds for value in costs[seed].values()]
        pieces = [int(games[seed]["pieces_placed"]) for seed in seeds]
        intrusion = [int(shape[seed]["min_forbidden_at_full_required"]) for seed in seeds
                     if int(shape[seed]["min_forbidden_at_full_required"]) != -1]
        report["populations"][name] = {
            "seed_start": seeds[0], "seed_end": seeds[-1], "games": total,
            "piece_horizon": protocol["piece_horizon"], "clean_by_limit": len(completed),
            "clean_rate": len(completed) / total, "clean_wilson95": wilson95(len(completed), total),
            "any_clean_games": len(all_clean), "not_clean_by_limit": total - len(completed),
            "first_clean_piece_median_completed_only": statistics.median([int(outcomes[s]["first_clean_piece"]) for s in completed]) if completed else None,
            "first_clean_elapsed_ms_median_completed_only": statistics.median([float(outcomes[s]["first_clean_elapsed_ms"]) for s in completed]) if completed else None,
            "post_clean_pieces_completed_only": [int(outcomes[s]["post_clean_pieces"]) for s in completed],
            "clean_and_horizon_games": sum(boolean(games[s]["reached_piece_limit"]) for s in completed),
            "clean_then_died_seeds": [s for s in completed if not boolean(games[s]["reached_piece_limit"])],
            "clean_at_end_games": sum(boolean(outcomes[s]["clean_at_end"]) for s in seeds),
            "horizon_games": sum(boolean(games[s]["reached_piece_limit"]) for s in seeds),
            "pieces_min": min(pieces), "pieces_median": statistics.median(pieces), "pieces_p95": nearest_rank(pieces),
            "pieces_mean": statistics.mean(pieces), "decisions": len(nanos),
            "decision_p95_ms": nearest_rank(nanos) / 1e6 if nanos else None,
            "decision_max_ms": max(nanos) / 1e6 if nanos else None,
            "best_error_mean": statistics.mean(int(shape[s]["best_visual_error_cells"]) for s in seeds),
            "best_error_min": min(int(shape[s]["best_visual_error_cells"]) for s in seeds),
            "zero_forbidden_coverage_max": max(int(shape[s]["max_required_with_zero_forbidden"]) for s in seeds),
            "full_required_intrusion_min": min(intrusion) if intrusion else None,
            "never_full_required_games": total - len(intrusion),
            "primary_failures": 0, "fallback_decisions": 0,
        }
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("log", type=Path)
    parser.add_argument("--protocol", type=Path, default=PROTOCOL)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    protocol_bytes = args.protocol.read_bytes()
    report = analyze(args.log.read_text(encoding="utf-8"), json.loads(protocol_bytes))
    report["protocol_sha256"] = hashlib.sha256(protocol_bytes).hexdigest()
    output = json.dumps(report, indent=2, ensure_ascii=False, allow_nan=False) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(output, encoding="utf-8")
    print(output, end="")


if __name__ == "__main__":
    main()
