"""Synthetic evidence only; no planner is run and no reserved population is consumed."""
from functools import lru_cache
import hashlib
import json
import math
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

import analyze_heart_paired as paired

PROTOCOL_BYTES = paired.PROTOCOL.read_bytes()
PROTOCOL = json.loads(PROTOCOL_BYTES)


@lru_cache(maxsize=64)
def facts(pieces, clean_at, nanos):
    return [{"decision": i, "decision_nanos": nanos,
             "selected_matched_required_cells": 32 if i == clean_at else 0,
             "selected_forbidden_occupied_cells": 0,
             "selected_visual_error_cells": 0 if i == clean_at else 32,
             "clean_completion": i == clean_at} for i in range(1, pieces + 1)]


def game(seed, arm, pieces=900, clean_at=-1, nanos=1_000_000):
    clean = clean_at > 0
    return {"seed": seed, "run_order": seed % 2 if arm == "baseline" else 1 - seed % 2,
            "piece_limit": 1000, "pieces_placed": pieces, "decisions": pieces,
            "reached_piece_limit": pieces == 1000, "primary_failures": 0,
            "fallback_decisions": 0, "illegal_plans": 0,
            "decision_facts": facts(pieces, clean_at, nanos),
            "first_clean_piece": clean_at, "first_clean_elapsed_ms": clean_at * nanos / 1e6 if clean else -1,
            "elapsed_game_ms": pieces * nanos / 1e6, "post_clean_pieces": pieces - clean_at if clean else 0,
            "clean_turns": int(clean), "max_clean_streak": int(clean), "clean_at_end": clean_at == pieces,
            "best_visual_error_cells": 0 if clean else 32,
            "max_required_with_zero_forbidden": 32 if clean else 0,
            "min_forbidden_at_full_required": 0 if clean else -1,
            "total_decision_nanos": pieces * nanos, "max_decision_nanos": nanos if pieces else 0}


def fixture(population="validation", evidence="synthetic"):
    seeds = sorted(paired.seed_set(PROTOCOL["populations"][population]))
    data = {"schema": paired.SCHEMA, "protocol_sha256": hashlib.sha256(PROTOCOL_BYTES).hexdigest(),
            "population": population, "evidence_kind": evidence,
            "timing_scope": paired.TIMING_SCOPE, "input_boundary": "post-spawn-gravity-known-preview",
            "reset_strategy_per_game": True,
            "environment": {k: "synthetic-test" for k in ("os", "arch", "cpu", "ram", "jdk", "javafx", "jvm_flags", "clock")},
            "arms": {}}
    for arm in ("baseline", "candidate"):
        config = {"agent": "build-shape" if arm == "baseline" else "synthetic-candidate",
                  "rows": 20, "cols": 10, "target": "heart", "piece_horizon": 1000,
                  "preview_rescue": False, "parameters": {}}
        data["arms"][arm] = {"source_sha": "a" * 40, "config": config, "config_sha256": paired.digest(config),
                             "production_base_sha": PROTOCOL["production_base_sha"],
                             "warmup": {**PROTOCOL["warmup"], "decisions": 100},
                             "games": [game(s, arm, 800 if arm == "baseline" else 900,
                                            500 if arm == "candidate" and i < math.ceil(len(seeds) * .9) else -1)
                                       for i, s in enumerate(seeds)]}
    return data


