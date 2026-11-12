"""Actual files/accounting and composed lifecycle; all native transports fake."""
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from fixed_witness_replay_quota import ByteQuota, OUTPUT_BYTES
from fixed_witness_replay_stdio import CoordinatorOutput
from fixed_witness_replay_supervisor import TracerSupervisor, SupervisorError
from fixed_witness_replay_tracer_evidence import TracerEvidence
from fixed_witness_replay_writers import reserve_writers, REPORT_BYTES
import test_fixed_witness_replay_supervisor as fixture


class TracerEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.path = Path(self.directory.name)
        self.root = ByteQuota()
        self.writers = reserve_writers(self.root)
        self.f = fixture.SupervisorTest()
        self.f.setUp()

    def owner(self):
        evidence = TracerEvidence(self.writers, self.path)
        output = CoordinatorOutput(self.writers, self.path)
        self.addCleanup(output.close)
        self.f.launch.stdio = (999, *output.writers)
        return self.f.owner(output=output, evidence=evidence)

    def record(self):
        return json.loads((self.path / 'tracer-lifecycle.json').read_bytes())

    def test_composed_success_charges_prefix_and_observation_only_to_tracer(self):
        owner = self.owner()
        os.write(owner.output.writers[0], b'out')
        result = owner.run()
        record = self.record()
        self.assertEqual('DRAINED_OBSERVATION', record['state'])
        self.assertFalse(record['suite_verified'])
        self.assertTrue(record['output_closed'])
        self.assertFalse(record['failed'])
        self.assertEqual((result.attempts, result.live, result.pending, result.wait_stops),
                         tuple(record[k] for k in ('attempts', 'live', 'pending', 'wait_stops')))
        self.assertEqual(b'INCOMPLETE\n', (self.path / 'tracer-incomplete.txt').read_bytes())
        self.assertEqual(sum((self.path / name).stat().st_size for name in
                             ('tracer-incomplete.txt', 'tracer-lifecycle.json')),
                         self.writers.tracer.charged)
        self.assertLess(self.writers.tracer.charged, REPORT_BYTES)
        self.assertEqual((3, 0, OUTPUT_BYTES),
                         (self.writers.coordinator.charged, self.writers.host.charged, self.root.charged))

    def test_original_wait_failure_is_reported_after_real_shaped_cleanup(self):
        failure = OSError('private credential-like error must never enter evidence')
        self.f.io.wait.side_effect = [(11, 0x137f), failure, (11, 9)]
        owner = self.owner()
        with self.assertRaises(OSError) as raised:
            owner.run()
        self.assertIs(failure, raised.exception)
        record = self.record()
        self.assertEqual(('INCOMPLETE', 1, 0), (record['state'], record['live'], record['pending']))
        self.assertTrue(record['failed'])
        self.assertFalse(record['cleanup_failed'])
        self.assertTrue(record['output_closed'])
        self.assertNotIn(b'credential', (self.path / 'tracer-lifecycle.json').read_bytes())
        self.assertEqual([1], owner.cleanup.signalled)

    def test_cleanup_failure_is_retained_without_false_drained_record(self):
        original = OSError('wait failed')
        self.f.io.wait.side_effect = [(11, 0x137f), original]
        self.f.io.open.side_effect = OSError('pin failed')
        owner = self.owner()
        with self.assertRaises(OSError) as raised:
            owner.run()
        self.assertIs(original, raised.exception)
        self.assertEqual(2, self.record()['live'])
        self.assertTrue(self.record()['cleanup_failed'])
        self.assertEqual('INCOMPLETE', self.record()['state'])

    def test_prefork_failure_keeps_prefix_and_reports_without_wait_or_signal(self):
        self.f.f.clock.return_value = 100.0
        owner = self.owner()
        with self.assertRaisesRegex(ValueError, 'deadline'):
            owner.run()
        self.assertEqual((1, 1, False), tuple(self.record()[k] for k in
                         ('attempts', 'live', 'handshake_admitted')))
        self.f.f.system.fork.assert_not_called()
        self.f.io.wait.assert_not_called()
        self.f.io.send.assert_not_called()

    def test_output_close_error_precedes_terminal_observation(self):
        owner = self.owner()
        original = os.close
        def failed(fd):
            original(fd)
            if fd == owner.output.readers[0]:
                raise OSError('uncertain close')
        with patch('fixed_witness_replay_stdio.os.close', side_effect=failed):
            with self.assertRaisesRegex(ValueError, 'close failed'):
                owner.run()
        self.assertTrue(self.record()['output_failed'])
        self.assertTrue(self.record()['failed'])
        self.assertEqual('INCOMPLETE', self.record()['state'])

    def test_final_artifact_collision_prevents_success_and_never_overwrites(self):
        owner = self.owner()
        path = self.path / 'tracer-lifecycle.json'
        path.write_bytes(b'original')
        with self.assertRaisesRegex(SupervisorError, 'evidence failed'):
            owner.run()
        self.assertFalse(owner.drained)
        self.assertIsInstance(owner.evidence_error, FileExistsError)
        self.assertEqual(b'original', path.read_bytes())
        charged = self.writers.tracer.charged
        with self.assertRaises(SupervisorError):
            owner.run()
        self.assertEqual(charged, self.writers.tracer.charged)

    def test_report_failure_never_replaces_original_wait_failure(self):
        owner = self.owner()
        original = OSError('first failure')
        self.f.io.wait.side_effect = [(11, 0x137f), original, (11, 9)]
        (self.path / 'tracer-lifecycle.json').write_bytes(b'occupied')
        with self.assertRaises(OSError) as raised:
            owner.run()
        self.assertIs(original, raised.exception)
        self.assertIs(original, owner.failure)
        self.assertIsInstance(owner.evidence_error, FileExistsError)
        self.assertIsNone(owner.cleanup_error)

    def test_full_partition_never_borrows_host_or_coordinator_for_final_record(self):
        owner = self.owner()
        self.writers.tracer.charge(self.writers.tracer.remaining)
        with self.assertRaisesRegex(SupervisorError, 'evidence failed'):
            owner.run()
        self.assertFalse((self.path / 'tracer-lifecycle.json').exists())
        self.assertEqual((0, 0), (self.writers.host.charged, self.writers.coordinator.charged))
        self.assertEqual(REPORT_BYTES, self.writers.tracer.charged)

    def test_prefix_failure_claim_is_permanent_and_prevents_native_preparation(self):
        (self.path / 'tracer-incomplete.txt').write_bytes(b'original')
        with patch('fixed_witness_replay_supervisor.CoordinatorLaunch.for_host') as native:
            for _ in range(2):
                with self.assertRaises(ValueError if _ else FileExistsError):
                    TracerSupervisor.for_host(self.f.ledger, ['fixed'], '/fixed', {}, 999,
                                              self.path, 100.0, self.writers)
            native.assert_not_called()
        self.assertEqual(11, self.writers.tracer.charged)
        self.assertEqual(b'original', (self.path / 'tracer-incomplete.txt').read_bytes())
        self.assertEqual(0, self.writers.coordinator.charged)

    def test_failed_final_write_is_charged_and_not_retried_on_second_finish(self):
        owner = self.owner()
        attempted = []
        def failed(path, data, quota):
            attempted.append(data)
            quota.charge(len(data))
            raise OSError('uncertain write')
        with patch('fixed_witness_replay_tracer_evidence.write_exclusive', side_effect=failed):
            with self.assertRaises(SupervisorError):
                owner.run()
            with self.assertRaises(ValueError):
                owner.evidence.finish(owner)
        self.assertEqual(1, len(attempted))
        self.assertEqual(11 + len(attempted[0]), self.writers.tracer.charged)

    def test_actual_raw_artifact_sink_does_not_retry_failed_write_on_close(self):
        owner = self.owner()
        original_open = Path.open
        calls = []
        def opening(path, *args, **kwargs):
            stream = original_open(path, *args, **kwargs)
            if path.name == 'tracer-lifecycle.json':
                self.assertEqual(0, kwargs.get('buffering'))
                def failed(data):
                    calls.append(data)
                    raise OSError('uncertain raw write')
                stream.write = failed
            return stream
        with patch.object(Path, 'open', new=opening):
            with self.assertRaises(SupervisorError):
                owner.run()
        self.assertEqual(1, len(calls))
        self.assertEqual(11 + len(calls[0]), self.writers.tracer.charged)
        self.assertEqual(b'', (self.path / 'tracer-lifecycle.json').read_bytes())

    def test_factory_prepares_prefix_before_native_wire_failure(self):
        def failed(*args):
            self.assertEqual(b'INCOMPLETE\n', (self.path / 'tracer-incomplete.txt').read_bytes())
            raise OSError('native unavailable')
        with patch('fixed_witness_replay_supervisor.CoordinatorLaunch.for_host', side_effect=failed):
            with self.assertRaises(OSError):
                TracerSupervisor.for_host(self.f.ledger, ['fixed'], '/fixed', {}, 999,
                                          self.path, 100.0, self.writers)
        self.assertFalse((self.path / 'tracer-lifecycle.json').exists())
        self.assertEqual(11, self.writers.tracer.charged)
        self.f.f.system.fork.assert_not_called()

    def test_evidence_cannot_be_attached_to_two_owners_or_finished_by_foreign_owner(self):
        owner = self.owner()
        with self.assertRaises(ValueError):
            owner.evidence.finish(object())
        self.assertFalse(owner.evidence.attempted)
        other = fixture.SupervisorTest()
        other.setUp()
        with self.assertRaises(SupervisorError):
            other.owner(evidence=owner.evidence)
        owner.run()
        self.assertTrue(owner.evidence.attempted)


if __name__ == '__main__':
    unittest.main()
