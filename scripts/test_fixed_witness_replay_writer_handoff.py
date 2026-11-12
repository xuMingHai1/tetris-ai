"""Real grant pipes/files and original supervisor with fake kernel only."""
import json
import os
from pathlib import Path
import tempfile
import time
import unittest
from unittest.mock import Mock, patch

from fixed_witness_replay_writer_handoff import HostWriterHandoff
from fixed_witness_replay_quota import OUTPUT_BYTES
from fixed_witness_replay_writers import REPORT_BYTES, COORDINATOR_BYTES
import test_fixed_witness_replay_probe_sources as source_fixture
import test_fixed_witness_replay_supervisor as supervisor_fixture


def grant():
    return ('FIXED_WRITERS_V1 %d %d %d %d %d %d\n' % (
        os.getppid(), os.getpid(), OUTPUT_BYTES, REPORT_BYTES, REPORT_BYTES,
        COORDINATOR_BYTES)).encode('ascii')


def composed(handoff, *, failure=None):
    """Call the actual factory/lifecycle; replace only source/native transports."""
    f = supervisor_fixture.SupervisorTest()
    f.setUp()
    sources = source_fixture.ProbeSourcesTest()
    sources.setUp()
    prepared = (sources.root, sources.receipt, (2, 3), sources.networks)
    f.ledger._tasks[0].tid = handoff.pid
    f.launch.owner = handoff.pid
    f.f.system.getpid.return_value = handoff.pid
    f.f.system.listdir.return_value = [str(handoff.pid)]

    def launch(ledger, command, cwd, environment, stdio, deadline):
        f.launch.stdio, f.launch.deadline = stdio, deadline
        return f.launch

    with patch('fixed_witness_replay_supervisor.CoordinatorLaunch.for_host', side_effect=launch), \
            patch('fixed_witness_replay_probe_sources.ProcFiles', return_value=sources.files):
        owner = handoff.prepare(f.ledger, ['frozen-coordinator'], '/frozen', {}, 999,
                                prepared_probes=prepared)
    owner.waitpid, owner.sleep = f.io.wait, f.io.sleep
    owner.open_pidfd, owner.send_signal, owner.close = f.io.open, f.io.send, f.io.close
    os.write(owner.output.writers[0], b'charged')
    if failure is not None:
        f.io.wait.side_effect = [(11, 0x137f), failure, (11, 9)]
    return owner, f


def java_fixture(directory):
    """Ordinary CI child: receive Java grant, then use fake kernel only."""
    handoff = HostWriterHandoff.receive(0, Path(directory), time.monotonic() + 20)
    owner, _ = composed(handoff)
    owner.run()
    assert owner.drained and not owner.cleanup_error
    assert handoff._writers.host is None
    assert handoff._writers.coordinator.charged == 7


class WriterHandoffTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.directory = Path(directory.name).resolve()
        self.deadline = time.monotonic() + 20

    def pipe(self, data, *, close=True):
        reader, writer = os.pipe()
        self.addCleanup(os.close, reader)
        os.write(writer, data)
        if close:
            os.close(writer)
        else:
            self.addCleanup(os.close, writer)
        return reader

    def receive(self, data=None):
        return HostWriterHandoff.receive(self.pipe(grant() if data is None else data),
                                         self.directory, self.deadline)

    def test_original_grant_to_actual_source_output_evidence_and_reap(self):
        h = self.receive()
        owner, f = composed(h)
        owner.run()
        self.assertTrue(owner.drained)
        self.assertIsNone(h._writers.host)
        self.assertEqual(7, h._writers.coordinator.charged)
        self.assertEqual(1, f.ledger.totals.live)
        self.assertEqual(self.deadline, owner.launch.deadline)
        self.assertEqual('DRAINED_OBSERVATION', json.loads(
            (self.directory / 'tracer-lifecycle.json').read_bytes())['state'])
        self.assertEqual(sum((self.directory / name).stat().st_size for name in (
            'tracer-writer-claim.txt', 'tracer-incomplete.txt', 'tracer-lifecycle.json')),
            h._writers.tracer.charged)

    def test_original_failure_cleanup_no_refund_or_reissue(self):
        h = self.receive()
        failure = OSError('original wait error')
        owner, f = composed(h, failure=failure)
        with self.assertRaises(OSError) as raised:
            owner.run()
        self.assertIs(failure, raised.exception)
        self.assertIsNone(owner.cleanup_error)
        self.assertEqual(1, f.ledger.totals.live)
        self.assertEqual(7, h._writers.coordinator.charged)
        with self.assertRaisesRegex(ValueError, 'consumed'):
            h.prepare(f.ledger, [], '', {}, 999, prepared_probes=None)

    def test_wrong_parent_receiver_and_each_limit_rejected(self):
        fields = grant().split()
        for i in range(len(fields)):
            with self.subTest(field=i):
                changed = list(fields)
                changed[i] = b'0'
                with self.assertRaisesRegex(ValueError, 'grant'):
                    self.receive(b' '.join(changed) + b'\n')
        self.assertEqual([], list(self.directory.iterdir()))

    def test_empty_truncated_extra_and_oversize_rejected(self):
        for data in (b'', grant()[:-1], grant() + b'\n', b'x' * 129):
            with self.subTest(data=data), self.assertRaises(ValueError):
                self.receive(data)

    def test_eof_required_with_original_deadline(self):
        clock = Mock(side_effect=[1., 1., 1., 2.])
        with self.assertRaisesRegex(ValueError, 'deadline'):
            HostWriterHandoff.receive(self.pipe(grant(), close=False), self.directory, 2., clock=clock)
        self.assertEqual([], list(self.directory.iterdir()))

    def test_regular_file_is_not_inherited_pipe(self):
        path = self.directory / 'input'
        path.write_bytes(grant())
        with path.open('rb') as stream, self.assertRaisesRegex(ValueError, 'pipe'):
            HostWriterHandoff.receive(stream.fileno(), self.directory, self.deadline)

    def test_duplicate_claim_never_overwrites(self):
        self.receive()
        with self.assertRaises(FileExistsError):
            self.receive()
        self.assertEqual(grant(), (self.directory / 'tracer-writer-claim.txt').read_bytes())

    def test_invalid_deadline_before_read(self):
        for deadline in (True, float('nan'), float('inf'), 0., time.monotonic() + 901):
            with self.subTest(deadline=deadline), self.assertRaises(ValueError):
                HostWriterHandoff.receive(self.pipe(grant()), self.directory, deadline)

    def test_no_alternate_constructor(self):
        with self.assertRaises(ValueError):
            HostWriterHandoff()

    def test_preparation_error_permanently_consumes_grant(self):
        h = self.receive()
        with patch('fixed_witness_replay_supervisor.TracerSupervisor.for_host',
                   side_effect=OSError('prepare failed')) as factory:
            ledger = Mock()
            with self.assertRaises(OSError):
                h.prepare(ledger, [], '', {}, 999, prepared_probes=(1, 2, 3, 4))
            with self.assertRaisesRegex(ValueError, 'consumed'):
                h.prepare(ledger, [], '', {}, 999, prepared_probes=(1, 2, 3, 4))
            factory.assert_called_once()

    def test_missing_prepared_sources_never_load_native(self):
        h = self.receive()
        with patch('fixed_witness_replay_supervisor.TracerSupervisor.for_host') as factory:
            with self.assertRaisesRegex(ValueError, 'sources'):
                h.prepare(Mock(), [], '', {}, 999, prepared_probes=None)
            factory.assert_not_called()

    def test_receiver_drift_blocks_native_preparation(self):
        h = self.receive()
        with patch('fixed_witness_replay_writer_handoff.os.getppid', return_value=0), \
                patch('fixed_witness_replay_supervisor.TracerSupervisor.for_host') as factory:
            with self.assertRaisesRegex(ValueError, 'direct tracer'):
                h.prepare(Mock(), [], '', {}, 999, prepared_probes=(1, 2, 3, 4))
            factory.assert_not_called()


if __name__ == '__main__':
    unittest.main()