class PairedTests(unittest.TestCase):
    def report(self, data=None):
        return paired.analyze(fixture() if data is None else data, PROTOCOL_BYTES)

    def replace(self, data, arm, index, **kwargs):
        seed = data["arms"][arm]["games"][index]["seed"]
        data["arms"][arm]["games"][index] = game(seed, arm, **kwargs)

    def test_all_frozen_gates_pass_180_of_200_but_synthetic_cannot_adopt(self):
        report = self.report()
        self.assertTrue(report["all_headless_gates_pass"])
        self.assertFalse(report["eligible_for_runtime_validation"])
        self.assertEqual(report["conclusion"], "diagnostic-only-no-adoption")
        self.assertAlmostEqual(report["arms"]["candidate"]["clean_wilson95"][0], .850594187567, places=10)
        self.assertEqual(report["paired"]["wins"], 200)

    def test_measured_validation_only_allows_next_gate_not_runtime_adoption(self):
        report = self.report(fixture(evidence="measured"))
        self.assertTrue(report["eligible_for_runtime_validation"])
        self.assertEqual(report["conclusion"], "headless-pass-runtime-validation-only")

    def test_development_and_calibration_are_diagnostic(self):
        for population in ("development", "calibration"):
            report = self.report(fixture(population, "measured"))
            self.assertFalse(report["eligible_for_runtime_validation"])
            self.assertEqual(report["conclusion"], "diagnostic-only-no-adoption")

    def test_exact_binomial_small_cases_and_zero_discordance(self):
        for won, lost, expected in ((0, 0, 1), (5, 0, 1/32), (4, 1, 6/32), (0, 5, 1), (3, 3, 42/64)):
            self.assertEqual(paired.exact_clean_p(won, lost), expected)
        data = fixture()
        for i in range(180):
            self.replace(data, "baseline", i, pieces=800, clean_at=500)
        self.assertFalse(self.report(data)["gates"]["maximum_paired_clean_p_value"]["pass"])

    def test_clean_500_501_and_wilson_boundary(self):
        data = fixture()
        self.replace(data, "candidate", 179, clean_at=501)
        report = self.report(data)
        self.assertEqual(report["arms"]["candidate"]["any_clean_games"], 180)
        self.assertEqual(report["arms"]["candidate"]["clean_by_limit"], 179)
        self.assertEqual(report["arms"]["candidate"]["first_clean_denominator"], 180)
        for gate in ("minimum_clean_rate", "minimum_wilson95_lower_bound"):
            self.assertFalse(report["gates"][gate]["pass"])

    def test_no_completion_unavailable_and_intrusion_sentinel(self):
        baseline = self.report()["arms"]["baseline"]
        self.assertIsNone(baseline["first_clean_piece_median_any_clean"])
        self.assertIsNone(baseline["first_clean_ms_median_by_limit"])
        self.assertIsNone(baseline["full_required_intrusion_min"])
        self.assertEqual(baseline["first_clean_denominator"], 0)
        self.assertEqual(baseline["never_full_required_games"], 200)
        self.assertEqual(self.report()["arms"]["candidate"]["full_required_intrusion_min"], 0)

    def test_positive_aggregate_does_not_hide_tail_regressions(self):
        data = fixture()
        for i in range(11):
            self.replace(data, "candidate", i, pieces=799, clean_at=500)
        report = self.report(data)
        self.assertGreater(report["paired"]["pieces_delta_mean"], 0)
        self.assertFalse(report["gates"]["maximum_regressed_seed_fraction"]["pass"])
        self.assertFalse(report["all_headless_gates_pass"])
        self.replace(data, "candidate", 10, pieces=800, clean_at=500)
        self.assertTrue(self.report(data)["gates"]["maximum_regressed_seed_fraction"]["pass"])

    def test_maximum_loss_100_101_boundary(self):
        data = fixture()
        self.replace(data, "candidate", 0, pieces=700, clean_at=500)
        self.assertTrue(self.report(data)["gates"]["maximum_piece_loss"]["pass"])
        self.replace(data, "candidate", 0, pieces=699, clean_at=500)
        report = self.report(data)
        self.assertFalse(report["gates"]["maximum_piece_loss"]["pass"])
        self.assertGreater(report["paired"]["pieces_delta_mean"], 0)

    def test_survivor_loss_and_horizon_loss_independent(self):
        data = fixture()
        for i in range(4):
            self.replace(data, "baseline", i, pieces=1000)
        report = self.report(data)
        self.assertFalse(report["gates"]["maximum_baseline_survivors_lost"]["pass"])
        self.assertTrue(report["gates"]["maximum_horizon_rate_loss"]["pass"])
        self.replace(data, "baseline", 4, pieces=1000)
        self.assertFalse(self.report(data)["gates"]["maximum_horizon_rate_loss"]["pass"])

    def test_mean_and_median_have_separate_gates(self):
        data = fixture()
        for i in range(200):
            self.replace(data, "candidate", i, pieces=800, clean_at=500 if i < 180 else -1)
        self.replace(data, "candidate", 0, pieces=799, clean_at=500)
        report = self.report(data)
        self.assertFalse(report["gates"]["minimum_mean_piece_delta"]["pass"])
        self.assertTrue(report["gates"]["minimum_median_piece_delta"]["pass"])
        for i in range(101):
            self.replace(data, "candidate", i, pieces=799, clean_at=500)
        for i in range(101, 200):
            self.replace(data, "candidate", i, pieces=900, clean_at=500 if i < 180 else -1)
        report = self.report(data)
        self.assertTrue(report["gates"]["minimum_mean_piece_delta"]["pass"])
        self.assertFalse(report["gates"]["minimum_median_piece_delta"]["pass"])

    def test_cost_p95_and_max_boundaries_in_both_arms(self):
        self.assertEqual(paired.nearest_rank(list(range(1, 21))), 19)
        data = fixture()
        for i in range(200):
            self.replace(data, "candidate", i, pieces=900, clean_at=500 if i < 180 else -1, nanos=50_000_000)
        self.assertTrue(self.report(data)["gates"]["candidate.decision_p95_ms"]["pass"])
        for i in range(200):
            self.replace(data, "candidate", i, pieces=900, clean_at=500 if i < 180 else -1, nanos=50_000_001)
        self.assertFalse(self.report(data)["gates"]["candidate.decision_p95_ms"]["pass"])
        for arm in ("baseline", "candidate"):
            data = fixture()
            self.replace(data, arm, 0, pieces=800, nanos=250_000_000)
            self.assertTrue(self.report(data)["gates"][arm + ".decision_max_ms"]["pass"])
            self.replace(data, arm, 0, pieces=800, nanos=250_000_001)
            report = self.report(data)
            self.assertFalse(report["gates"][arm + ".decision_max_ms"]["pass"])
            self.assertTrue(report["gates"][arm + ".decision_p95_ms"]["pass"])

    def test_correctness_failures_are_gates_not_silently_discarded(self):
        for arm in ("baseline", "candidate"):
            for key in ("primary_failures", "fallback_decisions", "illegal_plans"):
                data = fixture()
                data["arms"][arm]["games"][0][key] = 1
                self.assertFalse(self.report(data)["gates"][arm + "." + key]["pass"])

    def test_missing_duplicate_unexpected_or_unpaired_seeds_fail(self):
        for mutation in ("missing", "duplicate", "unexpected"):
            data = fixture()
            games = data["arms"]["candidate"]["games"]
            if mutation == "missing":
                games.pop()
            elif mutation == "duplicate":
                games.append(games[0])
            else:
                games[0]["seed"] = 20000
            with self.assertRaises(ValueError):
                self.report(data)

    def test_missing_or_inconsistent_decision_facts_fail_closed(self):
        mutations = [lambda g: g.pop("first_clean_piece"),
                     lambda g: g.update(first_clean_elapsed_ms=-1),
                     lambda g: g.update(post_clean_pieces=0),
                     lambda g: g.update(piece_limit=999),
                     lambda g: g.update(run_order=0),
                     lambda g: g.update(best_visual_error_cells=10),
                     lambda g: g.update(total_decision_nanos=0),
                     lambda g: g.update(elapsed_game_ms=0),
                     lambda g: g.update(decision_facts=g["decision_facts"][:-1])]
        for mutate in mutations:
            data = fixture()
            mutate(data["arms"]["candidate"]["games"][0])
            with self.assertRaises((ValueError, KeyError)):
                self.report(data)
        for key, value in (("decision", 2), ("decision_nanos", -1),
                           ("clean_completion", True), ("selected_visual_error_cells", 0),
                           ("selected_matched_required_cells", 33)):
            data = fixture()
            g = data["arms"]["candidate"]["games"][0]
            g["decision_facts"] = list(g["decision_facts"])
            g["decision_facts"][0] = {**g["decision_facts"][0], key: value}
            with self.assertRaises(ValueError):
                self.report(data)

    def test_invalid_metadata_protocol_and_warmup_fail(self):
        mutations = [lambda d: d.update(protocol_sha256="0" * 64),
                     lambda d: d.update(timing_scope="planner-internal"),
                     lambda d: d.update(reset_strategy_per_game=False),
                     lambda d: d["environment"].pop("jdk"),
                     lambda d: d["arms"]["candidate"].update(source_sha="short"),
                     lambda d: d["arms"]["candidate"]["warmup"].update(decisions=99),
                     lambda d: d["arms"]["candidate"]["config"].update(piece_horizon=999),
                     lambda d: d["arms"]["baseline"].update(production_base_sha="b" * 40)]
        for mutate in mutations:
            data = fixture()
            mutate(data)
            with self.assertRaises((ValueError, KeyError)):
                self.report(data)
        with self.assertRaises(ValueError):
            paired.analyze(fixture(), PROTOCOL_BYTES + b" ")
        with self.assertRaises(ValueError):
            json.loads('{"seed":1,"seed":2}', object_pairs_hook=paired.unique_object)

    def test_nonfinite_or_boolean_numeric_data_rejected(self):
        for key, value in (("elapsed_game_ms", float("nan")), ("first_clean_elapsed_ms", float("inf")),
                           ("decisions", True), ("primary_failures", -1)):
            data = fixture()
            data["arms"]["candidate"]["games"][0][key] = value
            with self.assertRaises(ValueError):
                self.report(data)

    def test_cli_artifacts_and_invalid_input_exit(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            input_path, output_path, csv_path = (root / name for name in ("input.json", "summary.json", "pairs.csv"))
            command = [sys.executable, str(Path(paired.__file__)), str(input_path),
                       "--output", str(output_path), "--pairs-csv", str(csv_path)]
            input_path.write_text(json.dumps(fixture("development")), encoding="utf-8")
            result = subprocess.run(command, capture_output=True, text=True, check=False)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn("diagnostic-only-no-adoption", result.stdout)
            self.assertEqual(len(csv_path.read_text(encoding="utf-8").splitlines()), 21)
            self.assertFalse(json.loads(output_path.read_text(encoding="utf-8"))["eligible_for_runtime_validation"])
            bad_path = root / "invalid-summary.json"
            input_path.write_text('{"schema":"bad","schema":"duplicate"}', encoding="utf-8")
            command[command.index("--output") + 1] = str(bad_path)
            result = subprocess.run(command, capture_output=True, text=True, check=False)
            self.assertEqual(result.returncode, 2)
            self.assertFalse(bad_path.exists())


if __name__ == "__main__":
    unittest.main()
