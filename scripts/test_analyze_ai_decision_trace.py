"""Contract checks for the desktop AI trace summary."""

from contextlib import redirect_stdout
from io import StringIO
import unittest

from analyze_ai_decision_trace import parse, summarize, timing


class AiDecisionTraceSummaryTest(unittest.TestCase):
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
        self.assertIn("Gravity fallback among applied decisions: 1/3 (33.33%)", output.getvalue())
        self.assertIn("Callback FX queue: mean=1.000 p95=1.000 max=1.000 ms", output.getvalue())

    def test_nearest_rank_percentile_and_malformed_line(self):
        self.assertEqual("mean=10.500 p95=19.000 max=20.000 ms", timing(range(1, 21)))
        with self.assertRaisesRegex(ValueError, "line 2"):
            parse(["unrelated\n", "AI_DECISION outcome=callback elapsed_ms=bad\n"])


if __name__ == "__main__":
    unittest.main()
