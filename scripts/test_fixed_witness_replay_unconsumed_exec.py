"""Unconsumed TGID EXEC to original kill/reap; all kernel transports injected."""
import ctypes
import errno
from dataclasses import replace
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

    def test_idle_and_interrupted_wait_poll_only_original_pair(self):
        self.f.transport.wait.side_effect = [InterruptedError(), (0, 0), (0, 0),
                                             (11, 0x4057f), (11, 9)]
        owner = self.f.owner()
        owner.run()
        self.assertEqual([call(11, WAIT_OPTIONS), call(11, WAIT_OPTIONS),
                          call(12, WAIT_OPTIONS), call(11, WAIT_OPTIONS),
                          call(11, WAIT_OPTIONS)], self.f.transport.wait.call_args_list)
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
        self.f.transport.wait.side_effect = [(0, 0), (0, 0)]
        owner = self.f.owner()
        owner.reaper.sleep.side_effect = lambda _: setattr(self.f.f.clock, 'return_value', 100.0)
        with self.assertRaisesRegex(CleanupReapError, 'deadline'):
            owner.run()
        self.assertEqual([call(11, WAIT_OPTIONS), call(12, WAIT_OPTIONS)],
                         self.f.transport.wait.call_args_list)
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


    def pending_failed_exit(self, result=-2, is_error=1):
        self.f.child.syscall_info.return_value = decode_syscall_info(*exit_info(result, is_error))
        self.f.transport.wait.side_effect = [(0, 0), (12, 0x857f), (11, 9), (12, 9)]

    def test_unconsumed_failed_exit_then_original_group_kill_and_both_reaps(self):
        self.pending_failed_exit()
        before, reason = self.ledger.totals, self.launch.stopped
        owner = self.f.owner()
        result = owner.run()
        self.assertEqual([(1, 11), (2, 12)], [(b, tid) for b, tid, _ in result])
        self.assertEqual([call.wait(11, WAIT_OPTIONS), call.wait(12, WAIT_OPTIONS),
                          call.open(11, 0), call.send(70, 9, None, 0),
                          call.wait(11, WAIT_OPTIONS), call.wait(12, WAIT_OPTIONS),
                          call.close(70)], self.f.transport.mock_calls)
        self.assertEqual([call.syscall_info(12)], self.f.child.mock_calls)
        self.assertEqual([], self.f.f.wire.mock_calls)
        self.assertEqual((before.attempts, before.wait_stops + 1, 1),
                         (self.ledger.totals.attempts, self.ledger.totals.wait_stops,
                          self.ledger.totals.live))
        self.assertEqual(reason, self.launch.stopped)
        self.assertEqual({}, self.post._exec_generations)
        self.assertIsNone(self.post._exec_observations)
        self.assertNotIn(2, self.launch.dispatcher._sequence)
        self.assertEqual('reaped', self.ledger.identity(1).terminal)
        self.assertEqual('reaped', self.ledger.identity(2).terminal)
        self.assertEqual(12, self.ledger.identity(2).tid)
        self.assertIsNotNone(owner.reaper.last_inflight_exit[-1])

    def test_unconsumed_execveat_failed_exit(self):
        self.setUp(322)
        self.pending_failed_exit(-38)
        self.f.owner().run()
        self.assertEqual('reaped', self.ledger.identity(2).terminal)

    def test_successful_or_invalid_errno_exit_never_pins(self):
        for result, flag in ((0, 0), (2, 1), (-4096, 1), (-2, 0)):
            with self.subTest(result=result, flag=flag):
                self.setUp()
                self.pending_failed_exit()
                self.f.child.syscall_info.return_value = replace(
                    decode_syscall_info(*exit_info(-2, 1)), result=result, is_error=bool(flag))
                with self.assertRaisesRegex(ValueError, 'failed native exec EXIT'):
                    self.f.owner().run()
                self.assert_no_pin()
                self.assertIsNotNone(self.launch.dispatcher._sequence[2])
                self.f.child.resume_syscall.assert_not_called()

    def test_wrong_phase_or_compat_exit_never_pins(self):
        for info in (decode_syscall_info(*entry(59)),
                     replace(decode_syscall_info(*exit_info(-2, 1)), arch=0x40000003)):
            with self.subTest(info=info):
                self.setUp()
                self.pending_failed_exit()
                self.f.child.syscall_info.return_value = info
                with self.assertRaises(ValueError):
                    self.f.owner().run()
                self.assert_no_pin()
                self.assertIsNotNone(self.launch.dispatcher._sequence[2])

    def test_uncertain_exit_read_retained_once_without_retry(self):
        self.pending_failed_exit()
        self.f.child.syscall_info.side_effect = OSError('uncertain EXIT read')
        owner = self.f.owner()
        with self.assertRaises(OSError):
            owner.run()
        with self.assertRaises(CleanupReapError):
            owner.reaper.bind_inflight_exec()
        self.f.child.syscall_info.assert_called_once_with(12)
        self.assertEqual((self.ledger.identity(2), 0x857f, None),
                         owner.reaper.last_inflight_exit)
        self.assert_no_pin()

    def test_executor_echild_only_returns_to_original_tgid(self):
        self.f.transport.wait.side_effect = [(0, 0), ChildProcessError(errno.ECHILD, 'gone'),
                                             (11, 0x4057f), (11, 9)]
        owner = self.f.owner()
        owner.run()
        self.assertEqual([11, 12, 11, 11],
                         [c.args[0] for c in self.f.transport.wait.call_args_list])
        owner.reaper.sleep.assert_called_once_with(0.01)
        self.f.child.syscall_info.assert_not_called()
        self.assertEqual('exec-replaced', self.ledger.identity(1).terminal)

    def test_other_executor_wait_error_is_not_retried(self):
        self.f.transport.wait.side_effect = [(0, 0), ChildProcessError(errno.EINVAL, 'bad')]
        with self.assertRaises(ChildProcessError):
            self.f.owner().run()
        self.assert_no_pin()
        self.f.child.syscall_info.assert_not_called()
        self.assertEqual(3, self.ledger.totals.live)

    def test_executor_terminal_quarantines_before_failure(self):
        self.f.transport.wait.side_effect = [(0, 0), (12, 9)]
        with self.assertRaisesRegex(CleanupReapError, 'failed exec EXIT'):
            self.f.owner().run()
        self.assertIn(12, self.launch.unsettled_terminal_waits)
        self.assert_no_pin()
        self.f.child.syscall_info.assert_not_called()
        self.assertEqual(3, self.ledger.totals.live)

    def test_wrong_executor_wait_birth_debits_without_read(self):
        before = self.ledger.totals.wait_stops
        self.f.transport.wait.side_effect = [(0, 0), (11, 0x857f)]
        with self.assertRaisesRegex(CleanupReapError, 'birth mismatch'):
            self.f.owner().run()
        self.assertEqual(before + 1, self.ledger.totals.wait_stops)
        self.f.child.syscall_info.assert_not_called()
        self.assert_no_pin()

    def test_exit_read_crossing_deadline_retains_facts_without_settlement(self):
        self.pending_failed_exit()
        def read(tid):
            self.f.f.clock.return_value = 100.0
            return decode_syscall_info(*exit_info(-2, 1))
        self.f.child.syscall_info.side_effect = read
        owner = self.f.owner()
        with self.assertRaisesRegex(CleanupReapError, 'deadline'):
            owner.run()
        self.assertIsNotNone(owner.reaper.last_inflight_exit[-1])
        self.assertIsNotNone(self.launch.dispatcher._sequence[2])
        self.assert_no_pin()

    def test_exit_stop_ceiling_charged_before_read(self):
        self.pending_failed_exit()
        before = self.ledger.totals.wait_stops
        self.ledger._event_limit = before + 1
        with self.assertRaisesRegex(ValueError, 'wait-stop limit'):
            self.f.owner().run()
        self.assertEqual(before + 1, self.ledger.totals.wait_stops)
        self.f.child.syscall_info.assert_not_called()
        self.assert_no_pin()

    def test_owner_or_binding_change_after_exit_read_never_settles(self):
        for change in ('owner', 'binding'):
            with self.subTest(change=change):
                self.setUp()
                self.pending_failed_exit()
                def read(tid):
                    if change == 'owner':
                        self.f.f.system.getpid.return_value = 99
                    else:
                        self.launch.dispatcher._tasks[2] = (12, None)
                    return decode_syscall_info(*exit_info(-2, 1))
                self.f.child.syscall_info.side_effect = read
                with self.assertRaises(ValueError):
                    self.f.owner().run()
                self.assertIsNotNone(self.launch.dispatcher._sequence[2])
                self.assert_no_pin()

    def test_executor_echild_idle_keeps_original_deadline(self):
        self.f.transport.wait.side_effect = [(0, 0), ChildProcessError(errno.ECHILD, 'gone')]
        owner = self.f.owner()
        owner.reaper.sleep.side_effect = lambda _: setattr(self.f.f.clock, 'return_value', 100.0)
        with self.assertRaisesRegex(CleanupReapError, 'deadline'):
            owner.run()
        self.assertEqual(2, self.f.transport.wait.call_count)
        self.assertEqual(3, self.ledger.totals.live)
        self.assert_no_pin()

    def test_actual_supervisor_failed_exit_preserves_original_failure_and_cleanup(self):
        self.supervisor_composition(failed=True)

    def test_actual_supervisor_typed_wires_preserve_original_failure_and_cleanup(self):
        self.supervisor_composition(failed=False)

    def supervisor_composition(self, *, failed):
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
                return (0, 0) if failed else (11, 0x4057f)
            if failed and index == 9:
                child.syscall_info.return_value = decode_syscall_info(*exit_info(-2, 1))
                return (12, 0x857f)
            if index == (10 if failed else 9):
                return (11, 9)
            if failed and index == 11:
                return (12, 9)
            self.fail('unexpected wait')
        f.io.wait.side_effect = wait
        owner = f.owner()
        with self.assertRaises(OSError) as raised:
            owner.run()
        self.assertIs(raised.exception, original)
        self.assertIs(owner.failure, original)
        self.assertIsNone(owner.cleanup_error)
        self.assertFalse(owner.drained)
        self.assertEqual('reaped' if failed else 'exec-replaced', f.ledger.identity(1).terminal)
        self.assertEqual('reaped', f.ledger.identity(2).terminal)
        self.assertEqual((3, 1, 0), (f.ledger.totals.attempts,
                                   f.ledger.totals.live, f.ledger.totals.pending))
        f.io.open.assert_called_once_with(11, 0)
        f.io.send.assert_called_once_with(70, 9, None, 0)
        self.assertEqual([11, 11, 11, 12, 11, 12, 11, 11, 12, 11, 12] if failed
                         else [11, 11, 11, 12, 11, 12, 11, 11, 11],
                         [c.args[0] for c in f.io.wait.call_args_list])
        self.assertEqual(2, child.resume_syscall.call_count)  # newborn, exec ENTRY only
        self.assertFalse(leader_wire._unscoped_mutation)
        self.assertFalse(child_wire._unscoped_mutation)
        self.assertEqual({}, owner.dispatcher._exec_generations)


if __name__ == '__main__':
    unittest.main()
