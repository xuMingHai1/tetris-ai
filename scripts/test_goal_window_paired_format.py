"""Artificial raw records only: no planner, JVM, warmup or population execution."""
import copy
import unittest

import analyze_heart_paired as paired
from goal_window_paired_format import convert_game
from test_analyze_heart_paired import game, fixture, PROTOCOL_BYTES


def raw_game(seed=20000, arm="BASELINE", pieces=4, clean_at=2):
    expected = copy.deepcopy(game(seed, arm.lower(), pieces, clean_at, 1_000_003))
    identity = {"phase": "MEASURED", "arm": arm, "seed": seed, "pieceLimit": 1000}
    data = [identity]
    for fact in expected["decision_facts"]:
        data.append({"game": copy.deepcopy(identity), "decision_fact": copy.deepcopy(fact),
                     "elapsed_game_nanos": fact["decision"] * 1_000_003,
                     "actual_progress": {"requiredCells": 32, "forbiddenCells": 16,
                         "matchedRequiredCells": fact["selected_matched_required_cells"],
                         "forbiddenOccupiedCells": 0, "supportOccupiedCells": 0},
                     # Actual clean can be non-Q; a prediction must never supply clean.
                     "q_accepted": False, "actual_q": {"accepted": False},
                     "candidate_choice": None if arm == "BASELINE" else {"choice": "PREVIEW_SETUP"},
                     "resolved_predictions": []})
    result = {source: expected[target] for target, source in (
        ("seed", "seed"), ("piece_limit", "pieceLimit"), ("pieces_placed", "piecesPlaced"),
        ("decisions", "decisions"), ("reached_piece_limit", "reachedPieceLimit"),
        ("primary_failures", "primaryFailures"), ("fallback_decisions", "fallbackDecisions"),
        ("total_decision_nanos", "totalDecisionNanos"), ("max_decision_nanos", "maxDecisionNanos"))}
    completion = (f"build_shape_outcome,{arm},heart,{seed},{pieces},{clean_at},"
                  f"{expected['first_clean_elapsed_ms']:.6f},{expected['post_clean_pieces']},"
                  f"{expected['clean_turns']},{expected['max_clean_streak']},"
                  f"{str(expected['clean_at_end']).lower()},{expected['elapsed_game_ms']:.6f}")
    data.append({"game": copy.deepcopy(identity), "result": result, "illegal_plans": 0,
                 "completion_csv": completion, "predictions": []})
    events = [{"schema": "goal-window-raw-v1", "sequence": 17 + i,
               "type": "game_started" if i == 0 else "game_complete" if i == len(data) - 1 else "turn",
               "data": value} for i, value in enumerate(data)]
    return events, expected


