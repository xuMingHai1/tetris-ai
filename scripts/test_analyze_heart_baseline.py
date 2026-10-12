"""Reject incomplete or contaminated baseline evidence before reporting HEART metrics."""
import copy
import json
import unittest

from analyze_heart_baseline import PROTOCOL, analyze, nearest_rank, validate_protocol, wilson95


def fixture():
    protocol = json.loads(PROTOCOL.read_text())
    protocol["piece_horizon"] = 3
    protocol["completion_piece_limit"] = 2
    protocol["populations"]["development"] = {"seed_start": 20000, "games": 1}
    protocol["populations"]["calibration"] = {"seed_start": 20001, "games": 1}
    lines = ["# build_shape_warmup seed=19000 games=10 pieces=10 decisions=100",
             "agent,seed,piece_limit,pieces_placed,decisions,primary_failures,fallback_decisions,avg_decision_ms,max_decision_ms,reached_piece_limit",
             "build-shape-heart,20000,3,3,3,0,0,2.000,3.000,true",
             "build-shape-heart,20001,3,3,3,0,0,2.000,3.000,true",
             "build_shape_game,strategy,target,seed,best_visual_error_cells,max_required_with_zero_forbidden,min_forbidden_at_full_required,clean_completion",
             "build_shape_game,build-shape-heart,heart,20000,0,32,0,true",
             "build_shape_game,build-shape-heart,heart,20001,12,20,-1,false",
             "build_shape_outcome,strategy,target,seed,decisions,first_clean_piece,first_clean_elapsed_ms,post_clean_pieces,clean_turns,max_clean_streak,clean_at_end,elapsed_game_ms",
             "build_shape_outcome,build-shape-heart,heart,20000,3,2,5.000000,1,1,1,false,9.000000",
             "build_shape_outcome,build-shape-heart,heart,20001,3,-1,-1.000000,0,0,0,false,9.000000",
             "build_shape_timing,strategy,target,seed,decision,decision_nanos"]
    for seed in (20000, 20001):
        for decision, nanos in enumerate((1000000, 3000000, 2000000), start=1):
            lines.append(f"build_shape_timing,build-shape-heart,heart,{seed},{decision},{nanos}")
    lines.append("build_shape_decision,strategy,target,seed,decision,clean_completion,selected_forbidden_occupied_cells,required_completion_rate,selected_matched_required_cells,selected_visual_error_cells")
    for seed in (20000, 20001):
        for decision in (1, 2, 3):
            clean = seed == 20000 and decision == 2
            matched = 32 if clean else 20 if seed == 20001 else 16
            lines.append(f"build_shape_decision,build-shape-heart,heart,{seed},{decision},{str(clean).lower()},0,{matched / 32},{matched},{32 - matched}")
    return "\n".join(lines), protocol


