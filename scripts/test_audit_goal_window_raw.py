"""Synthetic event streams only: no Java launcher, warmup, or population is executed."""
import copy
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

import audit_goal_window_raw as raw
from test_check_goal_window_session_header import fixture as header_fixture


def fixture(warmup_count=10):
    events = []

    def emit(kind, data):
        events.append({"schema": "goal-window-raw-v1", "sequence": len(events) + 1, "type": kind, "data": data})

    emit("provenance_verified", {"manifest": {}, "loaded_classes": {}, "manifest_sha256": "a" * 64})
    emit("session_started", {"expected_warmup_games": 20, "expected_measured_games": 160,
                             "timing_scope": raw.TIMING_SCOPE})
    # Independent schedule fixture; the test does not call the auditor's expected_game.
    games = [("WARMUP", arm, seed, 10) for arm in ("BASELINE", "CANDIDATE") for seed in range(19000, 19010)]
    for seed in range(20000, 20080):
        arms = ("BASELINE", "CANDIDATE") if seed % 2 == 0 else ("CANDIDATE", "BASELINE")
        games.extend(("MEASURED", arm, seed, 1000) for arm in arms)
    for phase, arm, seed, limit in games:
        game = {"phase": phase, "arm": arm, "seed": seed, "pieceLimit": limit}
        count = warmup_count if phase == "WARMUP" else 3
        emit("game_started", game)
        for decision in range(1, count + 1):
            clean = arm == "CANDIDATE" and decision == 2
            emit("turn", {"game": game, "decision_fact": {
                "decision": decision, "decision_nanos": 1000,
                "selected_matched_required_cells": 32 if clean else 0,
                "selected_forbidden_occupied_cells": 0, "selected_visual_error_cells": 0 if clean else 32,
                "clean_completion": clean}, "elapsed_game_nanos": decision * 2000,
                "actual_progress": {"requiredCells": 32, "forbiddenCells": raw.HEART_FORBIDDEN,
                                    "matchedRequiredCells": 32 if clean else 0,
                                    "forbiddenOccupiedCells": 0, "supportOccupiedCells": 0},
                "actual_q": {"clean": clean, "matchedRequired": 32 if clean else 0,
                             "forbiddenOccupied": 0, "maxHeight": 9, "rawHoles": 0,
                             "requiredHoles": 0, "supportHoles": 0, "outsideHoles": 0}, "q_accepted": False,
                "candidate_choice": {} if arm == "CANDIDATE" else None, "resolved_predictions": []})
        clean = arm == "CANDIDATE"
        csv = (f"build_shape_outcome,{arm},heart,{seed},{count},{2 if clean else -1},"
               f"{'0.004000' if clean else '-1.000000'},{count - 2 if clean else 0},"
               f"{1 if clean else 0},{1 if clean else 0},false,{count * .002:.6f}")
        emit("game_complete", {"game": game, "result": {"seed": seed, "pieceLimit": limit,
             "piecesPlaced": count, "decisions": count, "primaryFailures": 0, "fallbackDecisions": 0,
             "totalDecisionNanos": count * 1000, "maxDecisionNanos": 1000,
             "reachedPieceLimit": count == limit}, "illegal_plans": 0,
             "completion_csv": csv, "predictions": []})
    emit("session_complete", {"warmup_games": 20, "measured_games": 160})
    return events


def encode(events):
    return [(json.dumps(e, separators=(",", ":")) + "\n").encode() for e in events]