class FormatTests(unittest.TestCase):
    def convert(self, events, order=0):
        return convert_game(events, run_order=order)

    def test_exact_mapping_both_arms_and_parities(self):
        for arm in ("BASELINE", "CANDIDATE"):
            for seed in (20000, 20001, 20079):
                events, expected = raw_game(seed, arm)
                self.assertEqual(self.convert(events, expected["run_order"]), expected)

    def test_actual_clean_does_not_require_q(self):
        events, expected = raw_game()
        self.assertFalse(events[2]["data"]["q_accepted"])
        self.assertEqual(self.convert(events)["first_clean_piece"], 2)

    def test_predicted_completion_cannot_create_actual_clean(self):
        events, _ = raw_game(arm="CANDIDATE", clean_at=-1)
        events[1]["data"]["candidate_choice"]["predictedClean"] = True
        self.assertEqual(self.convert(events, 1)["first_clean_piece"], -1)

    def test_empty_death_and_horizon(self):
        for pieces in (0, 1000):
            events, expected = raw_game(pieces=pieces, clean_at=-1)
            self.assertEqual(self.convert(events), expected)

    def test_clean_500_and_501_remain_distinct(self):
        for clean_at in (500, 501):
            events, expected = raw_game(pieces=502, clean_at=clean_at)
            self.assertEqual(self.convert(events), expected)

    def test_result_and_input_are_not_aliased(self):
        events, _ = raw_game()
        before = copy.deepcopy(events)
        converted = self.convert(events)
        self.assertEqual(events, before)
        converted["decision_facts"][0]["decision_nanos"] = 99
        self.assertEqual(events, before)

    def test_streak_and_clean_at_end_use_actual_turns(self):
        events, _ = raw_game()
        for index in (3, 4):
            events[index]["data"]["decision_fact"].update(
                selected_matched_required_cells=32, selected_visual_error_cells=0, clean_completion=True)
            events[index]["data"]["actual_progress"]["matchedRequiredCells"] = 32
        row = events[-1]["data"]["completion_csv"].split(",")
        row[8:11] = ["3", "3", "true"]
        events[-1]["data"]["completion_csv"] = ",".join(row)
        converted = self.convert(events)
        self.assertEqual((converted["clean_turns"], converted["max_clean_streak"], converted["clean_at_end"]),
                         (3, 3, True))

    def test_missing_failure_fields_are_not_filled_with_zero(self):
        for field in ("primaryFailures", "fallbackDecisions"):
            events, _ = raw_game()
            del events[-1]["data"]["result"][field]
            with self.assertRaises(ValueError): self.convert(events)
        events, _ = raw_game()
        del events[-1]["data"]["illegal_plans"]
        with self.assertRaises(ValueError): self.convert(events)

    def test_explicit_failure_or_illegal_plan_is_rejected(self):
        for field in ("primaryFailures", "fallbackDecisions", "illegal_plans"):
            events, _ = raw_game()
            target = events[-1]["data"] if field == "illegal_plans" else events[-1]["data"]["result"]
            target[field] = 1
            with self.assertRaises(ValueError): self.convert(events)

    def test_missing_duplicate_reordered_or_failed_events(self):
        events, _ = raw_game()
        variants = [events[:-1], events[1:], events[:2] + events[3:],
                    events[:2] + [events[1]] + events[2:]]
        swapped = copy.deepcopy(events)
        swapped[1], swapped[2] = swapped[2], swapped[1]
        variants.append(swapped)
        for kind in ("game_incomplete", "session_complete", "verification_complete"):
            changed = copy.deepcopy(events)
            changed[-1]["type"] = kind
            variants.append(changed)
        for variant in variants:
            with self.assertRaises(ValueError): self.convert(variant)

    def test_wrong_identity_order_and_reserved_or_warmup_seed(self):
        for seed in (19000, 19999, 20080, 30000, 31000):
            events, _ = raw_game(seed)
            with self.assertRaises(ValueError): self.convert(events)
        for mutate in (
                lambda e: e[0]["data"].update(phase="WARMUP"),
                lambda e: e[1]["data"]["game"].update(arm="CANDIDATE"),
                lambda e: e[-1]["data"]["result"].update(seed=20001),
                lambda e: e[0].update(schema="other")):
            events, _ = raw_game()
            mutate(events)
            with self.assertRaises(ValueError): self.convert(events)
        for order in (1, -1, 2, True, None):
            events, _ = raw_game()
            with self.assertRaises(ValueError): self.convert(events, order)

    def test_exact_nanos_and_result_mismatch_rejected(self):
        for field in ("piecesPlaced", "decisions", "totalDecisionNanos", "maxDecisionNanos"):
            events, _ = raw_game()
            events[-1]["data"]["result"][field] += 1
            with self.assertRaises(ValueError): self.convert(events)

    def test_false_clean_or_progress_disagreement_rejected(self):
        for mutate in (
                lambda e: e[1]["data"]["decision_fact"].update(clean_completion=True),
                lambda e: e[1]["data"]["decision_fact"].update(selected_visual_error_cells=0),
                lambda e: e[1]["data"]["actual_progress"].update(matchedRequiredCells=32)):
            events, _ = raw_game()
            mutate(events)
            with self.assertRaises(ValueError): self.convert(events)

    def test_elapsed_regression_or_shorter_than_costs(self):
        for value in (0, 1_000_003):
            events, _ = raw_game()
            events[3]["data"]["elapsed_game_nanos"] = value
            with self.assertRaises(ValueError): self.convert(events)

    def test_oversized_elapsed_is_rejected_as_value_error(self):
        events, _ = raw_game()
        for event in events[1:-1]:
            event["data"]["elapsed_game_nanos"] = 10 ** 400
        with self.assertRaises(ValueError):
            self.convert(events)

    def test_csv_summary_tampering_is_rejected(self):
        for index, value in ((1, "CANDIDATE"), (3, "20001"), (4, "3"), (5, "1"),
                             (6, "0.000000"), (7, "0"), (8, "0"), (9, "0"),
                             (10, "True"), (10, "true"), (11, "0.000000")):
            events, _ = raw_game()
            row = events[-1]["data"]["completion_csv"].split(",")
            row[index] = value
            events[-1]["data"]["completion_csv"] = ",".join(row)
            with self.assertRaises(ValueError): self.convert(events)

    def test_output_is_accepted_by_frozen_envelope_analyzer(self):
        envelope = fixture("development")
        for arm in ("baseline", "candidate"):
            envelope["arms"][arm]["games"] = []
            for seed in range(20000, 20020):
                events, expected = raw_game(seed, arm.upper(), pieces=2, clean_at=2 if arm == "candidate" else -1)
                envelope["arms"][arm]["games"].append(self.convert(events, expected["run_order"]))
        report = paired.analyze(envelope, PROTOCOL_BYTES)
        self.assertEqual(report["paired"]["candidate_only_clean"], 20)
        self.assertFalse(report["eligible_for_runtime_validation"])


if __name__ == "__main__":
    unittest.main()
