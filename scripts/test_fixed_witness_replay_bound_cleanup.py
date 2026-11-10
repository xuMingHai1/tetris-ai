"""Event-bound creation cleanup with injected transports; no native tasks."""
from dataclasses import FrozenInstanceError
import struct
import unittest
from unittest.mock import Mock, call

from fixed_witness_replay_cleanup_reap import CleanupReapError
from fixed_witness_replay_creation import CLONE_VM, CLONE_VFORK, SIGCHLD
from fixed_witness_replay_ptrace import decode_syscall_info, SignalInfo
from fixed_witness_replay_wait_owner import WAIT_OPTIONS
from test_fixed_witness_replay_ptrace import entry
import test_fixed_witness_replay_settled_cleanup as settled_fixture


class BoundCreationCleanupTest(unittest.TestCase):
    def setUp(self):
        self.fixture = settled_fixture.SettledCleanupTest()
        self.fixture.setUp()
        self.launch, self.ledger = self.fixture.launch, self.fixture.ledger
        self.dispatch = self.fixture.fixture.fixture
        self.transport = self.fixture.transport

    def child(self, number=57, arguments=(0, 0, 0, 0, 0, 0)):
        raw, size = entry(number)
        raw = bytearray(raw)
        struct.pack_into('<6Q', raw, 32, *arguments)
        self.dispatch.syscall((bytes(raw), size))
        self.dispatch.wire.event_tid.return_value = 12
        self.dispatch.owner.accept(11, 0x2057f if number == 58 or arguments[0] & CLONE_VFORK
                                   else 0x1057f)
        return self.launch.dispatcher._task(2, 12)

    def stop(self):
        self.fixture.fixture.stopped()
        self.dispatch.wire.reset_mock()

    def test_pending_snapshot_is_immutable_and_available_after_failure(self):
        self.child()
        self.stop()
        before = self.ledger.totals
        facts, = self.ledger.cleanup_pending()
        self.assertEqual((2, 1, 'fork', False, 2),
                         (facts.reservation, facts.parent, facts.kind, facts.thread, facts.child))
        with self.assertRaises(FrozenInstanceError):
            facts.child = None
        self.assertEqual(before, self.ledger.totals)

    def test_parent_reap_closes_pending_without_result_or_attempt_refund(self):
        child = self.child()
        child.reset_mock()
        self.stop()
        self.transport.wait.side_effect = [(11, 9), (12, 9)]
        before = self.ledger.totals
        self.fixture.owner().run()
        self.assertEqual([call.open(11, 0), call.open(12, 0),
                          call.send(70, 9, None, 0), call.send(71, 9, None, 0),
                          call.wait(11, WAIT_OPTIONS), call.wait(12, WAIT_OPTIONS),
                          call.close(71), call.close(70)], self.transport.mock_calls)
        self.assertEqual((before.attempts, 1, 0, before.wait_stops),
                         (self.ledger.totals.attempts, self.ledger.totals.live,
                          self.ledger.totals.pending, self.ledger.totals.wait_stops))
        self.assertEqual('ledger failure', self.ledger.totals.stopped)
        self.assertEqual('lifecycle failure', self.launch.dispatcher.lifecycle.stopped)
        self.assertEqual([], child.mock_calls + self.dispatch.wire.mock_calls)
        self.assertEqual((), self.ledger.cleanup_pending())

    def test_child_reap_before_parent_retains_pending_and_live_parent(self):
        self.child()
        self.stop()
        def wait(tid, options):
            if self.transport.wait.call_count == 1:
                return (0, 0)
            if tid == 12:
                return (12, 9)
            self.assertEqual((2, 1, 3), (self.ledger.totals.live,
                                       self.ledger.totals.pending, self.ledger.totals.attempts))
            self.assertEqual(2, self.ledger.cleanup_pending()[0].child)
            return (11, 9)
        self.transport.wait.side_effect = wait
        result = self.fixture.owner().run()
        self.assertEqual([2, 1], [birth for birth, _, _ in result])
        self.assertEqual((1, 0), (self.ledger.totals.live, self.ledger.totals.pending))

    def test_vfork_event_bound_before_failed_parent_resume_remains_cleanup_identity(self):
        self.dispatch.syscall(entry(58))
        self.dispatch.wire.event_tid.return_value = 12
        self.dispatch.wire.resume_syscall.side_effect = OSError('uncertain resume')
        with self.assertRaises(OSError):
            self.dispatch.owner.accept(11, 0x2057f)
        self.dispatch.wire.reset_mock()
        self.transport.wait.side_effect = [(11, 9), (12, 9)]
        self.fixture.owner().run()
        self.assertEqual(1, self.ledger.totals.live)
        self.assertEqual([], self.dispatch.wire.mock_calls)

    def test_legacy_clone_process_and_clone_vfork_use_bound_process_path(self):
        for flags in (SIGCHLD | CLONE_VM, SIGCHLD | CLONE_VM | CLONE_VFORK):
            with self.subTest(flags=flags):
                self.setUp()
                self.child(56, (flags, 0, 0, 0, 0, 0))
                self.stop()
                self.transport.wait.side_effect = [(11, 9), (12, 9)]
                self.fixture.owner().run()
                self.assertEqual((3, 1, 0), (self.ledger.totals.attempts,
                                           self.ledger.totals.live, self.ledger.totals.pending))

    def test_child_wire_factory_failure_keeps_bound_birth_for_cleanup(self):
        self.dispatch.syscall(entry(57))
        self.dispatch.wire.event_tid.return_value = 12
        self.launch.dispatcher._factory = lambda: (_ for _ in ()).throw(OSError('wire failed'))
        with self.assertRaises(ValueError):
            self.dispatch.owner.accept(11, 0x1057f)
        self.assertIsNone(self.launch.dispatcher._task(2, 12))
        self.stop()
        self.transport.wait.side_effect = [(11, 9), (12, 9)]
        self.fixture.owner().run()
        self.assertEqual(1, self.ledger.totals.live)

    def test_already_reaped_child_does_not_reopen_its_pid(self):
        self.child()
        self.dispatch.owner.accept(12, 9)
        self.assertEqual((2, 1), (self.ledger.totals.live, self.ledger.totals.pending))
        self.stop()
        self.fixture.owner().run()
        self.transport.open.assert_called_once_with(11, 0)
        self.assertEqual((1, 0), (self.ledger.totals.live, self.ledger.totals.pending))

    def test_nested_bound_pending_processes_all_pin_before_signal(self):
        child = self.child()
        child.signal_info.return_value = SignalInfo(19, 0, 0, 0, 0)
        self.dispatch.owner.accept(12, 0x137f)
        child.syscall_info.return_value = decode_syscall_info(*entry(57))
        self.dispatch.owner.accept(12, 0x857f)
        child.event_tid.return_value = 13
        self.launch.dispatcher._factory = Mock(return_value=Mock())
        self.dispatch.owner.accept(12, 0x1057f)
        self.stop()
        self.transport.open.side_effect = [70, 71, 72]
        self.transport.wait.side_effect = [(11, 9), (12, 9), (13, 9)]
        self.fixture.owner().run()
        self.assertEqual([call(11, 0), call(12, 0), call(13, 0)],
                         self.transport.open.call_args_list)
        self.assertEqual([call.open(11, 0), call.open(12, 0), call.open(13, 0)],
                         self.transport.mock_calls[:3])
        self.assertEqual((4, 1, 0), (self.ledger.totals.attempts,
                                   self.ledger.totals.live, self.ledger.totals.pending))

    def test_missing_child_event_refuses_before_any_native_transport(self):
        self.dispatch.syscall(entry(57))
        self.stop()
        with self.assertRaisesRegex(CleanupReapError, 'unresolved creation'):
            self.fixture.owner().run()
        self.assertEqual([], self.transport.mock_calls)
        self.assertEqual((3, 1), (self.ledger.totals.live, self.ledger.totals.pending))

    def test_bound_child_with_own_unbound_creation_refuses_entire_inventory(self):
        self.child()
        self.ledger.reserve(2, 'fork')
        self.stop()
        with self.assertRaisesRegex(CleanupReapError, 'unresolved creation'):
            self.fixture.owner().run()
        self.assertEqual([], self.transport.mock_calls)
        self.assertEqual((4, 2), (self.ledger.totals.live, self.ledger.totals.pending))

    def test_pending_thread_and_clone3_still_refuse_before_pin(self):
        for kind, thread in (('clone', True), ('clone3', False)):
            self.setUp()
            reservation = self.ledger.reserve(1, kind, thread=thread)
            if thread:
                self.ledger.created(reservation, 12)
                self.launch.dispatcher._bind(reservation, 12, newborn=True)
            self.stop()
            with self.assertRaisesRegex(CleanupReapError, 'unresolved creation'):
                self.fixture.owner().run()
            self.assertEqual([], self.transport.mock_calls)

    def test_partial_pin_failure_preserves_pending_and_both_live_credits(self):
        self.child()
        self.stop()
        before = self.ledger.totals
        self.transport.open.side_effect = [70, OSError('pin failed')]
        with self.assertRaises(OSError):
            self.fixture.owner().run()
        self.assertEqual(before, self.ledger.totals)
        self.transport.send.assert_not_called()
        self.transport.wait.assert_not_called()
        self.transport.close.assert_called_once_with(70)

    def test_newborn_queued_stop_drains_only_after_all_pidfd_kills(self):
        child = self.child()
        child.reset_mock()
        self.stop()
        before = self.ledger.totals.wait_stops
        self.transport.wait.side_effect = [(0, 0), (12, 0x137f), (11, 9), (12, 9)]
        owner = self.fixture.owner()
        owner.run()
        self.assertEqual(1, owner.reaper.drained_stops)
        self.assertEqual(before + 1, self.ledger.totals.wait_stops)
        self.assertEqual([], child.mock_calls + self.dispatch.wire.mock_calls)

    def test_terminal_at_expiry_keeps_pending_and_quarantines_consumed_parent(self):
        self.child()
        self.stop()
        def wait(*args):
            self.dispatch.clock.return_value = 100.0
            return (11, 9)
        self.transport.wait.side_effect = wait
        with self.assertRaisesRegex(CleanupReapError, 'deadline'):
            self.fixture.owner().run()
        self.assertEqual({11}, self.launch.unsettled_terminal_waits)
        self.assertEqual((3, 1), (self.ledger.totals.live, self.ledger.totals.pending))

    def test_missing_dispatcher_child_binding_cannot_pin_ledger_identity_alone(self):
        self.child()
        del self.launch.dispatcher._tasks[2]
        self.stop()
        with self.assertRaisesRegex(ValueError, 'birth binding'):
            self.fixture.owner().run()
        self.assertEqual([], self.transport.mock_calls)
        self.assertEqual((3, 1), (self.ledger.totals.live, self.ledger.totals.pending))


if __name__ == '__main__':
    unittest.main()
