"""Consumed failed exec EXIT to original group cleanup; fake kernel only."""
from dataclasses import replace
import ctypes
import struct
import unittest
from unittest.mock import Mock, call

from fixed_witness_replay_cleanup_reap import CleanupReapError
from fixed_witness_replay_ptrace import decode_syscall_info
from fixed_witness_replay_wait_owner import BoundedWaitOwner, WAIT_OPTIONS
from test_fixed_witness_replay_ptrace import entry, exit_info
import test_fixed_witness_replay_thread_cleanup as fixture
import test_fixed_witness_replay_supervisor as supervisor_fixture
from test_fixed_witness_replay_filter_exec_chain import wire_kernel
from fixed_witness_replay_ptrace import SignalInfo, PtraceAccess


class FailedExecExitTest(unittest.TestCase):
    def setUp(self, number=59, result=-2, is_error=1):
        self.f = fixture.ThreadCleanupTest()
        self.f.setUp()
        self.launch, self.ledger = self.f.launch, self.f.ledger
        self.post = self.f.f.owner
        self.f.child.syscall_info.return_value = decode_syscall_info(*entry(number))
        self.post.accept(12, 0x857f)
        self.original = 'original dispatch failure after reading EXIT'
        def read(tid):
            self.launch.dispatcher.stopped = self.original
            return decode_syscall_info(*exit_info(result, is_error))
        self.f.child.syscall_info.side_effect = read
        self.normal = BoundedWaitOwner(self.post, waitpid=Mock(side_effect=[(0, 0), (12, 0x857f)]))
        with self.assertRaisesRegex(ValueError, 'creation dispatcher stopped'):
            self.normal.run()
        self.f.child.reset_mock()
        self.f.f.wire.reset_mock()

    def no_acquisition(self):
        self.f.no_acquisition()
        self.assertEqual([], self.f.child.mock_calls)
        self.assertEqual([], self.f.f.wire.mock_calls)

    def test_retained_exit_then_one_group_kill_and_each_birth_reap(self):
        before = self.ledger.totals
        reason = self.launch.stopped
        owner = self.f.owner()
        self.assertEqual([(1, 11), (2, 12)], [(b, tid) for b, tid, _ in owner.run()])
        self.assertIs(owner.reaper.adopted_failed_exec_exit, self.normal.last_wait)
        self.assertTrue(self.normal.last_wait.adopted)
        self.assertFalse(self.normal.last_wait.resume_attempted)
        self.assertEqual([call.open(11, 0), call.send(70, 9, None, 0),
                          call.wait(11, WAIT_OPTIONS), call.wait(12, WAIT_OPTIONS),
                          call.close(70)], self.f.transport.mock_calls)
        self.assertEqual((before.attempts, before.wait_stops, 1),
                         (self.ledger.totals.attempts, self.ledger.totals.wait_stops,
                          self.ledger.totals.live))
        self.assertEqual(reason, self.launch.stopped)
        self.assertEqual(self.original, self.launch.dispatcher.stopped)
        self.assertEqual({}, self.post._exec_generations)
        self.assertEqual([], self.f.child.mock_calls)
        self.assertEqual([], self.f.f.wire.mock_calls)

    def test_execveat_failed_exit_uses_same_path(self):
        self.setUp(322, -38)
        self.f.owner().run()
        self.assertEqual('reaped', self.ledger.identity(2).terminal)

    def test_nonfailure_result_cannot_clear_original_entry(self):
        self.setUp(result=0, is_error=0)
        with self.assertRaisesRegex(ValueError, 'failed native exec EXIT'):
            self.f.owner().run()
        self.assertIsNotNone(self.launch.dispatcher._sequence[2])
        self.no_acquisition()

    def test_compat_exit_is_not_native_failure(self):
        receipt = self.normal.last_wait
        receipt.facts = replace(receipt.facts, syscall=replace(receipt.facts.syscall, arch=0x40000003))
        with self.assertRaisesRegex(ValueError, 'native syscall stop'):
            self.f.owner().run()
        self.no_acquisition()

    def test_uncertain_resume_or_foreign_owner_never_pins(self):
        for change in ('resume_attempted', 'adopted', 'owner', 'tid', 'facts_tid'):
            with self.subTest(change=change):
                self.setUp()
                receipt = self.normal.last_wait
                if change == 'owner':
                    self.normal.dispatcher = Mock()
                elif change == 'tid':
                    receipt.tid = 11
                elif change == 'facts_tid':
                    receipt.facts = replace(receipt.facts, tid=11)
                else:
                    setattr(receipt, change, True)
                with self.assertRaisesRegex(CleanupReapError, 'owner or birth'):
                    self.f.owner().run()
                self.no_acquisition()

    def test_completed_receipt_is_not_adopted(self):
        self.normal.last_wait.completed = True
        with self.assertRaisesRegex(ValueError, 'thread/exec'):
            self.f.owner().run()
        self.assertFalse(self.normal.last_wait.adopted)
        self.no_acquisition()

    def test_missing_read_facts_never_clears_entry(self):
        self.normal.last_wait.facts = None
        with self.assertRaisesRegex(ValueError, 'thread/exec'):
            self.f.owner().run()
        self.no_acquisition()

    def test_terminal_quarantine_blocks_before_pin(self):
        self.launch.retain_terminal_wait(11, 9)
        with self.assertRaisesRegex(CleanupReapError, 'owner or birth'):
            self.f.owner().run()
        self.no_acquisition()

    def test_second_running_exec_blocks_before_mutation(self):
        self.launch.dispatcher._sequence[1] = decode_syscall_info(*entry(59))
        with self.assertRaisesRegex(ValueError, 'thread/exec'):
            self.f.owner().run()
        self.assertIsNotNone(self.launch.dispatcher._sequence[2])
        self.no_acquisition()

    def test_unsettled_creation_is_not_hidden(self):
        self.ledger.reserve(1, 'fork')
        with self.assertRaisesRegex(ValueError, 'settled original'):
            self.f.owner().run()
        self.no_acquisition()

    def test_missing_wire_blocks_before_pin(self):
        self.launch.dispatcher._tasks[2] = (12, None)
        with self.assertRaises(ValueError):
            self.f.owner().run()
        self.no_acquisition()

    def test_existing_failure_and_exhausted_stop_ceiling_are_not_reset(self):
        self.ledger._event_limit = self.ledger.totals.wait_stops
        self.ledger._stopped = 'original exhausted stop budget'
        self.f.owner().run()
        self.assertEqual(self.ledger._event_limit, self.ledger.totals.wait_stops)
        self.assertEqual('original exhausted stop budget', self.ledger.totals.stopped)

    def test_pin_failure_keeps_settled_exit_and_no_false_reap(self):
        self.f.transport.open.side_effect = OSError('pin failed')
        with self.assertRaisesRegex(OSError, 'pin failed'):
            self.f.owner().run()
        self.assertIsNone(self.launch.dispatcher._sequence[2])
        self.assertEqual(3, self.ledger.totals.live)
        self.f.transport.send.assert_not_called()
        self.f.transport.wait.assert_not_called()

    def test_original_deadline_and_single_attempt_remain(self):
        owner = self.f.owner()
        self.f.f.clock.return_value = 100.0
        with self.assertRaisesRegex(CleanupReapError, 'deadline'):
            owner.run()
        with self.assertRaisesRegex(CleanupReapError, 'already attempted'):
            owner.run()
        self.no_acquisition()

    def test_actual_supervisor_retains_failure_and_reaps_without_exec_migration(self):
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
        leader_wire, child_wire = PtraceAccess(kernel), PtraceAccess(wire_kernel(child))
        f.launch.access = leader_wire
        f.launch.access_factory = lambda: child_wire
        child.signal_info.return_value = SignalInfo(19, 0, 0, 0, 0)
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
                return (0, 0)
            if index == 8:
                def read(_):
                    f.launch.dispatcher.stopped = 'original shared dispatch failure'
                    return decode_syscall_info(*exit_info(-2, 1))
                child.syscall_info.side_effect = read
                return (12, 0x857f)
            if index == 9:
                return (11, 9)
            if index == 10:
                return (12, 9)
            self.fail('unexpected wait')
        f.io.wait.side_effect = wait
        owner = f.owner()
        with self.assertRaisesRegex(ValueError, 'creation dispatcher stopped') as raised:
            owner.run()
        self.assertIs(owner.failure, raised.exception)
        self.assertIsNone(owner.cleanup_error)
        self.assertFalse(owner.drained)
        self.assertEqual('reaped', f.ledger.identity(1).terminal)
        self.assertEqual('reaped', f.ledger.identity(2).terminal)
        self.assertEqual(1, f.ledger.totals.live)
        self.assertEqual(3, f.ledger.totals.attempts)
        f.io.open.assert_called_once_with(11, 0)
        f.io.send.assert_called_once_with(70, 9, None, 0)
        self.assertEqual(2, child.resume_syscall.call_count)
        self.assertEqual(2, child.syscall_info.call_count)
        self.assertEqual({}, owner.dispatcher._exec_generations)
        self.assertFalse(child_wire._unscoped_mutation)


if __name__ == '__main__':
    unittest.main()
