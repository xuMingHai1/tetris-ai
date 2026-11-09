"""Synthetic waits and fake kernel only; no native fork, ptrace or probes."""
import unittest
from unittest.mock import Mock

from fixed_witness_replay_stop_dispatch import PostHandshakeDispatcher, StopDispatchError
from fixed_witness_replay_ptrace import PtraceAccess, decode_syscall_info
from fixed_witness_replay_stops import StopReadError
import test_fixed_witness_replay_launch as launch_fixture
from test_fixed_witness_replay_ptrace import KernelFixture, entry, exit_info


class PostHandshakeDispatchTest(unittest.TestCase):
    def setUp(self, **limits):
        fixture = launch_fixture.CoordinatorLaunchTest()
        fixture.setUp(**limits)
        self.launch = fixture.launcher
        self.launch.launch()
        self.launch.accept_initial_wait(11, 0x137f)
        self.wire = fixture.access
        self.wire.reset_mock()
        self.owner = PostHandshakeDispatcher(self.launch)
        self.ledger = fixture.ledger
        self.clock, self.system = fixture.clock, fixture.system

    def syscall(self, raw):
        self.wire.syscall_info.return_value = decode_syscall_info(*raw)
        return self.owner.accept(11, 0x857f)

    def handshake_exit(self):
        self.syscall(exit_info(0, 0))
        self.wire.reset_mock()

    def test_handshake_exit_then_native_entry_exit_resume_once_each(self):
        self.handshake_exit()
        self.syscall(entry(39))
        self.syscall(exit_info(11, 0))
        self.assertEqual(2, self.wire.syscall_info.call_count)
        self.assertEqual([((11, 0),), ((11, 0),)], self.wire.resume_syscall.call_args_list)
        self.assertEqual(4, self.ledger.totals.wait_stops)

    def test_actual_wire_clone3_single_read_and_debit_before_write(self):
        self.handshake_exit()
        kernel = KernelFixture()
        wire = PtraceAccess(kernel)
        resume = Mock()
        # Preserve real syscall/register transport; inject only the resume syscall.
        wire.resume_syscall = resume
        self.launch.dispatcher._tasks[1] = (11, wire)
        original = kernel.__call__
        def checked(request, *args):
            if request == 0x4205:
                self.assertEqual(3, self.ledger.totals.attempts)
            return original(request, *args)
        wire._call = checked
        self.owner.accept(11, 0x857f)
        kernel.suppressed_exit()
        self.owner.accept(11, 0x857f)
        self.assertEqual(2, sum(r[0] == 0x420e for r in kernel.requests))
        self.assertEqual(2, resume.call_count)
        self.assertEqual((3, 2, 4), (self.ledger.totals.attempts,
                                   self.ledger.totals.live, self.ledger.totals.wait_stops))

    def test_fork_event_uses_same_wire_and_preserves_child_before_resume(self):
        self.handshake_exit()
        self.syscall(entry(57))
        self.wire.reset_mock()
        self.wire.event_tid.return_value = 12
        def resume(tid, sig):
            ticket, child_wire = self.launch.dispatcher.stop_binding(12)
            self.assertEqual(2, ticket)
            self.assertIsNot(child_wire, self.wire)
            self.assertEqual(3, self.ledger.totals.live)
        self.wire.resume_syscall.side_effect = resume
        self.owner.accept(11, 0x1057f)
        self.wire.event_tid.assert_called_once_with(11)
        self.wire.syscall_info.assert_not_called()
        self.wire.resume_syscall.assert_called_once_with(11, 0)

    def child(self):
        self.handshake_exit()
        self.syscall(entry(57))
        self.wire.event_tid.return_value = 12
        self.owner.accept(11, 0x1057f)
        return self.launch.dispatcher.stop_binding(12)[1]

    def test_newborn_stop_rejects_before_kernel_access_and_keeps_credit(self):
        child_wire = self.child()
        with self.assertRaisesRegex(StopDispatchError, 'newborn'):
            self.owner.accept(12, 0x137f)
        self.assertEqual([], child_wire.mock_calls)
        self.assertEqual(3, self.ledger.totals.live)
        self.assertIsNotNone(self.owner.stopped)

    def test_newborn_terminal_reap_releases_only_its_birth(self):
        child_wire = self.child()
        self.owner.accept(12, 0)
        self.assertEqual([], child_wire.mock_calls)
        self.assertEqual(2, self.ledger.totals.live)
        self.syscall(exit_info(12, 0))
        self.owner.accept(11, 9)
        self.assertEqual(1, self.ledger.totals.live)
        self.assertEqual(11, self.launch.cleanup_tid)

    def test_unknown_tid_charged_without_any_kernel_access(self):
        with self.assertRaises(ValueError):
            self.owner.accept(99, 0x857f)
        self.assertEqual(2, self.ledger.totals.wait_stops)
        self.assertEqual([], self.wire.mock_calls)

    def test_expired_deadline_or_foreign_owner_charged_before_access(self):
        for failure in ('deadline', 'owner'):
            self.setUp()
            if failure == 'deadline':
                self.clock.return_value = 100.0
            else:
                self.system.getpid.return_value = 99
            with self.assertRaises(ValueError):
                self.owner.accept(11, 0x857f)
            self.assertEqual(2, self.ledger.totals.wait_stops)
            self.assertEqual([], self.wire.mock_calls)

    def test_deadline_after_policy_prevents_resume(self):
        self.clock.side_effect = (90.0, 100.0)
        with self.assertRaises(ValueError):
            self.syscall(exit_info(0, 0))
        self.wire.syscall_info.assert_called_once_with(11)
        self.wire.resume_syscall.assert_not_called()

    def test_event_limit_prevents_identity_or_kernel_access(self):
        self.setUp(wait_stops=2)
        with self.assertRaises(StopReadError):
            self.owner.accept(99, 0x857f)
        self.assertEqual(2, self.ledger.totals.wait_stops)
        self.assertEqual([], self.wire.mock_calls)

    def test_signals_exec_and_malformed_stops_never_resume(self):
        for status in (0x137f, 0x57f, 0x4057f, 0x9057f):
            self.setUp()
            self.wire.event_tid.return_value = 11
            with self.assertRaises(ValueError):
                self.owner.accept(11, status)
            self.assertEqual(2, self.ledger.totals.wait_stops)
            self.wire.resume_syscall.assert_not_called()

    def test_managed_ptrace_entry_rejected_before_resume(self):
        self.handshake_exit()
        with self.assertRaisesRegex(ValueError, 'ptrace'):
            self.syscall(entry(101))
        self.wire.resume_syscall.assert_not_called()

    def test_resume_failure_latches_no_retry_but_next_stop_still_debits(self):
        self.wire.resume_syscall.side_effect = OSError('uncertain resume')
        with self.assertRaises(OSError):
            self.syscall(exit_info(0, 0))
        self.wire.reset_mock()
        with self.assertRaises(StopDispatchError):
            self.owner.accept(11, 0x857f)
        self.assertEqual(3, self.ledger.totals.wait_stops)
        self.assertEqual([], self.wire.mock_calls)

    def test_constructor_requires_completed_handshake_and_single_owner(self):
        with self.assertRaises(StopDispatchError):
            PostHandshakeDispatcher(self.launch)
        del self.launch._post_handshake_claimed
        self.launch.handshake.completed = False
        with self.assertRaises(StopDispatchError):
            PostHandshakeDispatcher(self.launch)

