"""Synthetic launch only: every fork/descriptor/child/ptrace operation is fake."""
import unittest
from unittest.mock import Mock, call, patch

from fixed_witness_replay_launch import CoordinatorLaunch, CoordinatorLaunchError
from fixed_witness_replay_ptrace import PtraceAccess, SignalInfo
from fixed_witness_replay_stub import CoordinatorStub
from fixed_witness_replay_startup_pin import CoordinatorPin
from fixed_witness_replay_tasks import TaskLedger, TaskAccountingError


class ChildExited(BaseException):
    pass


class CoordinatorLaunchTest(unittest.TestCase):
    def setUp(self, **limits):
        self.ledger = TaskLedger(10, **limits)
        self.system = Mock(spec=['getpid', 'listdir', 'fstat', 'fork', 'getuid',
                                 'dup2', 'closerange', '_exit'])
        self.system.getpid.return_value = 10
        self.system.listdir.return_value = ['10']
        self.system.getuid.return_value = 1000
        self.system.fork.return_value = 11
        self.system._exit.side_effect = ChildExited
        self.stub = Mock(spec=CoordinatorStub)
        self.stub.parent_pid = 10
        self.access = Mock(spec=PtraceAccess)
        self.access.signal_info.return_value = SignalInfo(19, 0, -6, 11, 1000)
        self.clock = Mock(return_value=90.0)
        self.factory = Mock(return_value=Mock(spec=PtraceAccess))
        self.launcher = self.make()

    def make(self, **kwargs):
        args = dict(ledger=self.ledger, stub=self.stub, access=self.access,
                    stdio=(7, 8, 9), deadline=100.0, system=self.system,
                    clock=self.clock, access_factory=self.factory)
        args.update(kwargs)
        return CoordinatorLaunch(**args)

    def test_reservation_precedes_only_fork_then_birth_and_handshake(self):
        def fork():
            self.assertEqual((2, 2, 1), (self.ledger.totals.attempts,
                                       self.ledger.totals.live, self.ledger.totals.pending))
            return 11
        self.system.fork.side_effect = fork
        self.assertEqual(11, self.launcher.launch())
        self.assertEqual(11, self.launcher.cleanup_tid)
        self.assertEqual(0, self.ledger.totals.pending)
        self.stub.run.assert_not_called()
        self.access.assert_not_called()
        self.launcher.accept_initial_wait(11, 0x137f)
        self.assertTrue(self.launcher.handshake.completed)
        self.assertEqual(1, self.ledger.totals.wait_stops)
        self.access.set_options.assert_called_once_with(11)
        self.access.resume_syscall.assert_called_once_with(11, 0)
        self.system.fork.assert_called_once_with()

    def test_failed_fork_never_refunds_attempt_or_retries(self):
        self.system.fork.side_effect = OSError('fork failed')
        with self.assertRaises(OSError):
            self.launcher.launch()
        self.assertEqual((2, 1, 0), (self.ledger.totals.attempts,
                                   self.ledger.totals.live, self.ledger.totals.pending))
        self.assertIsNone(self.launcher.cleanup_tid)
        with self.assertRaises(CoordinatorLaunchError):
            self.launcher.launch()
        self.system.fork.assert_called_once_with()

    def test_attempt_or_live_limit_prevents_fork(self):
        for limits in (dict(attempts=1), dict(live=1)):
            self.setUp(**limits)
            with self.assertRaises(TaskAccountingError):
                self.launcher.launch()
            self.system.fork.assert_not_called()
            self.assertIsNotNone(self.launcher.stopped)

    def test_owner_or_extra_native_thread_rejects_before_reservation(self):
        for pid, tids in ((12, ['12']), (10, ['10', '12']), (10, []), (10, ['12'])):
            self.setUp()
            self.system.getpid.return_value = pid
            self.system.listdir.return_value = tids
            with self.assertRaises(CoordinatorLaunchError):
                self.launcher.launch()
            self.assertEqual(1, self.ledger.totals.attempts)
            self.system.fork.assert_not_called()

    def test_mismatched_ledger_tracer_cannot_fork(self):
        self.launcher = self.make(ledger=TaskLedger(99))
        with self.assertRaisesRegex(TaskAccountingError, 'tracer identity'):
            self.launcher.launch()
        self.system.fork.assert_not_called()

    def test_original_deadline_checked_again_after_fd_validation(self):
        self.clock.side_effect = (90.0, 100.0)
        with self.assertRaisesRegex(CoordinatorLaunchError, 'deadline'):
            self.launcher.launch()
        self.system.fork.assert_not_called()
        self.assertEqual(1, self.ledger.totals.attempts)

    def test_closed_source_fd_fails_before_debit(self):
        self.system.fstat.side_effect = OSError('closed descriptor')
        with self.assertRaises(OSError):
            self.launcher.launch()
        self.system.fork.assert_not_called()
        self.assertEqual(1, self.ledger.totals.attempts)

    def test_child_maps_fixed_descriptors_closes_all_others_before_stub(self):
        self.system.fork.return_value = 0
        events = Mock()
        events.attach_mock(self.system, 'os')
        events.attach_mock(self.stub, 'stub')
        with self.assertRaises(ChildExited):
            self.launcher.launch()
        start = events.mock_calls.index(call.os.dup2(7, 0, inheritable=True))
        self.assertEqual([call.os.dup2(7, 0, inheritable=True),
                          call.os.dup2(8, 1, inheritable=True),
                          call.os.dup2(9, 2, inheritable=True),
                          call.os.closerange(3, 2**31 - 1), call.stub.run(),
                          call.os._exit(125)], events.mock_calls[start:])
        self.system.getuid.assert_not_called()
        self.assertIsNone(self.launcher.cleanup_tid)
        self.assertIsNone(self.launcher.handshake)

    def test_child_fd_failure_exits_without_stub_or_parent_binding(self):
        for method in ('dup2', 'closerange'):
            self.setUp()
            self.system.fork.return_value = 0
            getattr(self.system, method).side_effect = OSError('failed')
            with self.assertRaises(ChildExited):
                self.launcher.launch()
            self.stub.run.assert_not_called()
            self.system._exit.assert_called_once_with(125)
            self.assertIsNone(self.launcher.handshake)

    def test_parent_failure_retains_identity_and_no_false_reap_credit(self):
        self.system.getuid.side_effect = OSError('failed')
        with self.assertRaises(OSError):
            self.launcher.launch()
        self.assertEqual(11, self.launcher.cleanup_tid)
        self.assertEqual((2, 2, 0), (self.ledger.totals.attempts,
                                   self.ledger.totals.live, self.ledger.totals.pending))
        with self.assertRaises(CoordinatorLaunchError):
            self.launcher.accept_initial_wait(11, 0x137f)
        self.access.resume_syscall.assert_not_called()

    def test_identity_is_saved_before_fallible_ledger_binding(self):
        with patch.object(self.ledger, 'created', side_effect=TaskAccountingError('failed')):
            with self.assertRaises(TaskAccountingError):
                self.launcher.launch()
        self.assertEqual(11, self.launcher.cleanup_tid)
        self.assertEqual(2, self.ledger.totals.live)

    def test_expired_or_foreign_owner_wait_charged_without_kernel_access(self):
        for expired in (True, False):
            self.setUp()
            self.launcher.launch()
            if expired:
                self.clock.return_value = 100.0
            else:
                self.system.listdir.return_value = ['10', '12']
            with self.assertRaises((OSError, ValueError)):
                self.launcher.accept_initial_wait(11, 0x137f)
            self.assertEqual(1, self.ledger.totals.wait_stops)
            self.assertEqual([], self.access.mock_calls)
            self.assertEqual(11, self.launcher.cleanup_tid)

    def test_wrong_wait_and_resume_failure_never_allow_another_admission(self):
        for wrong_tid in (True, False):
            self.setUp()
            self.launcher.launch()
            if not wrong_tid:
                self.access.resume_syscall.side_effect = OSError('uncertain resume')
            with self.assertRaises(ValueError):
                self.launcher.accept_initial_wait(12 if wrong_tid else 11, 0x137f)
            calls = list(self.access.mock_calls)
            with self.assertRaises(CoordinatorLaunchError):
                self.launcher.accept_initial_wait(11, 0x137f)
            self.assertEqual(calls, self.access.mock_calls)
            self.assertEqual(1, self.ledger.totals.wait_stops)

    def test_success_cannot_fork_or_handshake_again(self):
        self.launcher.launch()
        self.launcher.accept_initial_wait(11, 0x137f)
        with self.assertRaises(CoordinatorLaunchError):
            self.launcher.launch()
        with self.assertRaises(CoordinatorLaunchError):
            self.launcher.accept_initial_wait(11, 0x137f)
        self.system.fork.assert_called_once_with()
        self.access.resume_syscall.assert_called_once_with(11, 0)

    def test_each_later_birth_gets_its_own_wire(self):
        self.launcher.launch()
        other = self.launcher._birth_wire()
        self.assertIs(self.factory.return_value, other)
        self.assertIsNot(self.access, other)
        self.factory.assert_called_once_with()

    def test_invalid_stdio_or_deadline_rejected_without_syscalls(self):
        for stdio in ((0, 1, 2), [7, 8, 9], (7, 8), (True, 8, 9), (7, 8, 2**31 - 1)):
            with self.assertRaises(ValueError):
                self.make(stdio=stdio)
        for deadline in (True, '100', float('inf'), float('nan')):
            with self.assertRaises(ValueError):
                self.make(deadline=deadline)
        self.assertEqual([], self.system.mock_calls)

    def test_wait_budget_exhaustion_prevents_any_ptrace_or_resume(self):
        self.setUp(wait_stops=1)
        self.launcher.launch()
        with self.assertRaisesRegex(ValueError, 'wait-stop limit'):
            self.launcher.accept_initial_wait(11, 0x137f)
        self.assertEqual([], self.access.mock_calls)
        self.assertEqual(11, self.launcher.cleanup_tid)
        self.assertEqual(2, self.ledger.totals.live)

    def test_host_preparation_loads_wires_without_reserving_or_forking(self):
        pin = CoordinatorPin(Mock(), Mock(), Mock())
        with patch('fixed_witness_replay_launch.CoordinatorStub.for_host',
                   return_value=self.stub) as stub_factory, patch(
                       'fixed_witness_replay_launch.PtraceAccess.for_host',
                       return_value=self.access), patch(
                           'fixed_witness_replay_launch.os.getpid', return_value=10), patch(
                               'fixed_witness_replay_launch.CoordinatorPin.for_host',
                               return_value=pin):
            launcher = CoordinatorLaunch.for_host(
                self.ledger, ['/trusted/python'], '/build', {}, (7, 8, 9), 100.0)
        stub_factory.assert_called_once_with(['/trusted/python'], '/build', {}, 10)
        self.assertFalse(launcher.attempted)
        self.assertEqual(1, self.ledger.totals.attempts)
        self.assertEqual([], self.access.mock_calls)
        self.assertIs(pin, launcher.startup_pin)
        pin.action.assert_not_called()
        pin.open_pidfd.assert_not_called()


if __name__ == '__main__':
    unittest.main()