class RawAuditTests(unittest.TestCase):
    def setUp(self):
        self.events = fixture()

    def reject(self, events=None):
        with self.assertRaises((ValueError, KeyError, TypeError)):
            raw.audit(encode(self.events if events is None else events))

    def first(self, kind, arm=None):
        return next(e["data"] for e in self.events if e["type"] == kind
                    and (arm is None or e["data"].get("game", e["data"]).get("arm") == arm))

    def test_full_structure_hash_and_clean_non_q_do_not_certify_origin_or_gates(self):
        lines = encode(self.events)
        report = raw.audit(iter(lines))
        self.assertEqual(hashlib.sha256(b"".join(lines)).hexdigest(), report["raw_sha256"])
        self.assertEqual(sum(map(len, lines)), report["raw_bytes"])
        self.assertEqual(80, report["measured_games_with_clean_facts"])
        self.assertTrue(report["structurally_complete"])
        for field in ("build_provenance_verified", "seed_sequence_attested", "prototype_gates_evaluated", "runtime_permission"):
            self.assertIs(False, report[field])

    def test_no_final_marker_truncated_line_or_trailing_event_is_success(self):
        self.reject(self.events[:-1])
        with self.assertRaises(ValueError):
            raw.audit(encode(self.events)[:-1] + [encode(self.events)[-1].rstrip(b"\n")])
        self.reject(self.events + [copy.deepcopy(self.events[-1])])

    def test_empty_blank_invalid_utf8_duplicate_fields_and_nonfinite_json_rejected(self):
        for lines in ([], [b"\n"], [b"\xff\n"], [b'{"schema":1,"schema":2}\n'], [b'{"x":NaN}\n']):
            with self.subTest(lines=lines), self.assertRaises(ValueError):
                raw.audit(lines)

    def test_verify_only_incomplete_unknown_events_and_wrong_schema_rejected(self):
        for kind in ("verification_complete", "game_incomplete", "session_incomplete", "unknown"):
            events = copy.deepcopy(self.events)
            events[1]["type"] = kind
            self.reject(events)
        self.events[3]["schema"] = "other"
        self.reject()

    def test_event_sequence_gap_duplicate_and_boolean_rejected(self):
        for value in (1, 3, True):
            self.events[1]["sequence"] = value
            self.reject()

    def test_wrong_fixed_counts_and_timing_scope_rejected(self):
        data = self.events[1]["data"]
        for key, value in (("expected_warmup_games", 2), ("expected_measured_games", 80), ("timing_scope", "plan-only")):
            original = data[key]
            data[key] = value
            self.reject()
            data[key] = original
        self.events[-1]["data"]["warmup_games"] = 19
        self.reject()

    def test_wrong_seed_phase_arm_and_limit_rejected(self):
        game = self.first("game_started")
        for key, value in (("seed", 20000), ("phase", "MEASURED"), ("arm", "CANDIDATE"), ("pieceLimit", 1000)):
            previous = game[key]
            game[key] = value
            self.reject()
            game[key] = previous

    def test_missing_overlapping_and_extra_games_rejected(self):
        for index in (2, 3, -2):
            events = copy.deepcopy(self.events)
            del events[index]
            for sequence, event in enumerate(events, 1):
                event["sequence"] = sequence
            self.reject(events)
        events = copy.deepcopy(self.events)
        events.insert(3, copy.deepcopy(events[2]))
        for sequence, event in enumerate(events, 1):
            event["sequence"] = sequence
        self.reject(events)

    def test_turn_identity_contiguity_and_fact_types_rejected(self):
        turn = self.first("turn")
        for key, value in (("decision", 2), ("decision_nanos", -1), ("decision_nanos", True), ("clean_completion", 0)):
            previous = turn["decision_fact"][key]
            turn["decision_fact"][key] = value
            self.reject()
            turn["decision_fact"][key] = previous
        turn["game"] = {**turn["game"], "seed": 19001}
        self.reject()

    def test_false_clean_and_progress_disagreement_rejected(self):
        turn = self.first("turn")
        turn["decision_fact"]["clean_completion"] = True
        self.reject()
        turn["decision_fact"]["clean_completion"] = False
        turn["actual_progress"]["matchedRequiredCells"] = 1
        self.reject()

    def test_missing_choice_or_wrong_arm_cannot_borrow_previous_observation(self):
        for arm, value in (("BASELINE", {}), ("CANDIDATE", None)):
            turn = self.first("turn", arm)
            original = turn["candidate_choice"]
            turn["candidate_choice"] = value
            self.reject()
            turn["candidate_choice"] = original
        del self.first("turn", "CANDIDATE")["resolved_predictions"]
        self.reject()

    def test_q_is_consistent_and_cannot_replace_clean(self):
        turn = self.first("turn")
        turn["q_accepted"] = 1
        self.reject()
        turn["q_accepted"] = True
        self.reject()

    def test_actual_terminal_record_has_no_synthetic_accepted_property(self):
        self.assertNotIn("accepted", self.first("turn")["actual_q"])
        self.assertTrue(raw.audit(encode(self.events))["structurally_complete"])
        del self.first("turn")["actual_q"]["rawHoles"]
        self.reject()

    def test_fully_consistent_early_warmup_still_rejected(self):
        self.reject(fixture(warmup_count=9))

    def test_elapsed_monotonic_and_complete_cost_pool_required(self):
        turns = [e["data"] for e in self.events if e["type"] == "turn"]
        turns[1]["elapsed_game_nanos"] = 1999
        self.reject()
        turns[1]["elapsed_game_nanos"] = 4000
        turns[0]["elapsed_game_nanos"] = 999
        self.reject()

    def test_summary_horizon_failure_illegal_or_cost_mismatch_rejected(self):
        data = self.first("game_complete")
        for key, value in (("decisions", 9), ("primaryFailures", 1), ("fallbackDecisions", 1),
                           ("totalDecisionNanos", 0), ("maxDecisionNanos", 999), ("reachedPieceLimit", False)):
            original = data["result"][key]
            data["result"][key] = value
            self.reject()
            data["result"][key] = original
        data["illegal_plans"] = 1
        self.reject()

    def test_completion_csv_cannot_claim_clean_missing_from_actual_turns(self):
        data = self.first("game_complete")
        data["completion_csv"] = data["completion_csv"].replace(",10,-1,-1.000000,", ",10,1,0.002000,")
        self.reject()

    def prediction_game(self):
        turns = [e["data"] for e in self.events if e["type"] == "turn"
                 and e["data"]["game"] == {"phase": "MEASURED", "arm": "CANDIDATE",
                                             "seed": 20000, "pieceLimit": 1000}]
        end = next(e["data"] for e in self.events if e["type"] == "game_complete"
                   and e["data"]["game"] == turns[0]["game"])
        turns[0]["candidate_choice"]["choice"] = "PREVIEW_SETUP"
        record = {"setupDecision": 1, "nextValidatedDecision": 2, "resolution": "ACTUAL_CLEAN_NON_Q"}
        turns[1]["resolved_predictions"] = [copy.deepcopy(record)]
        end["predictions"] = [copy.deepcopy(record)]
        return turns, end

    def test_streamed_prediction_and_only_last_turn_pending_are_reconciled(self):
        turns, end = self.prediction_game()
        self.assertTrue(raw.audit(encode(self.events))["structurally_complete"])
        turns[-1]["candidate_choice"]["choice"] = "PREVIEW_SETUP"
        end["predictions"].append({"setupDecision": 3, "nextValidatedDecision": -1,
                                   "resolution": "NO_NEXT_VALIDATED_TURN"})
        self.assertTrue(raw.audit(encode(self.events))["structurally_complete"])
        end["predictions"][-1]["setupDecision"] = 2
        self.reject()

    def test_dropped_injected_altered_or_malformed_predictions_rejected(self):
        for mutation in ("dropped", "injected", "altered", "missing-field", "boolean", "unknown", "pending"):
            self.events = fixture()
            turns, end = self.prediction_game()
            if mutation == "dropped":
                turns[1]["resolved_predictions"] = []
            elif mutation == "injected":
                end["predictions"].append(copy.deepcopy(end["predictions"][0]))
            elif mutation == "altered":
                end["predictions"][0]["resolution"] = "ACTUAL_NOT_CLEAN"
            elif mutation == "missing-field":
                del turns[1]["resolved_predictions"][0]["resolution"]
            elif mutation == "boolean":
                turns[1]["resolved_predictions"][0]["setupDecision"] = True
            elif mutation == "unknown":
                turns[1]["resolved_predictions"][0]["resolution"] = "INVENTED"
            else:
                turns[1]["resolved_predictions"][0]["nextValidatedDecision"] = -1
            with self.subTest(mutation=mutation):
                self.reject()

    def test_predictions_cannot_appear_without_setup_or_in_baseline(self):
        turns, end = self.prediction_game()
        turns[0]["candidate_choice"]["choice"] = "BASELINE_CHOICE"
        self.reject()
        self.events = fixture()
        self.first("turn")["resolved_predictions"] = [{"setupDecision": 1,
            "nextValidatedDecision": 2, "resolution": "ACTUAL_NOT_CLEAN"}]
        self.reject()

    def test_final_prediction_id_types_cannot_hide_behind_numeric_equality(self):
        for key, value in (("setupDecision", True), ("setupDecision", 1.0),
                           ("nextValidatedDecision", 2.0)):
            self.events = fixture()
            _, end = self.prediction_game()
            end["predictions"][0][key] = value
            with self.subTest(key=key, value=value):
                self.reject()

    def test_timing_integers_larger_than_java_long_are_rejected(self):
        for field in ("decision_nanos", "elapsed_game_nanos"):
            self.events = fixture()
            turn = self.first("turn")
            target = turn["decision_fact"] if field == "decision_nanos" else turn
            target[field] = 10**400
            self.reject()

    def test_bound_header_and_completeness_use_one_stream(self):
        manifest, header = header_fixture()
        self.events[0] = header
        lines = encode(self.events)
        expected = hashlib.sha256(manifest).hexdigest()
        result = raw.audit(iter(lines), manifest_bytes=manifest, expected_manifest_sha256=expected)
        self.assertEqual(result["raw_sha256"], hashlib.sha256(b"".join(lines)).hexdigest())
        self.assertEqual(result["session_header_check"]["manifest_sha256"], expected)
        self.assertTrue(result["structurally_complete"])
        self.assertFalse(result["build_provenance_verified"])
        self.assertFalse(result["runtime_permission"])
        self.assertFalse(result["session_header_check"]["provenance_authenticated"])
        self.assertFalse(result["session_header_check"]["raw_completeness_verified"])
        with self.assertRaises(ValueError):
            raw.audit(iter(lines[:-1]), manifest_bytes=manifest, expected_manifest_sha256=expected)
        for supplied, digest in ((manifest, None), (None, expected), (manifest, "0" * 64)):
            with self.assertRaises(ValueError):
                raw.audit(iter(lines), manifest_bytes=supplied, expected_manifest_sha256=digest)
        # Changing the same consumed header must fail even though the rest is complete.
        name = next(iter(header["data"]["loaded_classes"]))
        header["data"]["loaded_classes"][name]["sha256"] = "0" * 64
        with self.assertRaises(ValueError):
            raw.audit(encode(self.events), manifest_bytes=manifest, expected_manifest_sha256=expected)

    def test_cli_manifest_binding_rejects_mismatch_partial_options_and_incomplete_raw(self):
        manifest, header = header_fixture()
        self.events[0] = header
        original = b"".join(encode(self.events))
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source, build, output = root / "raw.jsonl", root / "build.json", root / "audit.json"
            source.write_bytes(original)
            build.write_bytes(manifest)
            command = [sys.executable, str(Path(raw.__file__)), "--input", str(source), "--output", str(output)]
            options = ["--manifest", str(build), "--manifest-sha256", hashlib.sha256(manifest).hexdigest()]
            result = subprocess.run(command + options, capture_output=True, timeout=30)
            self.assertEqual(0, result.returncode, result.stderr)
            report = output.read_bytes()
            self.assertIn("session_header_check", json.loads(report))
            # Exclusive output creation applies to the bound mode too.
            self.assertEqual(2, subprocess.run(command + options, capture_output=True, timeout=30).returncode)
            self.assertEqual(report, output.read_bytes())
            output.unlink()
            for extra in (options[:2], options[2:], options[:3] + ["0" * 64]):
                rejected = subprocess.run(command + extra, capture_output=True, timeout=30)
                self.assertEqual(2, rejected.returncode, rejected.stderr)
                self.assertNotIn(b"Traceback", rejected.stderr)
                self.assertFalse(output.exists())
            self.assertEqual(original, source.read_bytes())
            self.assertEqual(manifest, build.read_bytes())
            source.write_bytes(b"".join(encode(self.events[:-1])))
            rejected = subprocess.run(command + options, capture_output=True, timeout=30)
            self.assertEqual(2, rejected.returncode, rejected.stderr)
            self.assertNotIn(b"Traceback", rejected.stderr)
            self.assertFalse(output.exists())

    def test_cli_rejects_deep_json_without_traceback_or_output(self):
        with tempfile.TemporaryDirectory() as directory:
            source, output = Path(directory) / "raw.jsonl", Path(directory) / "audit.json"
            original = b'[' * 20000 + b'0' + b']' * 20000 + b'\n'
            source.write_bytes(original)
            command = [sys.executable, str(Path(raw.__file__)), "--input", str(source), "--output", str(output)]
            rejected = subprocess.run(command, capture_output=True, timeout=30)
            self.assertEqual(2, rejected.returncode, rejected.stderr.decode())
            self.assertIn(b"raw audit rejected:", rejected.stderr)
            self.assertNotIn(b"Traceback", rejected.stderr)
            self.assertFalse(output.exists())
            self.assertEqual(original, source.read_bytes())

    def test_cli_preserves_input_and_existing_report_and_rejects_incomplete(self):
        with tempfile.TemporaryDirectory() as directory:
            source, output = Path(directory) / "raw.jsonl", Path(directory) / "audit.json"
            original = b"".join(encode(self.events))
            source.write_bytes(original)
            command = [sys.executable, str(Path(raw.__file__)), "--input", str(source), "--output", str(output)]
            run = subprocess.run(command, capture_output=True, timeout=30)
            self.assertEqual(0, run.returncode, run.stderr.decode())
            report = output.read_bytes()
            self.assertEqual(2, subprocess.run(command, capture_output=True, timeout=30).returncode)
            self.assertEqual(report, output.read_bytes())
            self.assertEqual(original, source.read_bytes())
            source.write_bytes(b"".join(encode(self.events[:-1])))
            output.unlink()
            self.assertEqual(2, subprocess.run(command, capture_output=True, timeout=30).returncode)
            self.assertFalse(output.exists())
            self.first("turn")["elapsed_game_nanos"] = 10**400
            source.write_bytes(b"".join(encode(self.events)))
            rejected = subprocess.run(command, capture_output=True, timeout=30)
            self.assertEqual(2, rejected.returncode)
            self.assertNotIn(b"Traceback", rejected.stderr)
            self.assertFalse(output.exists())


if __name__ == "__main__":
    unittest.main()
