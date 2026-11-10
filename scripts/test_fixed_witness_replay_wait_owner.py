"""Synthetic targeted waits only; no native fork, wait, ptrace or probes."""
import errno
import unittest
from unittest.mock import Mock, call

from fixed_witness_replay_wait_owner import BoundedWaitOwner, WaitOwnerError, WAIT_OPTIONS
from fixed_witness_replay_ptrace import SignalInfo, decode_syscall_info
from fixed_witness_replay_stops import StopReadError
from fixed_witness_replay_dispatch import CreationDispatchError
import test_fixed_witness_replay_stop_dispatch as dispatch_fixture
from test_fixed_witness_replay_ptrace import entry, exit_info


class BoundedWaitOwnerTest(unittest.TestCase):
    def setUp(self):
        fixture = dispatch_fixture.PostHandshakeDispatchTest()
        fixture.setUp()
        self.fixture = fixture
        self.dispatch = fixture.owner
        self.launch, self.ledger = fixture.launch, fixture.ledger
        self.wire = fixture.wire
        self.wait = Mock()
        self.sleep = Mock()
        self.owner = BoundedWaitOwner(self.dispatch, waitpid=self.wait, sleep=self.sleep)

    def test_terminal_reap_drains_without_extra_wait_or_stop_charge(self):
        self.wait.return_value = (11, 0)
        totals = self.owner.run()
        self.assertEqual((1, 1), (totals.live, totals.wait_stops))
        self.wait.assert_called_once_with(11, 0x60000001)
        self.assertEqual([], self.wire.mock_calls)
        self.sleep.assert_not_called()
        self.assertEqual(11, self.launch.cleanup_tid)

    def test_child_stop_before_parent_event_remains_in_kernel_until_bound(self):
        self.fixture.syscall(entry(57))
        self.wire.reset_mock()
        self.wire.event_tid.return_value = 12
        self.wire.syscall_info.return_value = decode_syscall_info(*exit_info(12, 0))
        child_wire = self.launch.access_factory.return_value
        child_wire.signal_info.return_value = SignalInfo(19, 0, 0, 0, 0)
        # Queue is in arrival order; targeted wait may only remove its TID.
        pending = [(12, 0x137f), (11, 0x1057f), (11, 0x857f), (12, 0), (11, 0)]
        def wait(tid, options):
            self.assertEqual(WAIT_OPTIONS, options)
            if tid == 12:
                self.assertEqual(2, self.launch.dispatcher.stop_binding(12)[0])
            for i, result in enumerate(pending):
                if result[0] == tid:
                    return pending.pop(i)
            return (0, 0)
        self.wait.side_effect = wait
        totals = self.owner.run()
        self.assertEqual([], pending)
        self.assertEqual((3, 1, 0, 5), (totals.attempts, totals.live,
                                       totals.pending, totals.wait_stops))
        self.assertEqual([call(11, WAIT_OPTIONS), call(12, WAIT_OPTIONS),
                          call(11, WAIT_OPTIONS), call(12, WAIT_OPTIONS),
                          call(11, WAIT_OPTIONS)], self.wait.call_args_list)
        self.assertEqual([call.signal_info(12, 19), call.set_options(12),
                          call.resume_syscall(12, 0)], child_wire.mock_calls)
        self.sleep.assert_not_called()

    def test_wait_round_rotates_to_child_even_when_parent_always_ready(self):
        child_wire = self.fixture.child()
        child_wire.signal_info.return_value = SignalInfo(19, 0, 0, 0, 0)
        self.wire.syscall_info.return_value = decode_syscall_info(*exit_info(12, 0))
        self.wait.side_effect = [(11, 0x857f), (12, 0x137f), (11, 0), (12, 0)]
        self.owner.run()
        self.assertEqual([11, 12, 11, 12], [c.args[0] for c in self.wait.call_args_list])

    def test_no_event_polls_all_bound_births_then_sleeps(self):
        self.fixture.child()
        self.wait.side_effect = [(0, 0), (0, 0), (11, 0), (12, 0)]
        self.owner.run()
        self.assertEqual([11, 12, 11, 12], [c.args[0] for c in self.wait.call_args_list])
        self.sleep.assert_called_once_with(0.01)
        self.assertEqual(3, self.ledger.totals.wait_stops)

    def test_idle_sleep_cannot_extend_original_deadline(self):
        self.wait.return_value = (0, 0)
        self.fixture.clock.return_value = 99.997
        self.sleep.side_effect = lambda seconds: setattr(self.fixture.clock, 'return_value', 100.0)
        with self.assertRaisesRegex(ValueError, 'deadline'):
            self.owner.run()
        self.assertAlmostEqual(0.003, self.sleep.call_args.args[0])
        self.wait.assert_called_once()
        self.assertEqual(1, self.ledger.totals.wait_stops)
        self.assertEqual(2, self.ledger.totals.live)

    def test_eintr_is_not_stop_or_new_deadline(self):
        self.wait.side_effect = [InterruptedError(), (11, 0)]
        self.owner.run()
        self.sleep.assert_called_once_with(0.01)
        self.assertEqual(1, self.ledger.totals.wait_stops)

    def test_eintr_storm_stops_at_original_deadline(self):
        self.wait.side_effect = InterruptedError()
        self.sleep.side_effect = lambda seconds: setattr(self.fixture.clock, 'return_value', 100.0)
        with self.assertRaises(ValueError):
            self.owner.run()
        self.wait.assert_called_once()
        self.assertEqual(2, self.ledger.totals.live)

    def test_echild_or_wait_failure_never_refunds_live_credit_or_retries(self):
        for error in (ChildProcessError(), OSError('wait failed')):
            self.setUp()
            self.wait.side_effect = error
            with self.assertRaises(OSError):
                self.owner.run()
            self.wait.assert_called_once()
            self.assertEqual(2, self.ledger.totals.live)
            self.assertIsNotNone(self.launch.stopped)
            with self.assertRaises(WaitOwnerError):
                self.owner.run()
            self.wait.assert_called_once()

    def test_wrong_target_stop_debits_before_reject_without_ptrace(self):
        self.wait.return_value = (99, 0x137f)
        with self.assertRaises(StopReadError):
            self.owner.run()
        self.assertEqual((2, 2), (self.ledger.totals.live, self.ledger.totals.wait_stops))
        self.assertEqual([], self.wire.mock_calls)

    def test_other_known_target_terminal_cannot_settle_wrong_birth(self):
        child_wire = self.fixture.child()
        self.wire.reset_mock()
        self.wait.return_value = (12, 0)
        with self.assertRaises(WaitOwnerError):
            self.owner.run()
        self.assertEqual(3, self.ledger.totals.live)
        self.assertEqual([], self.wire.mock_calls)
        self.assertEqual([], child_wire.mock_calls)

    def test_malformed_wait_results_fail_without_access_or_credit_release(self):
        for result in (None, [11, 0], (11,), (True, 0), (0, 1), (-1, 0), (11, True)):
            self.setUp()
            self.wait.return_value = result
            with self.assertRaises(ValueError):
                self.owner.run()
            self.assertEqual(2, self.ledger.totals.live)
            self.assertEqual([], self.wire.mock_calls)
            self.assertIsNotNone(self.owner.stopped)

    def test_stop_returned_after_deadline_still_debits_before_reject(self):
        def wait(tid, options):
            self.fixture.clock.return_value = 100.0
            return (11, 0x857f)
        self.wait.side_effect = wait
        with self.assertRaises(ValueError):
            self.owner.run()
        self.assertEqual(2, self.ledger.totals.wait_stops)
        self.assertEqual([], self.wire.mock_calls)

    def test_foreign_owner_before_wait_or_after_observed_stop(self):
        self.fixture.system.getpid.return_value = 99
        with self.assertRaises(ValueError):
            self.owner.run()
        self.wait.assert_not_called()
        self.setUp()
        def wait(tid, options):
            self.fixture.system.getpid.return_value = 99
            return (11, 0x857f)
        self.wait.side_effect = wait
        with self.assertRaises(ValueError):
            self.owner.run()
        self.assertEqual(2, self.ledger.totals.wait_stops)
        self.assertEqual([], self.wire.mock_calls)

    def test_wait_cap_prevents_fact_read_and_resume(self):
        self.ledger._event_limit = 2
        self.wait.return_value = (11, 0x857f)
        with self.assertRaises(StopReadError):
            self.owner.run()
        self.assertEqual([], self.wire.mock_calls)
        self.assertEqual(2, self.ledger.totals.wait_stops)

    def test_exec_and_unknown_signal_fail_closed_without_next_wait(self):
        for status in (0x4057f, 0x0a7f):
            self.setUp()
            self.wait.return_value = (11, status)
            with self.assertRaises(ValueError):
                self.owner.run()
            self.wait.assert_called_once()
            self.wire.resume_syscall.assert_not_called()
            self.assertEqual(2, self.ledger.totals.live)

    def test_uncertain_resume_latches_all_owners_without_retry(self):
        self.wait.return_value = (11, 0x857f)
        self.wire.syscall_info.return_value = decode_syscall_info(*entry(39))
        self.wire.resume_syscall.side_effect = OSError('uncertain resume')
        with self.assertRaises(OSError):
            self.owner.run()
        self.assertEqual(1, self.wire.resume_syscall.call_count)
        self.assertEqual(self.owner.stopped, self.dispatch.stopped)
        self.assertEqual(self.owner.stopped, self.launch.stopped)
        with self.assertRaises(WaitOwnerError):
            self.owner.run()
        self.wait.assert_called_once()

    def test_second_wait_owner_or_reentry_is_forbidden(self):
        with self.assertRaises(WaitOwnerError):
            BoundedWaitOwner(self.dispatch, waitpid=self.wait, sleep=self.sleep)
        self.wait.return_value = (11, 0)
        self.owner.run()
        with self.assertRaises(WaitOwnerError):
            self.owner.run()
        self.wait.assert_called_once()

    def test_snapshot_contains_only_bound_births_and_no_tracer(self):
        self.fixture.syscall(entry(57))
        self.assertEqual(((1, 11),), self.launch.dispatcher.waiting_births())
        self.wire.event_tid.return_value = 12
        self.dispatch.accept(11, 0x1057f)
        self.assertEqual(((1, 11), (2, 12)), self.launch.dispatcher.waiting_births())

    def test_empty_binding_inventory_requires_drained_ledger(self):
        self.launch.dispatcher._tasks.clear()
        self.launch.dispatcher._sequence.clear()
        with self.assertRaisesRegex(CreationDispatchError, 'unreaped tasks'):
            self.owner.run()
        self.wait.assert_not_called()
        self.assertEqual(2, self.ledger.totals.live)

    def test_old_exec_tid_echild_defers_to_actual_tgid_event_without_refund(self):
        thread = self.fixture.exec_thread()
        self.owner._last_birth = 1  # Poll the disappearing executor first.
        before = self.ledger.totals
        thread.syscall_info.return_value = decode_syscall_info(*exit_info(0, 0))
        self.wait.side_effect = [ChildProcessError(errno.ECHILD, 'gone'),
                                 (11, 0x4057f), (11, 0x857f), (11, 0)]
        totals = self.owner.run()
        self.assertEqual([12, 11, 11, 11], [c.args[0] for c in self.wait.call_args_list])
        self.assertEqual(before.attempts, totals.attempts)
        self.assertEqual(before.wait_stops + 2, totals.wait_stops)
        self.assertEqual('exec-replaced', self.ledger.identity(1).terminal)
        self.assertEqual('reaped', self.ledger.identity(2).terminal)
        self.wire.resume_syscall.assert_not_called()
        self.assertEqual([call.resume_syscall(11, 0), call.syscall_info(11),
                          call.resume_syscall(11, 0)], thread.mock_calls)

    def test_old_exec_tid_echild_without_event_exhausts_original_deadline(self):
        self.fixture.exec_thread()
        self.owner._last_birth = 1
        before = self.ledger.totals
        self.wait.side_effect = [ChildProcessError(errno.ECHILD, 'gone'), (0, 0)]
        self.sleep.side_effect = lambda seconds: setattr(self.fixture.clock, 'return_value', 100.0)
        with self.assertRaisesRegex(ValueError, 'deadline'):
            self.owner.run()
        self.assertEqual(before, self.ledger.totals)
        self.assertEqual(12, self.ledger.identity(2).tid)
        self.assertTrue(self.ledger.identity(1).alive)

    def test_non_echild_or_nonexec_child_error_never_defers(self):
        self.fixture.exec_thread()
        self.owner._last_birth = 1
        self.wait.side_effect = ChildProcessError(errno.EPERM, 'denied')
        with self.assertRaises(ChildProcessError):
            self.owner.run()
        self.wait.assert_called_once_with(12, WAIT_OPTIONS)
        self.assertEqual(3, self.ledger.totals.live)


if __name__ == '__main__':
    unittest.main()
