"""Contract checks for the desktop AI trace summary."""

from contextlib import redirect_stdout
from io import StringIO
import unittest

from analyze_ai_decision_trace import parse, parse_capture, summarize, timing


class AiDecisionTraceSummaryTest(unittest.TestCase):
    def test_session_seed_is_reported_and_old_logs_remain_readable(self):
        decision = "AI_DECISION outcome=callback elapsed_ms=4.000 fx_queue_ms=1.000 fallback_ms=0.000\n"
        for seed in (1000, -(2**63), 2**63 - 1):
            capture = parse_capture([f"AI_TRACE_SESSION seconds=120 seed={seed}\n", decision])
            output = StringIO()
            with redirect_stdout(output):
                summarize(capture.decisions, session=capture.session)
            self.assertIn(f"7-bag seed: {seed}", output.getvalue())
            self.assertIn("Capture duration: 120 seconds", output.getvalue())
        for lines in ([decision], ["AI_TRACE_SESSION seconds=120\n", decision]):
            capture = parse_capture(lines)
            output = StringIO()
            with redirect_stdout(output):
                summarize(capture.decisions, session=capture.session)
            self.assertIn("7-bag seed: unavailable", output.getvalue())

    def test_invalid_or_combined_sessions_fail_instead_of_mislabeling_evidence(self):
        decision = "AI_DECISION outcome=callback elapsed_ms=4.000 fx_queue_ms=1.000 fallback_ms=0.000\n"
        for session in ("seconds=120 seed=bad", "seconds=120 seed=9223372036854775808",
                        "seconds=120 seed=-9223372036854775809", "seconds=0 seed=1000"):
            with self.subTest(session=session), self.assertRaisesRegex(ValueError, "AI_TRACE_SESSION"):
                parse_capture([f"AI_TRACE_SESSION {session}\n", decision])
        with self.assertRaisesRegex(ValueError, "multiple AI_TRACE_SESSION"):
            parse_capture(["AI_TRACE_SESSION seconds=120 seed=1000\n", decision,
                           "AI_TRACE_SESSION seconds=120 seed=1001\n", decision])

    def test_excludes_cancellation_and_failure_from_deadline_denominator(self):
        lines = [
            "other console output\n",
            "AI_DECISION outcome=callback elapsed_ms=4.000 fx_queue_ms=1.000 fallback_ms=0.000\n",
            "AI_DECISION outcome=gravity-ready elapsed_ms=5.000 fx_queue_ms=-1.000 fallback_ms=0.000\n",
            "AI_DECISION outcome=gravity-fallback elapsed_ms=6.000 fx_queue_ms=-1.000 fallback_ms=2.000\n",
            "AI_DECISION outcome=cancelled elapsed_ms=3.000 fx_queue_ms=-1.000 fallback_ms=0.000\n",
            "AI_DECISION outcome=failed elapsed_ms=7.000 fx_queue_ms=-1.000 fallback_ms=0.000\n",
            "AI_DECISION outcome=failed elapsed_ms=8.000 fx_queue_ms=0.500 fallback_ms=0.000\n",
        ]
        output = StringIO()
        with redirect_stdout(output):
            summarize(parse(lines))

        self.assertIn("Requests: 6", output.getvalue())
        self.assertIn("Gravity fallback among accepted decisions: 1/3 (33.33%)", output.getvalue())
        self.assertIn("Callback FX queue: mean=1.000 p95=1.000 max=1.000 ms", output.getvalue())

    def test_nearest_rank_percentile_and_malformed_line(self):
        self.assertEqual("mean=10.500 p95=19.000 max=20.000 ms", timing(range(1, 21)))
        with self.assertRaisesRegex(ValueError, "line 2"):
            parse(["unrelated\n", "AI_DECISION outcome=callback elapsed_ms=bad\n"])

    def test_computed_rescue_observations_are_separate_from_applied_outcomes(self):
        capture = parse_capture([
            "AI_PREVIEW_RESCUE rank1_evaluated=true preview_unrecoverable=false "
            "replaced=false replacement_rank=0 candidates_probed=0 "
            "detection_ms=0.250 search_ms=0.000\n",
            "AI_PREVIEW_RESCUE rank1_evaluated=true preview_unrecoverable=true "
            "replaced=true replacement_rank=3 candidates_probed=2 "
            "detection_ms=1.000 search_ms=5.000\n",
            "AI_DECISION outcome=callback elapsed_ms=8.000 fx_queue_ms=0.500 "
            "fallback_ms=0.000\n",
        ])
        output = StringIO()
        with redirect_stdout(output):
            summarize(capture.decisions, capture.rescues)
        self.assertIn("Requests: 1", output.getvalue())
        self.assertIn("Computed preview rescue plans: 2 (may include discarded decisions)",
                      output.getvalue())
        self.assertIn("replacement found: 1", output.getvalue())
        self.assertIn("Preview replacement search: mean=5.000 p95=5.000 max=5.000 ms",
                      output.getvalue())
        with self.assertRaisesRegex(ValueError, "line 2"):
            parse_capture(["unrelated\n", "AI_PREVIEW_RESCUE replaced=bad\n"])

    def test_playback_completion_and_interruption_are_separate_from_decisions(self):
        capture = parse_capture([
            "AI_DECISION outcome=callback elapsed_ms=3.000 fx_queue_ms=0.100 "
            "fallback_ms=0.000\n",
            "AI_DECISION outcome=callback elapsed_ms=4.000 fx_queue_ms=0.100 "
            "fallback_ms=0.000\n",
            "AI_PLAYBACK_BLOCKED action=ROTATE_CLOCKWISE piece=L index=1 "
            "plan=[ROTATE_CLOCKWISE, ROTATE_CLOCKWISE, HARD_DROP] cells=[]\n",
            "AI_PLAYBACK outcome=completed elapsed_ms=240.000 controls=4 "
            "executed=4 drop_rows=12\n",
            "AI_PLAYBACK outcome=blocked elapsed_ms=90.000 controls=5 "
            "executed=2 drop_rows=0\n",
            "AI_PLAYBACK outcome=gravity-landed elapsed_ms=180.000 controls=8 "
            "executed=6 drop_rows=0\n",
        ])
        output = StringIO()
        with redirect_stdout(output):
            summarize(capture.decisions, capture.rescues, capture.playbacks)
        self.assertIn("Requests: 2", output.getvalue())
        self.assertIn("Finished playback: 2/3 (66.67%)", output.getvalue())
        self.assertIn("Animated drop rows: 12", output.getvalue())
        with self.assertRaisesRegex(ValueError, "line 2"):
            parse_capture(["unrelated\n",
                           "AI_PLAYBACK outcome=completed elapsed_ms=2.000 "
                           "controls=3 executed=2 drop_rows=0\n"])
        with self.assertRaisesRegex(ValueError, "malformed AI_PLAYBACK at line 2"):
            parse_capture(["AI_DECISION outcome=callback elapsed_ms=3.000 "
                           "fx_queue_ms=0.100 fallback_ms=0.000\n",
                           "AI_PLAYBACK elapsed_ms=2.000 controls=3 executed=3 drop_rows=0\n"])


if __name__ == "__main__":
    unittest.main()
