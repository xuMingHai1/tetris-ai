"""Synthetic allocation and temporary byte writes; no tracer or probes."""
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import tempfile
import unittest

from fixed_witness_replay_quota import ByteQuota, OUTPUT_BYTES, OutputLimitError, write_exclusive
from fixed_witness_replay_writers import COORDINATOR_BYTES, REPORT_BYTES, reserve_writers


class WriterReservationsTest(unittest.TestCase):
    def test_fixed_limits_permanently_exhaust_root(self):
        root = ByteQuota()
        writers = reserve_writers(root)
        self.assertEqual((32768, 32768, 10420224),
                         (writers.tracer.limit, writers.host.limit, writers.coordinator.limit))
        self.assertEqual(OUTPUT_BYTES, root.charged)
        self.assertEqual(0, root.remaining)
        self.assertEqual(OUTPUT_BYTES, sum(q.remaining for q in
                         (writers.tracer, writers.host, writers.coordinator)))

    def test_nonfresh_or_smaller_root_rejected_without_partial_debit(self):
        for root in (ByteQuota(OUTPUT_BYTES - 1), ByteQuota()):
            root.charge(1)
            with self.assertRaises(ValueError):
                reserve_writers(root)
            self.assertEqual(1, root.charged)
        for invalid in (None, OUTPUT_BYTES, True):
            with self.assertRaises(ValueError):
                reserve_writers(invalid)

    def test_second_allocation_cannot_reuse_root(self):
        root = ByteQuota()
        writers = reserve_writers(root)
        with self.assertRaises(OutputLimitError):
            reserve_writers(root)
        self.assertEqual(REPORT_BYTES, writers.host.remaining)
        self.assertEqual(OUTPUT_BYTES, root.charged)

    def test_concurrent_allocation_has_exactly_one_winner(self):
        root = ByteQuota()
        def allocate(_):
            try:
                return reserve_writers(root)
            except OutputLimitError:
                return None
        with ThreadPoolExecutor(max_workers=2) as executor:
            results = list(executor.map(allocate, range(2)))
        self.assertEqual(1, sum(result is not None for result in results))
        self.assertEqual(OUTPUT_BYTES, root.charged)

    def test_exhausted_tracer_cannot_borrow_host_or_coordinator(self):
        writers = reserve_writers(ByteQuota())
        writers.tracer.charge(REPORT_BYTES)
        with self.assertRaises(OutputLimitError):
            writers.tracer.charge(1)
        self.assertEqual(REPORT_BYTES, writers.host.remaining)
        self.assertEqual(COORDINATOR_BYTES, writers.coordinator.remaining)

    def test_failure_prefix_is_inside_its_writer_and_never_refunded(self):
        root = ByteQuota()
        writers = reserve_writers(root)
        failure = writers.host.reserve(4096)
        writers.host.charge(REPORT_BYTES - 4096)
        with self.assertRaises(OutputLimitError):
            writers.host.charge(1)
        failure.charge(4096)
        self.assertEqual(0, failure.remaining)
        self.assertEqual(OUTPUT_BYTES, root.charged)
        self.assertEqual(REPORT_BYTES, writers.tracer.remaining)

    def test_failed_exclusive_write_keeps_only_owner_charge(self):
        root = ByteQuota()
        writers = reserve_writers(root)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'host-report'
            path.write_bytes(b'original')
            with self.assertRaises(FileExistsError):
                write_exclusive(path, b'failure', writers.host)
            self.assertEqual(b'original', path.read_bytes())
        self.assertEqual(7, writers.host.charged)
        self.assertEqual(0, writers.tracer.charged)
        self.assertEqual(0, writers.coordinator.charged)
        self.assertEqual(OUTPUT_BYTES, root.charged)


if __name__ == '__main__':
    unittest.main()