class HeartBaselineTest(unittest.TestCase):
    def test_reports_completion_and_continues_to_equal_horizon(self):
        text, protocol = fixture()
        report = analyze(text, protocol)
        dev, calibration = report["populations"].values()
        self.assertEqual(1, dev["clean_by_limit"])
        self.assertEqual([1], dev["post_clean_pieces_completed_only"])
        self.assertEqual(5, dev["first_clean_elapsed_ms_median_completed_only"])
        self.assertEqual(3, dev["decision_p95_ms"])
        self.assertEqual(1, dev["clean_and_horizon_games"])
        self.assertEqual(0, calibration["any_clean_games"])
        self.assertIsNone(calibration["full_required_intrusion_min"])
        self.assertIsNone(calibration["first_clean_piece_median_completed_only"])
        self.assertFalse(report["validation_run"])

    def test_late_clean_is_not_counted_as_on_time_success(self):
        text, protocol = fixture()
        protocol["completion_piece_limit"] = 1
        result = analyze(text, protocol)["populations"]["development"]
        self.assertEqual(0, result["clean_by_limit"])
        self.assertEqual(1, result["any_clean_games"])

    def test_missing_timing_or_diagnostic_is_a_failure(self):
        text, protocol = fixture()
        for prefix in ("build_shape_timing,build-shape-heart,heart,20000,2,", "build_shape_decision,build-shape-heart,heart,20000,2,"):
            bad = "\n".join(line for line in text.splitlines() if not line.startswith(prefix))
            with self.subTest(prefix=prefix), self.assertRaises(ValueError):
                analyze(bad, protocol)

    def test_duplicate_or_validation_seed_cannot_enter_baseline(self):
        text, protocol = fixture()
        for bad in (text + "\nbuild_shape_timing,build-shape-heart,heart,20000,1,1000000",
                    text.replace("20001", "30000"), text + "\n" + text):
            with self.subTest(bad=bad[-60:]), self.assertRaises(ValueError):
                analyze(bad, protocol)

    def test_rejects_short_horizon_failure_and_fake_completion(self):
        text, protocol = fixture()
        replacements = [(",3,3,3,0,0,2.000", ",2,3,3,0,0,2.000"),
                        (",3,3,3,0,0,2.000", ",3,3,3,1,1,2.000"),
                        ("3,2,5.000000,1,1,1,false", "3,1,5.000000,1,1,1,false"),
                        ("20000,2,true,0,1", "20000,2,true,1,1"),
                        ("decisions=100", "decisions=99"),
                        ("5.000000", "nan"), ("3.000,true", "nan,true")]
        for before, after in replacements:
            with self.subTest(before=before), self.assertRaises(ValueError):
                analyze(text.replace(before, after), protocol)

    def test_rejects_inconsistent_cost_even_if_game_max_matches(self):
        text, protocol = fixture()
        with self.assertRaises(ValueError):
            analyze(text.replace("20000,1,1000000", "20000,1,0"), protocol)

    def test_rejects_corrupted_shape_aggregates(self):
        text, protocol = fixture()
        for value in ("0,20,-1", "-1,20,-1", "12,999,-1", "12,19,-1", "12,20,0"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                analyze(text.replace("20001,12,20,-1", "20001," + value), protocol)
        with self.assertRaises(ValueError):
            analyze(text.replace("20000,0,32,0,true", "20000,0,32,-1,true"), protocol)

    def test_rejects_invalid_decision_shape_facts(self):
        text, protocol = fixture()
        before = "20000,1,false,0,0.5,16,16"
        for value in ("false,-1,0.5,16,15", "false,17,0.5,16,33",
                      "false,0,1.03125,33,-1", "false,0,nan,16,16",
                      "false,0,0.6,16,16", "false,0,0.5,16,0"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                analyze(text.replace(before, "20000,1," + value), protocol)

    def test_rejects_foreign_agent_rows_without_rejecting_build_logs(self):
        text, protocol = fixture()
        for agent, seed in (("heuristic", 30000), ("jev", 20000), ("future-agent", 20001)):
            with self.subTest(agent=agent), self.assertRaises(ValueError):
                analyze(text + f"\n{agent},{seed},3,3,3,0,0,2.000,3.000,true", protocol)
        with self.assertRaises(ValueError):
            analyze(text + "\nheuristic,30000", protocol)
        analyze("[INFO] build log\n# note,30000\n" + text + "\n[INFO] BUILD SUCCESS", protocol)

    def test_protocol_populations_must_be_disjoint(self):
        _, protocol = fixture()
        bad = copy.deepcopy(protocol)
        bad["populations"]["validation"]["seed_start"] = 20000
        with self.assertRaises(ValueError):
            validate_protocol(bad)

    def test_nearest_rank_and_registered_wilson_boundary(self):
        self.assertEqual(19, nearest_rank(list(range(1, 21))))
        self.assertIsNone(nearest_rank([]))
        self.assertAlmostEqual(0.850594, wilson95(180, 200)[0], places=5)
        self.assertGreater(wilson95(180, 200)[0], 0.85)
        self.assertLess(wilson95(179, 200)[0], 0.85)


if __name__ == "__main__":
    unittest.main()
