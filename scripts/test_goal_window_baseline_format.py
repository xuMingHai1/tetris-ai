"""Synthetic envelopes only; no JVM, warmup or population is executed."""
import copy
import unittest

import analyze_heart_baseline as baseline
import goal_window_baseline_format as adapter
from test_analyze_heart_paired import fixture, game, PROTOCOL, PROTOCOL_BYTES


def inputs():
    result = [fixture(p) for p in ("development", "calibration")]
    for data in result:
        for name, arm in data["arms"].items():
            arm["games"] = [game(g["seed"], name, pieces=3,
                                 clean_at=2 if name == "candidate" else -1,
                                 nanos=1_000_003) for g in arm["games"]]
    return result


class BaselineFormatTests(unittest.TestCase):
    def setUp(self):
        self.dev, self.cal = inputs()

    def render(self):
        return adapter.render_baseline_tables(self.dev, self.cal, PROTOCOL_BYTES)

    def test_frozen_consumer_reads_all_80_baseline_games_without_candidate_clean(self):
        original = copy.deepcopy((self.dev, self.cal))
        text = self.render()
        self.assertEqual(original, (self.dev, self.cal))
        self.assertIn("evidence_kind=synthetic", text)
        self.assertIn("not original benchmark stdout", text)
        tables = baseline.parse_tables(text)
        self.assertEqual(80, len(tables["agent"]))
        self.assertEqual(240, len(tables["build_shape_timing"]))
        self.assertEqual({"1000003"}, {f["decision_nanos"] for f in tables["build_shape_timing"]})
        report = baseline.analyze(text, PROTOCOL)
        for population in report["populations"].values():
            self.assertEqual(0, population["any_clean_games"])
            self.assertAlmostEqual(1.000003, population["decision_max_ms"])

    def test_actual_baseline_clean_and_intrusion_sentinels_survive_projection(self):
        self.dev["arms"]["baseline"]["games"][0] = game(20000, "baseline", 3, 2)
        report = baseline.analyze(self.render(), PROTOCOL)
        self.assertEqual(1, report["populations"]["development"]["any_clean_games"])
        self.assertEqual(0, report["populations"]["development"]["full_required_intrusion_min"])
        self.assertIsNone(report["populations"]["calibration"]["full_required_intrusion_min"])

    def test_empty_game_preserves_zero_costs_and_no_clean(self):
        self.dev["arms"]["baseline"]["games"][0] = game(20000, "baseline", 0)
        row = baseline.parse_tables(self.render())["agent"][0]
        self.assertEqual(("0", "0.000", "false"), (row["decisions"], row["max_decision_ms"], row["reached_piece_limit"]))

    def test_rejects_missing_duplicate_or_reserved_games(self):
        for change in (lambda a: a.pop(), lambda a: a.append(a[0]),
                       lambda a: a.__setitem__(0, game(30000, "baseline", 3))):
            self.dev, self.cal = inputs()
            change(self.dev["arms"]["baseline"]["games"])
            with self.assertRaises(ValueError): self.render()

    def test_rejects_wrong_population_order_and_changed_protocol(self):
        with self.assertRaises(ValueError):
            adapter.render_baseline_tables(self.cal, self.dev, PROTOCOL_BYTES)
        with self.assertRaises(ValueError):
            adapter.render_baseline_tables(self.dev, self.cal, PROTOCOL_BYTES + b" ")

    def test_mixed_environment_evidence_or_arm_identity_rejected(self):
        mutations = [lambda d: d["environment"].update(cpu="other"),
                     lambda d: d.update(evidence_kind="measured"),
                     lambda d: d["arms"]["baseline"].update(source_sha="b" * 40),
                     lambda d: d["arms"]["candidate"].update(source_sha="b" * 40)]
        for mutate in mutations:
            self.dev, self.cal = inputs()
            mutate(self.cal)
            with self.assertRaises(ValueError): self.render()

    def test_invalid_candidate_is_not_hidden_by_baseline_projection(self):
        del self.dev["arms"]["candidate"]["games"][0]["decision_facts"]
        with self.assertRaises(ValueError): self.render()

    def test_baseline_failure_fallback_or_illegal_never_exported_as_normal_death(self):
        for field in ("primary_failures", "fallback_decisions", "illegal_plans"):
            self.dev, self.cal = inputs()
            self.dev["arms"]["baseline"]["games"][0][field] = 1
            with self.assertRaises(ValueError): self.render()

    def test_missing_failure_field_or_false_timing_and_shape_rejected(self):
        for field in ("illegal_plans", "primary_failures", "fallback_decisions"):
            self.dev, self.cal = inputs()
            del self.dev["arms"]["baseline"]["games"][0][field]
            with self.assertRaises(ValueError): self.render()
        for field in ("total_decision_nanos", "best_visual_error_cells", "clean_turns"):
            self.dev, self.cal = inputs()
            self.dev["arms"]["baseline"]["games"][0][field] += 1
            with self.assertRaises(ValueError): self.render()


if __name__ == "__main__":
    unittest.main()
