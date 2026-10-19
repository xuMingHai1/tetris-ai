"""Pure single-game raw-event adapter for #105; no session or provenance certification.

The caller supplies the observed pair order and retains the original raw bytes. Full
session scheduling, warmup, environment and build checks belong to the future driver.
"""
import copy
import csv

import analyze_heart_paired as paired


def convert_game(events, *, run_order):
    """Convert one complete MEASURED event slice, rejecting inconsistent facts.

    Input includes game_started, every turn, and game_complete (whole raw envelopes).
    Only registered development/calibration seeds are supported. The returned game
    carries no evidence-kind or permission: a valid object is not an attested run.
    """
    try:
        return _convert_game(events, run_order)
    except (KeyError, TypeError, IndexError, csv.Error) as failure:
        raise ValueError("missing or malformed raw game field") from failure


def _convert_game(events, run_order):
    paired.require(isinstance(events, list) and len(events) >= 2, "complete game slice required")
    paired.require(paired.integer(run_order) in (0, 1), "observed pair order must be 0 or 1")
    identity = events[0]["data"]
    paired.require(isinstance(identity, dict) and set(identity) == {"phase", "arm", "seed", "pieceLimit"},
                   "invalid game identity")
    seed = paired.integer(identity["seed"])
    paired.require(identity["phase"] == "MEASURED" and 20000 <= seed < 20080
                   and identity["arm"] in ("BASELINE", "CANDIDATE")
                   and paired.integer(identity["pieceLimit"]) == 1000, "not a registered measured game")
    expected_order = seed % 2 if identity["arm"] == "BASELINE" else 1 - seed % 2
    paired.require(run_order == expected_order, "observed order disagrees with registered parity")
    first_sequence = paired.integer(events[0]["sequence"], 1)
    facts, elapsed, first_clean_elapsed, total_cost = [], 0, -1, 0
    for index, event in enumerate(events):
        expected_kind = "game_started" if index == 0 else "game_complete" if index == len(events) - 1 else "turn"
        paired.require(event["schema"] == "goal-window-raw-v1" and event["type"] == expected_kind
                       and paired.integer(event["sequence"], 1) == first_sequence + index,
                       "wrong event type/schema or missing, duplicate, out-of-order event")
        if index == 0:
            continue
        data = event["data"]
        game = data["game"]
        paired.require(game == identity and type(game["seed"]) is int and type(game["pieceLimit"]) is int,
                       "event belongs to another game")
        if expected_kind == "game_complete":
            break
        fact = copy.deepcopy(data["decision_fact"])
        # #105 validates sequence, occupancy algebra, clean, and precise decision costs
        # below. Here bind those facts to the original production progress record.
        progress = data["actual_progress"]
        for key, value in (("requiredCells", paired.HEART_REQUIRED), ("forbiddenCells", paired.HEART_FORBIDDEN),
                           ("matchedRequiredCells", fact["selected_matched_required_cells"]),
                           ("forbiddenOccupiedCells", fact["selected_forbidden_occupied_cells"])):
            paired.require(paired.integer(progress[key]) == value, "progress disagrees with decision fact")
        now = paired.integer(data["elapsed_game_nanos"])
        paired.require(now >= elapsed, "non-monotonic elapsed game time")
        elapsed = now
        facts.append(fact)
        total_cost += paired.integer(fact["decision_nanos"])
        paired.require(elapsed >= total_cost,
                       "elapsed shorter than accumulated decision costs")
        if fact["clean_completion"] is True and first_clean_elapsed == -1:
            first_clean_elapsed = elapsed

    completed = events[-1]["data"]
    result = completed["result"]
    paired.require(paired.integer(result["seed"]) == seed
                   and paired.integer(result["pieceLimit"]) == 1000, "result identity mismatch")
    row = next(csv.reader([completed["completion_csv"]], strict=True))
    paired.require(len(row) == 12 and row[:4] == ["build_shape_outcome", identity["arm"], "heart", str(seed)],
                   "invalid completion CSV identity")
    paired.require(row[10] in ("true", "false"), "invalid clean-at-end boolean")
    paired.require(row[6] == (f"{first_clean_elapsed / 1e6:.6f}" if first_clean_elapsed >= 0 else "-1.000000")
                   and row[11] == f"{elapsed / 1e6:.6f}", "completion elapsed disagrees with raw nanos")
    paired.require(int(row[4]) == paired.integer(result["decisions"]), "completion decision count mismatch")
    matched = [f["selected_matched_required_cells"] for f in facts]
    forbidden = [f["selected_forbidden_occupied_cells"] for f in facts]
    game = {
        "seed": seed, "run_order": run_order, "piece_limit": identity["pieceLimit"],
        **{target: result[source] for target, source in (
            ("pieces_placed", "piecesPlaced"), ("decisions", "decisions"),
            ("reached_piece_limit", "reachedPieceLimit"), ("primary_failures", "primaryFailures"),
            ("fallback_decisions", "fallbackDecisions"), ("total_decision_nanos", "totalDecisionNanos"),
            ("max_decision_nanos", "maxDecisionNanos"))},
        "illegal_plans": completed["illegal_plans"], "decision_facts": facts,
        "first_clean_piece": int(row[5]), "first_clean_elapsed_ms": first_clean_elapsed / 1e6 if first_clean_elapsed >= 0 else -1,
        "post_clean_pieces": int(row[7]), "clean_turns": int(row[8]), "max_clean_streak": int(row[9]),
        "clean_at_end": row[10] == "true", "elapsed_game_ms": elapsed / 1e6,
        "best_visual_error_cells": min([paired.HEART_REQUIRED] + [f["selected_visual_error_cells"] for f in facts]),
        "max_required_with_zero_forbidden": max([0] + [m for m, f in zip(matched, forbidden) if f == 0]),
        "min_forbidden_at_full_required": min([f for m, f in zip(matched, forbidden) if m == paired.HEART_REQUIRED], default=-1),
    }
    paired.validate_game(game, 1000, expected_order)
    # A completed production GoalWindow game has no fallback or failure; incomplete
    # events must never be transformed into normal deaths by filling missing zeros.
    paired.require(all(game[k] == 0 for k in ("primary_failures", "fallback_decisions", "illegal_plans")),
                   "failed game cannot be converted as complete")
    return game
