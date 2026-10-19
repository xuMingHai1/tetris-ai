"""Audit raw event completeness only; never launches games or certifies their origin."""
import argparse
import csv
import hashlib
import json
from pathlib import Path
import sys

from analyze_heart_paired import HEART_FORBIDDEN, HEART_REQUIRED, TIMING_SCOPE, integer, require


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, "duplicate JSON field")
        result[key] = value
    return result


def reject_constant(value):
    raise ValueError(f"non-finite JSON: {value}")


def expected_game(index):
    """Match the frozen CLI schedule, including the separate per-arm warmup."""
    if index < 20:
        arm, seed, phase, limit = index // 10, 19000 + index % 10, "WARMUP", 10
    else:
        offset = index - 20
        seed = 20000 + offset // 2
        arm, phase, limit = (seed % 2 + offset % 2) % 2, "MEASURED", 1000
    return {"phase": phase, "arm": ("BASELINE", "CANDIDATE")[arm], "seed": seed, "pieceLimit": limit}


def same_game(actual, expected):
    require(isinstance(actual, dict) and actual == expected
            and type(actual["seed"]) is int and type(actual["pieceLimit"]) is int,
            "missing, duplicate, or out-of-order game")


def nanos(value):
    require(integer(value) <= 2**63 - 1, "timing exceeds Java long range")
    return value


def check_predictions(values, setup, next_decision):
    """Bind serialized records to their streamed positions, without replaying predictions."""
    require(isinstance(values, list) and len(values) == int(setup is not None),
            "missing or unexpected prediction")
    for prediction in values:
        require(isinstance(prediction, dict) and set(prediction) == {
            "setupDecision", "nextValidatedDecision", "resolution"}, "invalid prediction record")
        require(integer(prediction["setupDecision"], 1) == setup
                and type(prediction["nextValidatedDecision"]) is int
                and prediction["nextValidatedDecision"] == next_decision,
                "prediction decision mismatch")
        resolutions = {"NO_NEXT_VALIDATED_TURN"} if next_decision == -1 else {
            "ACTUAL_Q", "ACTUAL_CLEAN_NON_Q", "ACTUAL_NOT_CLEAN", "BOARD_OR_TYPE_CHANGED"}
        require(prediction["resolution"] in resolutions, "invalid prediction resolution")


def check_turn(data, game, facts, elapsed):
    same_game(data["game"], game)
    fact = data["decision_fact"]
    require(integer(fact["decision"], 1) == len(facts) + 1 <= game["pieceLimit"], "non-contiguous turn")
    nanos(fact["decision_nanos"])
    matched = integer(fact["selected_matched_required_cells"])
    forbidden = integer(fact["selected_forbidden_occupied_cells"])
    require(matched <= HEART_REQUIRED and forbidden <= HEART_FORBIDDEN
            and integer(fact["selected_visual_error_cells"]) == HEART_REQUIRED - matched + forbidden,
            "invalid occupancy facts")
    clean = matched == HEART_REQUIRED and forbidden == 0
    require(type(fact["clean_completion"]) is bool and fact["clean_completion"] == clean, "false clean outcome")
    progress = data["actual_progress"]
    for key, expected in (("requiredCells", HEART_REQUIRED), ("forbiddenCells", HEART_FORBIDDEN),
                          ("matchedRequiredCells", matched), ("forbiddenOccupiedCells", forbidden)):
        require(integer(progress[key]) == expected, "progress disagrees with decision fact")
    integer(progress["supportOccupiedCells"])
    now = nanos(data["elapsed_game_nanos"])
    require(now >= elapsed and now >= sum(f["decision_nanos"] for f in facts) + fact["decision_nanos"],
            "invalid elapsed timing")
    terminal = data["actual_q"]
    require(type(data["q_accepted"]) is bool and isinstance(terminal, dict)
            and type(terminal["clean"]) is bool and terminal["clean"] == clean,
            "missing/inconsistent Q observation")
    require(integer(terminal["matchedRequired"]) == matched
            and integer(terminal["forbiddenOccupied"]) == forbidden, "Q occupancy disagrees with turns")
    for key in ("maxHeight", "rawHoles", "requiredHoles", "supportHoles", "outsideHoles"):
        integer(terminal[key])
    require(not data["q_accepted"] or clean, "Q without actual clean")
    choice = data["candidate_choice"]
    require(choice is None if game["arm"] == "BASELINE" else isinstance(choice, dict), "wrong arm observation")
    require(isinstance(data["resolved_predictions"], list), "missing prediction list")
    facts.append(fact)
    return now


