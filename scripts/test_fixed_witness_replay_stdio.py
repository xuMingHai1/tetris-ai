"""Real temporary byte pipes/files, fake-only fork/wait/ptrace/pidfd transports."""
import io
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch

from fixed_witness_replay_quota import ByteQuota, OUTPUT_BYTES, write_exclusive
from fixed_witness_replay_stdio import CoordinatorOutput, CoordinatorOutputError, CHUNK_BYTES
from fixed_witness_replay_supervisor import TracerSupervisor, SupervisorError
from fixed_witness_replay_writers import reserve_writers, COORDINATOR_BYTES, REPORT_BYTES
import test_fixed_witness_replay_supervisor as fixture


class ChargedStdioTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.path = Path(self.directory.name)
        self.root = ByteQuota()
        self.writers = reserve_writers(self.root)
        self.output = CoordinatorOutput(self.writers, self.path)
        self.addCleanup(self.output.close)
        self.f = fixture.SupervisorTest()
        self.f.setUp()
        self.launch = self.f.launch
        # stdin must be distinct even on Windows, where pipe FDs may use 7.
        self.launch.stdio = (999, *self.output.writers)

    def owner(self):
        return self.f.owner(output=self.output)

    def test_actual_supervisor_retains_both_streams_from_same_root(self):
        os.write(self.output.writers[0], b'out\x00\xff')
        os.write(self.output.writers[1], b'err\n')
        owner = self.owner()
        result = owner.run()
        self.assertEqual(b'out\x00\xff', (self.path / 'coordinator-stdout.log').read_bytes())
        self.assertEqual(b'err\n', (self.path / 'coordinator-stderr.log').read_bytes())
        self.assertTrue(self.output.drained)
        self.assertTrue(self.output.closed)
        self.assertTrue(owner.drained)
        self.assertEqual(9, self.writers.coordinator.charged)
        self.assertEqual(OUTPUT_BYTES, self.root.charged)
        self.assertEqual((REPORT_BYTES, REPORT_BYTES),
                         (self.writers.tracer.remaining, self.writers.host.remaining))
        self.assertEqual((2, 1, 0, 1),
                         (result.attempts, result.live, result.pending, result.wait_stops))

    def test_artifact_and_stdout_compete_for_original_coordinator_partition(self):
        self.writers.coordinator.charge(COORDINATOR_BYTES - 8)
        write_exclusive(self.path / 'artifact', b'abc', self.writers.coordinator)
        os.write(self.output.writers[0], b'12345')
        self.owner().run()
        self.assertEqual(0, self.writers.coordinator.remaining)
        self.assertEqual(b'12345', (self.path / 'coordinator-stdout.log').read_bytes())

    def test_overflow_after_handshake_kills_and_reaps_without_consuming_normal_wait(self):
        self.writers.coordinator.charge(COORDINATOR_BYTES - 3)
        owner = self.owner()
        # Fake child retains a writer after parent's fork-end closure.
        child = os.dup(self.output.writers[0])
        remaining = [child]
        self.addCleanup(lambda: [os.close(fd) for fd in remaining])
        def wait_with_child(tid, options):
            if self.f.io.wait.call_count == 1:
                os.write(child, b'abcdef')
                return (11, 0x137f)
            os.close(child)
            remaining.clear()
            return (11, 9)
        self.f.io.wait.side_effect = wait_with_child
        with self.assertRaises(CoordinatorOutputError):
            owner.run()
        self.assertEqual(2, self.f.io.wait.call_count)
        self.assertEqual((1, 0), (self.f.ledger.totals.live, self.f.ledger.totals.pending))
        self.assertEqual([1], owner.cleanup.signalled)
        self.assertIsNone(owner.cleanup_error)
        self.assertEqual(b'abc', (self.path / 'coordinator-stdout.log').read_bytes())
        self.assertEqual(0, self.writers.coordinator.remaining)
        self.assertEqual(REPORT_BYTES, self.writers.host.remaining)

    def test_output_before_handshake_does_not_invent_initial_stop_or_reap(self):
        self.writers.coordinator.charge(COORDINATOR_BYTES - 1)
        os.write(self.output.writers[0], b'xx')
        owner = self.owner()
        with self.assertRaises(CoordinatorOutputError):
            owner.run()
        self.f.io.wait.assert_not_called()
        self.f.f.access.resume_syscall.assert_not_called()
        self.assertEqual(0, self.f.ledger.totals.wait_stops)
        self.assertEqual(2, self.f.ledger.totals.live)
        self.assertIsNotNone(owner.cleanup_error)  # no authenticated startup pin in this fake

    def test_failed_fork_closes_writer_ends_and_keeps_original_exception(self):
        failure = OSError('fork failed')
        self.f.f.system.fork.side_effect = failure
        owner = self.owner()
        with self.assertRaises(OSError) as raised:
            owner.run()
        self.assertIs(failure, raised.exception)
        self.assertTrue(self.output.drained)
        self.assertTrue(self.output.closed)
        self.assertIsNone(owner.cleanup)
        self.assertIsNone(owner.cleanup_error)
        self.f.io.wait.assert_not_called()

    def test_failed_sink_keeps_charge_and_cleanup_never_retries_write(self):
        self.output.sinks[0].close()
        sink = Mock()
        failure = OSError('disk failure')
        sink.write.side_effect = failure
        self.output.sinks[0] = sink
        os.write(self.output.writers[0], b'charged')
        owner = self.owner()
        with self.assertRaises(CoordinatorOutputError):
            owner.run()
        self.assertIs(failure, self.output.failure)
        sink.write.assert_called_once_with(b'charged')
        self.assertEqual(7, self.writers.coordinator.charged)
        self.assertFalse(owner.drained)

    def test_poll_quantum_is_bounded_and_cleanup_discards_after_failure(self):
        self.output.claim(self.launch)
        self.output.release_parent_writers()
        self.writers.coordinator.charge(COORDINATOR_BYTES - 1)
        with patch('fixed_witness_replay_stdio.os.read', return_value=b'abc') as read:
            with self.assertRaises(CoordinatorOutputError):
                self.output.poll()
            self.output.poll(cleanup=True)
        self.assertEqual(3, read.call_count)  # first stream fails; cleanup reads both
        self.assertTrue(all(c.args[1] == CHUNK_BYTES for c in read.call_args_list))
        self.assertEqual(1, self.output.budget.written)
        self.assertEqual(0, self.writers.coordinator.remaining)

    def test_short_write_keeps_entire_charged_prefix(self):
        self.output.sinks[0].close()
        sink = Mock()
        sink.write.return_value = 2
        self.output.sinks[0] = sink
        os.write(self.output.writers[0], b'1234')
        with self.assertRaises(CoordinatorOutputError):
            self.owner().run()
        self.assertEqual(4, self.writers.coordinator.charged)
        sink.write.assert_called_once_with(b'1234')

    def test_failed_raw_write_is_not_retried_by_close(self):
        # Exercise the actual sink type: BufferedWriter would retain this data
        # and try the raw write again during close after its flush failed.
        sink = self.output.sinks[0]
        self.assertIsInstance(sink, io.FileIO)
        os.write(self.output.writers[0], b'once-only')
        attempted = []
        def fail_target(data):
            attempted.append(bytes(data))
            raise OSError('raw write failure')
        with patch.object(sink, 'write', fail_target):
            with self.assertRaises(CoordinatorOutputError):
                self.owner().run()
        self.assertEqual([b'once-only'], attempted)
        self.assertTrue(sink.closed)
        self.assertEqual(9, self.writers.coordinator.charged)

    def test_output_claim_rejects_wrong_launch_before_fork(self):
        self.launch.stdio = (999, 888, 777)
        with self.assertRaises(CoordinatorOutputError):
            self.owner()
        self.f.f.system.fork.assert_not_called()
        self.assertFalse(getattr(self.launch, '_supervisor_claimed', False))

    def test_one_partition_cannot_create_another_output_writer(self):
        with self.assertRaises(CoordinatorOutputError):
            CoordinatorOutput(self.writers, self.path)
        self.assertEqual(0, self.writers.coordinator.charged)

    def test_existing_output_is_not_overwritten_and_partition_not_reusable(self):
        directory = self.path / 'collision'
        directory.mkdir()
        old = directory / 'coordinator-stderr.log'
        old.write_bytes(b'original')
        writers = reserve_writers(ByteQuota())
        with self.assertRaises(FileExistsError):
            CoordinatorOutput(writers, directory)
        self.assertEqual(b'original', old.read_bytes())
        with self.assertRaises(CoordinatorOutputError):
            CoordinatorOutput(writers, directory)

    def test_invalid_output_type_does_not_claim_or_fork(self):
        with self.assertRaises(SupervisorError):
            self.f.owner(output=object())
        self.f.f.system.fork.assert_not_called()

    def test_late_output_after_actual_terminal_is_drained_before_success(self):
        child = os.dup(self.output.writers[1])
        def wait(tid, options):
            if self.f.io.wait.call_count == 1:
                return (11, 0x137f)
            os.write(child, b'final')
            os.close(child)
            return (11, 0)
        self.f.io.wait.side_effect = wait
        owner = self.owner()
        owner.run()
        self.assertTrue(owner.drained)
        self.assertEqual(5, self.writers.coordinator.charged)
        self.assertEqual(b'final', (self.path / 'coordinator-stderr.log').read_bytes())

    def test_pipe_eof_cannot_replace_actual_terminal_wait(self):
        failure = ChildProcessError('ECHILD')
        self.f.io.wait.side_effect = [(11, 0x137f), failure, failure]
        owner = self.owner()
        with self.assertRaises(ChildProcessError) as raised:
            owner.run()
        self.assertIs(failure, raised.exception)
        self.assertFalse(owner.drained)
        self.assertEqual(2, self.f.ledger.totals.live)
        self.assertIsNotNone(owner.cleanup_error)

    def test_open_pipe_after_reap_uses_original_deadline_and_no_new_wait(self):
        child = os.dup(self.output.writers[1])
        self.addCleanup(os.close, child)
        self.f.io.sleep.side_effect = lambda delay: self.f.f.clock.configure_mock(return_value=100.0)
        owner = self.owner()
        with self.assertRaisesRegex(ValueError, 'deadline'):
            owner.run()
        self.assertEqual(2, self.f.io.wait.call_count)
        self.assertEqual(1, self.f.ledger.totals.live)
        self.assertFalse(owner.drained)
        self.assertEqual(100.0, self.launch.deadline)

    def test_host_factory_binds_actual_pipe_numbers(self):
        self.output.close()
        directory = self.path / 'factory'
        directory.mkdir()
        writers = reserve_writers(ByteQuota())
        def prepared(ledger, command, cwd, env, stdio, deadline):
            self.assertEqual(999, stdio[0])
            self.launch.stdio = stdio
            return self.launch
        with patch('fixed_witness_replay_supervisor.CoordinatorLaunch.for_host', side_effect=prepared):
            owner = TracerSupervisor.for_host(
                self.f.ledger, ['fixed-command'], '/fixed', {}, 999, directory, 100.0, writers)
        self.addCleanup(owner.output.close)
        self.assertEqual(tuple(owner.output.writers), owner.launch.stdio[1:])
        self.assertIs(writers.coordinator, owner.output.quota)
        self.assertFalse(owner.started)
        self.f.f.system.fork.assert_not_called()

    def test_exhausted_partition_prevents_fork_even_without_output(self):
        self.writers.coordinator.charge(COORDINATOR_BYTES)
        owner = self.owner()
        with self.assertRaisesRegex(CoordinatorOutputError, 'before launch'):
            owner.run()
        self.f.f.system.fork.assert_not_called()
        self.f.io.wait.assert_not_called()
        self.assertEqual(1, self.f.ledger.totals.attempts)
        self.assertEqual(OUTPUT_BYTES, self.root.charged)

    def test_host_factory_closes_partial_transport_on_native_prepare_failure(self):
        directory = self.path / 'failed-factory'
        directory.mkdir()
        writers = reserve_writers(ByteQuota())
        failure = OSError('native wire unavailable')
        seen = []
        def failed(*args):
            seen.extend(args[4][1:])
            raise failure
        with patch('fixed_witness_replay_supervisor.CoordinatorLaunch.for_host', side_effect=failed):
            with self.assertRaises(OSError) as raised:
                TracerSupervisor.for_host(
                    self.f.ledger, ['fixed-command'], '/fixed', {}, 999, directory, 100.0, writers)
        self.assertIs(failure, raised.exception)
        for fd in seen:
            with self.assertRaises(OSError):
                os.fstat(fd)
        self.f.f.system.fork.assert_not_called()
        with self.assertRaises(CoordinatorOutputError):
            CoordinatorOutput(writers, directory)

    def test_close_error_after_real_reap_prevents_success(self):
        original_close = os.close
        def close(fd):
            original_close(fd)
            if fd == self.output.readers[0]:
                raise OSError('uncertain close')
        owner = self.owner()
        with patch('fixed_witness_replay_stdio.os.close', side_effect=close):
            with self.assertRaisesRegex(CoordinatorOutputError, 'close failed'):
                owner.run()
        self.assertFalse(owner.drained)
        self.assertIsNotNone(self.launch.stopped)
        self.assertEqual(1, self.f.ledger.totals.live)
        self.assertEqual(1, len(self.output.close_errors))

    def test_two_exec_exact_entry_real_shaped_reap_fixture_uses_charged_output(self):
        # Reuse the complete existing typed-wire fixture rather than injecting
        # private ABI ENTRY or replacing any of the original dispatcher owners.
        original = self.f.owner
        os.write(self.output.writers[0], b'chain')
        self.f.owner = lambda **kwargs: original(output=self.output, **kwargs)
        self.f.test_frozen_two_exec_entry_reap_path_through_single_run()
        self.assertEqual(5, self.writers.coordinator.charged)
        self.assertTrue(self.output.closed)


if __name__ == '__main__':
    unittest.main()
