"""Unconsumed TGID EXEC to original kill/reap; all kernel transports injected."""
import ctypes
import struct
import unittest
from unittest.mock import Mock, call

from fixed_witness_replay_cleanup_reap import CleanupReapError
from fixed_witness_replay_ptrace import PtraceAccess, SignalInfo, decode_syscall_info
from fixed_witness_replay_wait_owner import BoundedWaitOwner, WAIT_OPTIONS
from test_fixed_witness_replay_ptrace import entry, exit_info
import test_fixed_witness_replay_supervisor as supervisor_fixture
from test_fixed_witness_replay_filter_exec_chain import wire_kernel
import test_fixed_witness_replay_thread_cleanup as fixture


class UnconsumedExecTest(unittest.TestCase):
    def setUp(self, number=59, resume_error=None):
        self.f = fixture.ThreadCleanupTest()
        self.f.setUp()
        self.launch, self.ledger = self.f.launch, self.f.ledger
        self.post = self.f.f.owner
        self.f.child.syscall_info.return_value = decode_syscall_info(*entry(number))
        self.f.child.resume_syscall.side_effect = resume_error
        self.original = OSError('original failure before next EXEC wait')
        self.normal_wait = Mock(side_effect=[(0, 0), (12, 0x857f), self.original])
        self.normal = BoundedWaitOwner(self.post, waitpid=self.normal_wait)
        with self.assertRaises(OSError):
            self.normal.run()
        self.f.child.reset_mock()
        self.f.f.wire.reset_mock()
        self.f.f.wire.event_tid.return_value = 12
        self.f.transport.wait.side_effect = [(11, 0x4057f), (11, 9)]

    def assert_no_pin(self):
        self.f.transport.open.assert_not_called()
        self.f.transport.send.assert_not_called()

    def test_original_completed_entry_tgid_event_pin_kill_and_real_reap(self):
        before = self.ledger.totals
        owner = self.f.owner()
        result = owner.run()
        self.assertEqual([(2, 11)], [(b, tid) for b, tid, _ in result])
        self.assertEqual([call.wait(11, WAIT_OPTIONS), call.open(11, 0),
                          call.send(70, 9, None, 0), call.wait(11, WAIT_OPTIONS),
                          call.close(70)], self.f.transport.mock_calls)
        self.f.f.wire.event_tid.assert_called_once_with(11)
        self.assertEqual([], self.f.child.mock_calls)
        self.assertTrue(self.normal.last_wait.completed)
        self.assertTrue(self.normal.last_wait.adopted)
        self.assertEqual('exec-replaced', self.ledger.identity(1).terminal)
        self.assertEqual('reaped', self.ledger.identity(2).terminal)
        self.assertEqual((before.attempts, 1, before.wait_stops + 1, before.stopped),
                         (self.ledger.totals.attempts, self.ledger.totals.live,
                          self.ledger.totals.wait_stops, self.ledger.totals.stopped))
        self.assertEqual(self.normal.stopped, self.launch.stopped)
        self.assertEqual({}, self.post._exec_generations)
        self.assertIsNone(self.post._exec_observations)

    def test_execveat_reuses_exact_original_entry(self):
        self.setUp(322)
        self.f.owner().run()
        self.assertEqual('reaped', self.ledger.identity(2).terminal)

    def test_idle_and_interrupted_wait_only_poll_original_tgid(self):
        self.f.transport.wait.side_effect = [InterruptedError(), (0, 0),
                                             (11, 0x4057f), (11, 9)]
        owner = self.f.owner()
        owner.run()
        self.assertEqual([call(11, WAIT_OPTIONS)] * 4,
                         self.f.transport.wait.call_args_list)
        owner.reaper.sleep.assert_called_once_with(0.01)

    def test_wrong_old_tid_never_installs_or_pins(self):
        for tid in (True, 0, 11, 13, 2**31):
            with self.subTest(tid=tid):
                self.setUp()
                self.f.f.wire.event_tid.return_value = tid
                with self.assertRaisesRegex(CleanupReapError, 'old TID'):
                    self.f.owner().run()
                self.assert_no_pin()
                self.assertEqual(3, self.ledger.totals.live)
                self.assertEqual(12, self.ledger.identity(2).tid)

    def test_nonexec_stop_or_terminal_is_not_a_migration(self):
        for raw in (0x857f, 0x137f, 0x3057f, 9):
            with self.subTest(raw=raw):
                self.setUp()
                self.f.transport.wait.side_effect = [(11, raw)]
                before = self.ledger.totals.wait_stops
                with self.assertRaisesRegex(CleanupReapError, 'EXEC event'):
                    self.f.owner().run()
                self.assert_no_pin()
                self.f.f.wire.event_tid.assert_not_called()
                self.assertEqual(before + (raw != 9), self.ledger.totals.wait_stops)
                self.assertEqual(raw == 9, 11 in self.launch.unsettled_terminal_waits)

    def test_wrong_wait_target_stop_is_debited_before_rejection(self):
        before = self.ledger.totals.wait_stops
        self.f.transport.wait.side_effect = [(12, 0x4057f)]
        with self.assertRaisesRegex(CleanupReapError, 'birth mismatch'):
            self.f.owner().run()
        self.assertEqual(before + 1, self.ledger.totals.wait_stops)
        self.assert_no_pin()
        self.f.f.wire.event_tid.assert_not_called()

    def test_malformed_wait_never_reads_or_pins(self):
        for result in (None, [11, 0x4057f], (11,), (True, 0x4057f), (0, 1)):
            with self.subTest(result=result):
                self.setUp()
                self.f.transport.wait.side_effect = [result]
                with self.assertRaises(ValueError):
                    self.f.owner().run()
                self.assert_no_pin()
                self.f.f.wire.event_tid.assert_not_called()

    def test_shared_stop_ceiling_retains_consumed_wait_without_event_read(self):
        self.ledger._event_limit = self.ledger.totals.wait_stops + 1
        with self.assertRaisesRegex(ValueError, 'wait-stop limit'):
            self.f.owner().run()
        self.assert_no_pin()
        self.f.f.wire.event_tid.assert_not_called()
        self.assertEqual(3, self.ledger.totals.live)
        self.assertEqual(self.ledger._event_limit, self.ledger.totals.wait_stops)

    def test_uncertain_event_read_is_once_only(self):
        self.f.f.wire.event_tid.side_effect = OSError('uncertain read')
        owner = self.f.owner()
        with self.assertRaises(OSError):
            owner.run()
        with self.assertRaises(CleanupReapError):
            owner.run()
        with self.assertRaises(CleanupReapError):
            owner.reaper.bind_inflight_exec()
        self.f.f.wire.event_tid.assert_called_once_with(11)
        self.assertEqual((11, 0x4057f), owner.reaper.last_wait[1])
        self.assertIsNone(owner.reaper.last_exec_event[-1])
        self.assert_no_pin()

    def test_read_crossing_deadline_retains_value_without_identity_change(self):
        def read(tid):
            self.f.f.clock.return_value = 100.0
            return 12
        self.f.f.wire.event_tid.side_effect = read
        owner = self.f.owner()
        with self.assertRaisesRegex(CleanupReapError, 'deadline'):
            owner.run()
        self.assertEqual(12, owner.reaper.last_exec_event[-1])
        self.assertEqual(12, self.ledger.identity(2).tid)
        self.assert_no_pin()

    def test_wait_crossing_deadline_charges_stop_before_failure(self):
        before = self.ledger.totals.wait_stops
        def wait(*args):
            self.f.f.clock.return_value = 100.0
            return (11, 0x4057f)
        self.f.transport.wait.side_effect = wait
        with self.assertRaisesRegex(CleanupReapError, 'deadline'):
            self.f.owner().run()
        self.assertEqual(before + 1, self.ledger.totals.wait_stops)
        self.f.f.wire.event_tid.assert_not_called()
        self.assert_no_pin()

    def test_idle_wait_keeps_original_deadline(self):
        self.f.transport.wait.side_effect = [(0, 0)]
        owner = self.f.owner()
        owner.reaper.sleep.side_effect = lambda _: setattr(self.f.f.clock, 'return_value', 100.0)
        with self.assertRaisesRegex(CleanupReapError, 'deadline'):
            owner.run()
        self.f.transport.wait.assert_called_once_with(11, WAIT_OPTIONS)
        self.assert_no_pin()

    def test_echild_is_not_migration_or_reap(self):
        self.f.transport.wait.side_effect = ChildProcessError('no child')
        with self.assertRaises(ChildProcessError):
            self.f.owner().run()
        self.assertEqual(3, self.ledger.totals.live)
        self.assert_no_pin()

    def test_owner_loss_before_wait_rejects_without_acquisition(self):
        owner = self.f.owner()
        self.f.f.system.getpid.return_value = 99
        with self.assertRaisesRegex(CleanupReapError, 'owner'):
            owner.run()
        self.f.transport.wait.assert_not_called()
        self.assert_no_pin()

    def test_owner_loss_after_event_read_rejects_before_install(self):
        def read(tid):
            self.f.f.system.getpid.return_value = 99
            return 12
        self.f.f.wire.event_tid.side_effect = read
        with self.assertRaisesRegex(CleanupReapError, 'owner'):
            self.f.owner().run()
        self.assertEqual(12, self.ledger.identity(2).tid)
        self.assert_no_pin()

    def test_changed_original_executor_rejects_before_wait(self):
        self.ledger._tasks[2].tid = 13
        with self.assertRaises(ValueError):
            self.f.owner().run()
        self.f.transport.wait.assert_not_called()
        self.assert_no_pin()

    def test_group_pending_creation_blocks_before_wait(self):
        # In-flight creation is unrelated to this completed exec entry but
        # remains an unresolved group obligation; never infer its outcome.
        self.ledger.reserve(1, 'fork')
        with self.assertRaisesRegex(ValueError, 'settled original'):
            self.f.owner().run()
        self.f.transport.wait.assert_not_called()
        self.assert_no_pin()

    def test_unreaped_sibling_blocks_before_wait(self):
        ticket = self.ledger.reserve(1, 'clone', thread=True)
        self.ledger.created(ticket, 13)
        self.ledger.finish(ticket, 13)
        with self.assertRaisesRegex(ValueError, 'settled original'):
            self.f.owner().run()
        self.f.transport.wait.assert_not_called()
        self.assert_no_pin()

    def test_second_exec_entry_in_leader_blocks_before_wait(self):
        self.launch.dispatcher._sequence[1] = decode_syscall_info(*entry(59))
        with self.assertRaisesRegex(ValueError, 'thread/exec'):
            self.f.owner().run()
        self.f.transport.wait.assert_not_called()
        self.assert_no_pin()

    def test_uncertain_entry_resume_is_not_completed_provenance(self):
        self.setUp(resume_error=OSError('resume uncertain'))
        with self.assertRaisesRegex(ValueError, 'thread/exec'):
            self.f.owner().run()
        self.assertFalse(self.normal.last_wait.completed)
        self.f.transport.wait.assert_not_called()
        self.assert_no_pin()

    def test_foreign_wait_owner_cannot_supply_completed_entry(self):
        self.normal.dispatcher = Mock()
        with self.assertRaisesRegex(CleanupReapError, 'owner or completed'):
            self.f.owner().run()
        self.f.transport.wait.assert_not_called()
        self.assert_no_pin()

    def test_quarantined_terminal_group_is_not_polled_or_pinned(self):
        self.launch.retain_terminal_wait(11, 9)
        with self.assertRaisesRegex(CleanupReapError, 'quarantined'):
            self.f.owner().run()
        self.f.transport.wait.assert_not_called()
        self.assert_no_pin()

    def test_pin_failure_keeps_installed_executor_and_original_failure(self):
        self.f.transport.open.side_effect = OSError('pin failed')
        with self.assertRaises(OSError):
            self.f.owner().run()
        self.assertEqual(11, self.ledger.identity(2).tid)
        self.assertEqual('exec-replaced', self.ledger.identity(1).terminal)
        self.assertEqual(2, self.ledger.totals.live)
        self.assertEqual(self.normal.stopped, self.launch.stopped)
        self.f.transport.send.assert_not_called()
        self.f.transport.wait.assert_called_once_with(11, WAIT_OPTIONS)


    def test_actual_supervisor_typed_wires_preserve_original_failure_and_cleanup(self):
        f = supervisor_fixture.SupervisorTest()
        f.setUp()
        leader, child = f.f.access, Mock()
        raw, size = entry(56)
        raw = bytearray(raw)
        struct.pack_into('<Q', raw, 32, 0x10000 | 0x800 | 0x100)
        leader_kernel = wire_kernel(leader)
        def kernel(request, tid, address, data):
            if request == 0x420e and f.io.wait.call_count == 2:
                ctypes.memmove(data, bytes(raw), len(raw))
                return size
            return leader_kernel(request, tid, address, data)
        leader_wire = PtraceAccess(kernel)
        child_wire = PtraceAccess(wire_kernel(child))
        f.launch.access = leader_wire
        f.launch.access_factory = lambda: child_wire
        child.signal_info.return_value = SignalInfo(19, 0, 0, 0, 0)
        original = OSError('original pre-EXEC wait failure')
        def wait(tid, options):
            index = f.io.wait.call_count
            if index == 1:
                return (11, 0x137f)
            if index == 2:
                return (11, 0x857f)
            if index == 3:
                leader.event_tid.return_value = 12
                return (11, 0x3057f)
            if index == 4:
                return (12, 0x137f)
            if index == 5:
                leader.syscall_info.return_value = decode_syscall_info(*exit_info(12, 0))
                return (11, 0x857f)
            if index == 6:
                child.syscall_info.return_value = decode_syscall_info(*entry(59))
                return (12, 0x857f)
            if index == 7:
                raise original
            if index == 8:
                return (11, 0x4057f)
            if index == 9:
                return (11, 9)
            self.fail('unexpected wait')
        f.io.wait.side_effect = wait
        owner = f.owner()
        with self.assertRaises(OSError) as raised:
            owner.run()
        self.assertIs(raised.exception, original)
        self.assertIs(owner.failure, original)
        self.assertIsNone(owner.cleanup_error)
        self.assertFalse(owner.drained)
        self.assertEqual('exec-replaced', f.ledger.identity(1).terminal)
        self.assertEqual('reaped', f.ledger.identity(2).terminal)
        self.assertEqual((3, 1, 0), (f.ledger.totals.attempts,
                                   f.ledger.totals.live, f.ledger.totals.pending))
        f.io.open.assert_called_once_with(11, 0)
        f.io.send.assert_called_once_with(70, 9, None, 0)
        self.assertEqual([11, 11, 11, 12, 11, 12, 11, 11, 11],
                         [c.args[0] for c in f.io.wait.call_args_list])
        self.assertEqual(2, child.resume_syscall.call_count)  # newborn, exec ENTRY only
        self.assertFalse(leader_wire._unscoped_mutation)
        self.assertFalse(child_wire._unscoped_mutation)
        self.assertEqual({}, owner.dispatcher._exec_generations)


if __name__ == '__main__':
    unittest.main()
