"""Synthetic waits and fake kernel only; no native fork, ptrace or probes."""
import unittest
import ctypes
import struct
from unittest.mock import Mock, call

from fixed_witness_replay_stop_dispatch import PostHandshakeDispatcher, StopDispatchError
from fixed_witness_replay_ptrace import PtraceAccess, SignalInfo, decode_syscall_info
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

    def test_handshake_then_first_native_entry_exit_resume_once_each(self):
        self.syscall(entry(39))
        self.syscall(exit_info(11, 0))
        self.assertEqual(2, self.wire.syscall_info.call_count)
        self.assertEqual([((11, 0),), ((11, 0),)], self.wire.resume_syscall.call_args_list)
        self.assertEqual(3, self.ledger.totals.wait_stops)

    def test_coordinator_unpaired_first_exit_never_resumes(self):
        for result, error in ((0, 0), (11, 0), (-38, 1)):
            self.setUp()
            with self.assertRaisesRegex(ValueError, 'without entry'):
                self.syscall(exit_info(result, error))
            self.wire.resume_syscall.assert_not_called()
            self.assertEqual((2, 2, 2), (self.ledger.totals.attempts,
                                       self.ledger.totals.live,
                                       self.ledger.totals.wait_stops))
            self.wire.reset_mock()
            with self.assertRaises(StopDispatchError):
                self.syscall(entry(39))
            self.assertEqual([], self.wire.mock_calls)

    def test_actual_wire_clone3_single_read_and_debit_before_write(self):
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
        self.assertEqual((3, 2, 3), (self.ledger.totals.attempts,
                                   self.ledger.totals.live, self.ledger.totals.wait_stops))

    def test_fork_event_uses_same_wire_and_preserves_child_before_resume(self):
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
        self.syscall(entry(57))
        self.wire.event_tid.return_value = 12
        self.owner.accept(11, 0x1057f)
        return self.launch.dispatcher.stop_binding(12)[1]

    def test_newborn_initial_stop_options_before_resume_then_first_entry(self):
        child_wire = self.child()
        child_wire.signal_info.return_value = SignalInfo(19, 0, 0, 0, 0)
        before = self.ledger.totals
        self.owner.accept(12, 0x137f)
        self.assertEqual([call.signal_info(12, 19), call.set_options(12),
                          call.resume_syscall(12, 0)], child_wire.mock_calls)
        self.assertEqual(before.wait_stops + 1, self.ledger.totals.wait_stops)
        self.assertEqual(before.attempts, self.ledger.totals.attempts)
        self.assertEqual(before.live, self.ledger.totals.live)
        child_wire.reset_mock()
        child_wire.syscall_info.return_value = decode_syscall_info(*entry(39))
        self.owner.accept(12, 0x857f)
        child_wire.syscall_info.return_value = decode_syscall_info(*exit_info(12, 0))
        self.owner.accept(12, 0x857f)
        child_wire.set_options.assert_not_called()
        self.assertEqual(2, child_wire.resume_syscall.call_count)

    def test_newborn_wrong_signal_identity_or_group_stop_never_resumes(self):
        for info in (SignalInfo(19, 0, 0, 12, 0), SignalInfo(19, 0, 0, 0, 1000),
                     SignalInfo(19, 0, -6, 0, 0), SignalInfo(19, 0, 128),
                     SignalInfo(19, 1, 0, 0, 0), SignalInfo(5, 0, 0, 0, 0), None):
            self.setUp()
            child_wire = self.child()
            child_wire.signal_info.return_value = info
            with self.assertRaisesRegex(StopDispatchError, 'newborn'):
                self.owner.accept(12, 0x137f)
            child_wire.set_options.assert_not_called()
            child_wire.resume_syscall.assert_not_called()
            self.assertEqual(3, self.ledger.totals.live)
        self.setUp()
        child_wire = self.child()
        child_wire.signal_info.side_effect = OSError('EINVAL group stop')
        with self.assertRaises(StopReadError):
            self.owner.accept(12, 0x137f)
        child_wire.resume_syscall.assert_not_called()

    def test_newborn_real_signal_wire_reads_once_and_installs_frozen_options(self):
        self.child()
        requests = []
        def kernel(request, tid, address, data):
            self.assertEqual(12, tid)
            requests.append((request, data if type(data) is int else None))
            if request == 0x4202:
                raw = struct.pack('<iii4xiI', 19, 0, 0, 0, 0).ljust(128, b'\0')
                ctypes.memmove(data, raw, 128)
            return 0
        self.launch.dispatcher._tasks[2] = (12, PtraceAccess(kernel))
        self.owner.accept(12, 0x137f)
        self.assertEqual([(0x4202, None), (0x4200, 0x0010001f), (24, 0)], requests)

    def test_newborn_wait_cap_blocks_siginfo_options_and_resume(self):
        child_wire = self.child()
        self.ledger._event_limit = self.ledger.totals.wait_stops + 1
        with self.assertRaises(StopReadError):
            self.owner.accept(12, 0x137f)
        self.assertEqual([], child_wire.mock_calls)
        self.assertEqual(3, self.ledger.totals.live)

    def test_newborn_unpaired_creation_exit_after_admission_is_rejected(self):
        child_wire = self.child()
        child_wire.signal_info.return_value = SignalInfo(19, 0, 0, 0, 0)
        self.owner.accept(12, 0x137f)
        child_wire.reset_mock()
        child_wire.syscall_info.return_value = decode_syscall_info(*exit_info(0, 0))
        with self.assertRaises(ValueError):
            self.owner.accept(12, 0x857f)
        child_wire.resume_syscall.assert_not_called()

    def test_newborn_syscall_or_event_rejects_before_kernel_access(self):
        for raw in (0x857f, 0x1057f):
            self.setUp()
            child_wire = self.child()
            with self.assertRaisesRegex(StopDispatchError, 'newborn'):
                self.owner.accept(12, raw)
            self.assertEqual([], child_wire.mock_calls)
            self.assertEqual(3, self.ledger.totals.live)

    def test_newborn_duplicate_initial_stop_rejected_without_second_options(self):
        child_wire = self.child()
        child_wire.signal_info.return_value = SignalInfo(19, 0, 0, 0, 0)
        self.owner.accept(12, 0x137f)
        child_wire.reset_mock()
        with self.assertRaises(StopDispatchError):
            self.owner.accept(12, 0x137f)
        child_wire.set_options.assert_not_called()
        child_wire.resume_syscall.assert_not_called()

    def test_newborn_options_or_resume_failure_latches_without_retry(self):
        for method in ('set_options', 'resume_syscall'):
            self.setUp()
            child_wire = self.child()
            child_wire.signal_info.return_value = SignalInfo(19, 0, 0, 0, 0)
            getattr(child_wire, method).side_effect = OSError('uncertain request')
            with self.assertRaises(OSError):
                self.owner.accept(12, 0x137f)
            if method == 'set_options':
                child_wire.resume_syscall.assert_not_called()
            child_wire.reset_mock()
            with self.assertRaises(StopDispatchError):
                self.owner.accept(12, 0x137f)
            self.assertEqual([], child_wire.mock_calls)
            self.assertEqual(3, self.ledger.totals.live)

    def test_newborn_deadline_after_options_prevents_first_resume(self):
        child_wire = self.child()
        child_wire.signal_info.return_value = SignalInfo(19, 0, 0, 0, 0)
        self.clock.side_effect = (90.0, 100.0)
        with self.assertRaises(ValueError):
            self.owner.accept(12, 0x137f)
        child_wire.set_options.assert_called_once_with(12)
        child_wire.resume_syscall.assert_not_called()

    def test_unknown_initial_stop_signature_does_not_create_birth(self):
        child_wire = self.child()
        with self.assertRaises(ValueError):
            self.owner.accept(99, 0x137f)
        self.assertEqual([], child_wire.mock_calls)
        self.assertEqual(3, self.ledger.totals.live)

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
            self.syscall(entry(39))
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
        with self.assertRaisesRegex(ValueError, 'ptrace'):
            self.syscall(entry(101))
        self.wire.resume_syscall.assert_not_called()

    def test_resume_failure_latches_no_retry_but_next_stop_still_debits(self):
        self.wire.resume_syscall.side_effect = OSError('uncertain resume')
        with self.assertRaises(OSError):
            self.syscall(entry(39))
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