def check_complete(data, game, facts, elapsed, first_clean_elapsed):
    same_game(data["game"], game)
    result = data["result"]
    for key, expected in (("seed", game["seed"]), ("pieceLimit", game["pieceLimit"]),
                          ("piecesPlaced", len(facts)), ("decisions", len(facts)),
                          ("primaryFailures", 0), ("fallbackDecisions", 0),
                          ("totalDecisionNanos", sum(f["decision_nanos"] for f in facts)),
                          ("maxDecisionNanos", max((f["decision_nanos"] for f in facts), default=0))):
        require(integer(result[key]) == expected, "result disagrees with turn facts")
    require(integer(data["illegal_plans"]) == 0, "illegal plans in completed game")
    require(type(result["reachedPieceLimit"]) is bool
            and result["reachedPieceLimit"] == (len(facts) == game["pieceLimit"]), "false horizon outcome")
    require(game["phase"] != "WARMUP" or len(facts) == 10, "incomplete warmup")
    require(isinstance(data["predictions"], list), "missing final prediction list")
    # The original telemetry prints times to six decimal places in milliseconds.
    # Compare that exact formatting, without inferring completion from candidate/Q labels.
    clean_turns = [f["decision"] for f in facts if f["clean_completion"]]
    first = clean_turns[0] if clean_turns else -1
    streak = longest = 0
    for fact in facts:
        streak = streak + 1 if fact["clean_completion"] else 0
        longest = max(longest, streak)
    expected_csv = ["build_shape_outcome", game["arm"], "heart", str(game["seed"]), str(len(facts)),
                    str(first), f"{first_clean_elapsed / 1e6:.6f}" if first > 0 else "-1.000000",
                    str(len(facts) - first if first > 0 else 0), str(len(clean_turns)), str(longest),
                    str(bool(facts and facts[-1]["clean_completion"])).lower(), f"{elapsed / 1e6:.6f}"]
    require(next(csv.reader([data["completion_csv"]])) == expected_csv, "completion CSV disagrees with turns")
    return bool(clean_turns)


def audit(lines):
    """Consume binary JSONL lines. Success means structural consistency, never provenance."""
    digest = hashlib.sha256()
    size = sequence = completed = measured_clean = 0
    game = None
    facts = []
    elapsed, first_clean_elapsed = 0, None
    finished = False
    resolved, pending = [], None
    for raw in lines:
        require(isinstance(raw, bytes) and raw.endswith(b"\n"), "truncated or non-binary JSONL line")
        digest.update(raw)
        size += len(raw)
        event = json.loads(raw.decode("utf-8"), object_pairs_hook=unique_object,
                           parse_constant=reject_constant)
        require(isinstance(event, dict) and set(event) == {"schema", "sequence", "type", "data"}, "invalid event")
        sequence += 1
        require(event["schema"] == "goal-window-raw-v1" and integer(event["sequence"], 1) == sequence,
                "wrong schema or event sequence")
        kind, data = event["type"], event["data"]
        require(isinstance(data, dict) and not finished, "invalid data or trailing event")
        if sequence == 1:
            require(kind == "provenance_verified" and isinstance(data["manifest"], dict)
                    and isinstance(data["loaded_classes"], dict)
                    and isinstance(data["manifest_sha256"], str)
                    and len(data["manifest_sha256"]) == 64
                    and all(c in "0123456789abcdef" for c in data["manifest_sha256"]), "missing provenance record")
        elif sequence == 2:
            require(kind == "session_started" and data == {"expected_warmup_games": 20,
                    "expected_measured_games": 160, "timing_scope": TIMING_SCOPE}
                    and type(data["expected_warmup_games"]) is int
                    and type(data["expected_measured_games"]) is int, "not a full measurement session")
        elif kind == "game_started":
            require(game is None and completed < 180, "overlapping or extra game")
            game = expected_game(completed)
            same_game(data, game)
            facts, elapsed, first_clean_elapsed = [], 0, None
            resolved, pending = [], None
        elif kind == "turn":
            require(game is not None, "turn outside game")
            elapsed = check_turn(data, game, facts, elapsed)
            check_predictions(data["resolved_predictions"], pending, len(facts))
            resolved.extend(data["resolved_predictions"])
            choice = data["candidate_choice"]
            pending = len(facts) if choice is not None and choice.get("choice") == "PREVIEW_SETUP" else None
            if facts[-1]["clean_completion"] and first_clean_elapsed is None:
                first_clean_elapsed = elapsed
        elif kind == "game_complete":
            require(game is not None, "completion outside game")
            predictions = data["predictions"]
            require(isinstance(predictions, list) and predictions[:len(resolved)] == resolved,
                    "prediction summary disagrees with streamed records")
            for final, streamed in zip(predictions, resolved):
                check_predictions([final], streamed["setupDecision"], streamed["nextValidatedDecision"])
            check_predictions(predictions[len(resolved):], pending, -1)
            clean = check_complete(data, game, facts, elapsed, first_clean_elapsed)
            measured_clean += int(clean and game["phase"] == "MEASURED")
            completed += 1
            game = None
        elif kind == "session_complete":
            require(game is None and completed == 180 and data == {"warmup_games": 20, "measured_games": 160}
                    and type(data["warmup_games"]) is int and type(data["measured_games"]) is int,
                    "missing games or false final count")
            finished = True
        else:
            raise ValueError("unexpected, verify-only, or incomplete event")
    require(finished, "missing session_complete")
    return {"schema": "goal-window-raw-audit-v1", "structurally_complete": True,
            "raw_sha256": digest.hexdigest(), "raw_bytes": size, "events": sequence,
            "warmup_games": 20, "measured_games": 160, "measured_games_with_clean_facts": measured_clean,
            "build_provenance_verified": False, "seed_sequence_attested": False,
            "prototype_gates_evaluated": False, "runtime_permission": False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    try:
        with args.input.open("rb") as source:
            report = audit(source)
        with args.output.open("x", encoding="utf-8") as output:
            json.dump(report, output, indent=2, allow_nan=False)
            output.write("\n")
    except (ValueError, KeyError, TypeError, OSError, csv.Error, RecursionError) as failure:
        print(f"raw audit rejected: {failure}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
