"""Small artificial byte streams/files/children; no Java or historical input."""
from concurrent.futures import ThreadPoolExecutor
from io import BytesIO
from pathlib import Path
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

from fixed_witness_replay_process import OutputBudget, run_process
from fixed_witness_replay_quota import ByteQuota, OUTPUT_BYTES, OutputLimitError, write_exclusive


class QuotaTest(unittest.TestCase):
    def test_invalid_counts_cannot_inflate_or_refund_credit(self):
        for count in (True, -1, 1.5, float('inf'), '5', None):
            with self.subTest(count=count), self.assertRaises(ValueError):
                ByteQuota(count)
            quota = ByteQuota(5)
            for action in (quota.charge, quota.take_prefix, quota.reserve):
                with self.assertRaises(ValueError):
                    action(count)
            self.assertEqual(quota.remaining, 5)
        for limit in (0, OUTPUT_BYTES + 1):
            with self.assertRaises(ValueError):
                ByteQuota(limit)
        with self.assertRaises(ValueError):
            ByteQuota(5).reserve(0)

    def test_failed_exact_charge_does_not_create_negative_credit(self):
        quota = ByteQuota(5)
        quota.charge(4)
        with self.assertRaises(OutputLimitError):
            quota.charge(2)
        self.assertEqual((quota.charged, quota.remaining), (4, 1))
        quota.charge(1)
        self.assertEqual(quota.take_prefix(100), 0)

    def test_concurrent_writers_cannot_claim_the_same_bytes(self):
        quota = ByteQuota(101)
        with ThreadPoolExecutor(max_workers=8) as workers:
            retained = list(workers.map(quota.take_prefix, [7] * 100))
        self.assertEqual((sum(retained), quota.charged, quota.remaining), (101, 101, 0))

    def test_nested_reservations_and_failure_record_share_the_original_ceiling(self):
        with tempfile.TemporaryDirectory() as directory:
            quota = ByteQuota(16)
            failure = quota.reserve(4)
            child = quota.reserve(7)
            grandchild = child.reserve(3)
            self.assertEqual((quota.remaining, child.remaining), (5, 4))
            self.assertEqual(quota.take_prefix(100), 5)
            self.assertEqual(child.take_prefix(100), 4)
            self.assertEqual(grandchild.take_prefix(100), 3)
            write_exclusive(Path(directory) / 'failure', b'FAIL', failure)
            self.assertEqual((quota.charged, failure.remaining), (16, 0))
            with self.assertRaises(OutputLimitError):
                quota.reserve(1)

    def test_pipe_and_artifact_use_one_allowance_without_double_counting_log(self):
        with tempfile.TemporaryDirectory() as directory:
            quota = ByteQuota(8)
            budget = OutputBudget(quota=quota)
            log = Path(directory) / 'stdout'
            with log.open('xb') as stream:
                self.assertFalse(budget.consume(b'abc', stream))
            write_exclusive(Path(directory) / 'report', b'de', quota)
            with log.open('ab') as stream:
                self.assertTrue(budget.consume(b'fghi', stream))
            self.assertEqual(log.read_bytes(), b'abcfgh')
            self.assertEqual(sum(p.stat().st_size for p in Path(directory).iterdir()), 8)
            self.assertEqual((quota.charged, budget.written, budget.observed), (8, 6, 7))

    def test_artifact_overflow_does_not_create_even_an_empty_file(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / 'report'
            quota = ByteQuota(3)
            with self.assertRaises(OutputLimitError):
                write_exclusive(target, b'four', quota)
            self.assertFalse(target.exists())
            self.assertEqual(quota.remaining, 3)

    def test_exclusive_open_failure_keeps_charge_and_preserves_original_bytes(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / 'report'
            target.write_bytes(b'original')
            quota = ByteQuota(6)
            with self.assertRaises(FileExistsError):
                write_exclusive(target, b'new', quota)
            self.assertEqual((target.read_bytes(), quota.remaining), (b'original', 3))
            # No credit is returned when an output destination cannot be opened.
            with self.assertRaises(FileNotFoundError):
                write_exclusive(Path(directory) / 'missing-parent' / 'new', b'new', quota)
            self.assertEqual(quota.remaining, 0)

    def test_short_or_failed_artifact_writes_keep_full_precharge(self):
        class ShortFile(BytesIO):
            def write(self, data):
                return super().write(data[:1])
        for stream in (ShortFile(), BytesIO()):
            quota = ByteQuota(4)
            with patch.object(Path, 'open', return_value=stream), self.assertRaises(OSError):
                # BytesIO has no fileno: failure after write/flush must keep charge too.
                write_exclusive(Path('unused'), b'data', quota)
            self.assertEqual(quota.remaining, 0)

    def test_pipe_short_write_keeps_charge_and_new_budget_cannot_reset_root(self):
        class ShortFile(BytesIO):
            def write(self, data):
                return super().write(data[:1])
        quota = ByteQuota(4)
        first = OutputBudget(quota=quota)
        with self.assertRaises(OSError):
            first.consume(b'data', ShortFile())
        self.assertEqual((first.written, quota.charged), (4, 4))
        second = OutputBudget(quota=quota)
        sink = BytesIO()
        self.assertTrue(second.consume(b'new', sink))
        self.assertEqual((sink.getvalue(), second.written, second.remaining), (b'', 0, 0))

    def test_separate_pipe_ceiling_cannot_spend_artifact_or_reserved_credit(self):
        quota = ByteQuota(10)
        failure = quota.reserve(2)
        budget = OutputBudget(limit=3, quota=quota)
        self.assertTrue(budget.consume(b'1234', BytesIO()))
        self.assertEqual((budget.remaining, quota.remaining, failure.remaining), (0, 5, 2))
        with self.assertRaises(ValueError):
            OutputBudget(quota=object())

    @unittest.skipUnless(sys.platform == 'linux', 'real Linux child required')
    def test_process_artifact_exhaustion_stops_child_and_forbids_next_launch(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            quota = ByteQuota(16)
            failure = quota.reserve(4)
            write_exclusive(output / 'manifest', b'1234', quota)
            budget = OutputBudget(quota=quota)
            result = run_process([sys.executable, '-c',
                                  "import os,time; os.write(1,b'x'*100); time.sleep(30)"],
                                 output, output, 'child', budget, seconds=2,
                                 total_deadline=time.monotonic() + 5)
            self.assertEqual(result['state'], 'OUTPUT_LIMIT')
            self.assertEqual(result['stdout_stderr_bytes'], 8)
            with self.assertRaises(ValueError):
                run_process([sys.executable, '-c', 'pass'], output, output, 'second', budget,
                            seconds=1, total_deadline=time.monotonic() + 5)
            self.assertFalse((output / 'second-stdout.log').exists())
            write_exclusive(output / 'failure', b'FAIL', failure)
            self.assertEqual(sum(p.stat().st_size for p in output.iterdir()), 16)


if __name__ == '__main__':
    unittest.main()
