"""Injected pidfd/kill/wait only; never signals or creates a native task."""
import unittest
from unittest.mock import Mock, call

from fixed_witness_replay_settled_cleanup import SettledProcessCleanup
from fixed_witness_replay_cleanup_reap import CleanupReapError
from fixed_witness_replay_tasks import TaskAccountingError
from fixed_witness_replay_ptrace import decode_syscall_info
from fixed_witness_replay_wait_owner import WAIT_OPTIONS
from fixed_witness_replay_wait_owner import BoundedWaitOwner
from test_fixed_witness_replay_ptrace import entry, exit_info
import test_fixed_witness_replay_cleanup_reap as reap_fixture
import test_fixed_witness_replay_launch as launch_fixture


class SettledCleanupTest(unittest.TestCase):
    def setUp(self):
        self.fixture = reap_fixture.CleanupReaperTest()
        self.fixture.setUp()
        self.launch, self.ledger = self.fixture.launch, self.fixture.ledger
        self.transport = Mock()
        self.transport.open.side_effect = [70, 71]
        self.transport.send.return_value = None
        self.transport.wait.return_value = (11, 9)
        self.cleanup = None

    def owner(self):
        self.cleanup = SettledProcessCleanup(
            self.launch, open_pidfd=self.transport.open, send_signal=self.transport.send,
            close=self.transport.close, waitpid=self.transport.wait, sleep=self.fixture.sleep)
        return self.cleanup

    def tree(self):
        self.fixture.fixture.child()
        self.fixture.fixture.wire.syscall_info.return_value = decode_syscall_info(*exit_info(12, 0))
        self.fixture.fixture.owner.accept(11, 0x857f)
        self.fixture.fixture.wire.reset_mock()

    def test_kill_pinned_birth_then_reap_preserves_failures_and_attempts(self):
        self.fixture.stopped()
        self.launch.cleanup_tid = 999  # Never a signal target or identity proof.
        result = self.owner().run()
        self.assertEqual((1, 11, 'signaled'), (result[0][0], result[0][1], result[0][2].kind))
        self.assertEqual([call.open(11, 0), call.send(70, 9, None, 0),
                          call.wait(11, WAIT_OPTIONS), call.close(70)], self.transport.mock_calls)
        self.assertEqual([1], self.cleanup.signalled)
        self.assertEqual((2, 1, 0, 1), (self.ledger.totals.attempts, self.ledger.totals.live,
                                       self.ledger.totals.pending, self.ledger.totals.wait_stops))
        self.assertEqual('ledger failure', self.ledger.totals.stopped)
        self.assertEqual('original failure', self.launch.stopped)
        self.assertEqual('reader failure', self.launch.reader.stopped)
        self.assertEqual([], self.fixture.fixture.wire.mock_calls)

    def test_all_tree_pidfds_acquired_before_any_signal_or_wait(self):
        self.tree()
        self.fixture.stopped()
        self.transport.wait.side_effect = [(11, 9), (12, 9)]
        self.owner().run()
        self.assertEqual([call.open(11, 0), call.open(12, 0),
                          call.send(70, 9, None, 0), call.send(71, 9, None, 0),
                          call.wait(11, WAIT_OPTIONS), call.wait(12, WAIT_OPTIONS),
                          call.close(71), call.close(70)], self.transport.mock_calls)
        self.assertEqual((3, 1), (self.ledger.totals.attempts, self.ledger.totals.live))

    def test_partial_acquisition_failure_kills_and_reaps_prior_group_without_retry(self):
        self.tree()
        self.transport.open.side_effect = [70, ProcessLookupError('gone')]
        owner = self.owner()
        with self.assertRaises(ProcessLookupError):
            owner.run()
        self.assertEqual([call.open(11, 0), call.open(12, 0), call.close(70)],
                         [c for c in self.transport.mock_calls if c[0] in ('open', 'close')])
        self.transport.send.assert_called_once_with(70, 9, None, 0)
        self.transport.wait.assert_called_once_with(11, WAIT_OPTIONS)
        self.assertEqual(2, self.ledger.totals.live)
        self.assertEqual([1], owner.signalled)
        self.assertEqual([(1, 11)], [(b, tid) for b, tid, _ in owner.partial_reaped])
        self.assertIsNone(owner.partial_error)
        self.assertTrue(self.ledger.identity(2).alive)

    def test_signal_error_or_uncertain_result_never_retries_or_refunds(self):
        for failure in (ProcessLookupError('gone'), PermissionError('denied'),
                        InterruptedError('uncertain'), 0):
            with self.subTest(failure=failure):
                self.setUp()
                if isinstance(failure, BaseException):
                    self.transport.send.side_effect = failure
                else:
                    self.transport.send.return_value = failure
                owner = self.owner()
                with self.assertRaises((OSError, CleanupReapError)):
                    owner.run()
                with self.assertRaises(CleanupReapError):
                    owner.run()
                self.transport.send.assert_called_once_with(70, 9, None, 0)
                self.transport.close.assert_called_once_with(70)
                self.transport.wait.assert_not_called()
                self.assertEqual(2, self.ledger.totals.live)

    def test_partial_signal_failure_retains_each_attempted_identity(self):
        self.tree()
        self.transport.send.side_effect = [None, OSError('second kill failed')]
        owner = self.owner()
        with self.assertRaises(OSError):
            owner.run()
        self.assertEqual([1], owner.signalled)
        self.assertEqual([1, 2], [item.birth for item, _ in owner.pinned])
        self.assertEqual([call(71), call(70)], self.transport.close.call_args_list)
        self.transport.wait.assert_called_once_with(11, WAIT_OPTIONS)
        self.assertEqual([(1, 11)], [(b, tid) for b, tid, _ in owner.partial_reaped])
        self.assertEqual(2, self.ledger.totals.live)

    def test_expiry_during_pin_closes_fd_and_never_sends(self):
        def acquire(tid, flags):
            self.fixture.fixture.clock.return_value = 100.0
            return 70
        self.transport.open.side_effect = acquire
        with self.assertRaisesRegex(CleanupReapError, 'deadline'):
            self.owner().run()
        self.transport.close.assert_called_once_with(70)
        self.transport.send.assert_not_called()
        self.transport.wait.assert_not_called()

    def test_expiry_during_signal_prevents_wait_but_releases_pin(self):
        def send(*args):
            self.fixture.fixture.clock.return_value = 100.0
        self.transport.send.side_effect = send
        with self.assertRaisesRegex(CleanupReapError, 'deadline'):
            self.owner().run()
        self.assertEqual([1], self.cleanup.signalled)
        self.transport.close.assert_called_once_with(70)
        self.transport.wait.assert_not_called()
        self.assertEqual(2, self.ledger.totals.live)

    def test_inventory_mutation_or_foreign_owner_after_pin_prevents_signal(self):
        for mutation in ('birth', 'owner'):
            with self.subTest(mutation=mutation):
                self.setUp()
                def acquire(tid, flags):
                    if mutation == 'birth':
                        self.ledger.reaped(1)
                    else:
                        self.fixture.fixture.system.getpid.return_value = 99
                    return 70
                self.transport.open.side_effect = acquire
                with self.assertRaises(CleanupReapError):
                    self.owner().run()
                self.transport.send.assert_not_called()
                self.transport.wait.assert_not_called()
                self.transport.close.assert_called_once_with(70)

    def test_unresolved_creation_or_pending_threads_reject_before_acquisition(self):
        for kind in ('pending', 'thread'):
            with self.subTest(kind=kind):
                self.setUp()
                if kind == 'pending':
                    self.fixture.fixture.syscall(entry(57))
                else:
                    ticket = self.ledger.reserve(1, 'clone', thread=True)
                    self.ledger.created(ticket, 12)
                    self.launch.dispatcher._bind(ticket, 12, newborn=True)
                self.fixture.stopped()
                with self.assertRaises(CleanupReapError):
                    self.owner().run()
                self.assertEqual([call.wait(11, WAIT_OPTIONS)] if kind == 'pending' else [],
                                 self.transport.mock_calls)

    def test_pending_stops_after_kill_debit_shared_budget_without_resume(self):
        self.fixture.stopped()
        self.transport.wait.side_effect = [(11, 0x137f), (11, 0x857f), (11, 9)]
        owner = self.owner()
        result = owner.run()
        self.assertEqual((1, 3), (self.ledger.totals.live, self.ledger.totals.wait_stops))
        self.assertEqual(2, owner.reaper.drained_stops)
        self.assertEqual('syscall-stop', owner.reaper.last_drained_stop[2].kind)
        self.assertEqual('signaled', result[0][2].kind)
        self.assertEqual([call(0.01), call(0.01)], self.fixture.sleep.call_args_list)
        self.transport.close.assert_called_once_with(70)
        self.assertEqual([], self.fixture.fixture.wire.mock_calls)

    def test_killed_stop_drain_remains_round_robin_across_processes(self):
        self.tree()
        self.transport.wait.side_effect = [(11, 0x857f), (12, 9), (11, 9)]
        owner = self.owner()
        result = owner.run()
        self.assertEqual([(2, 12), (1, 11)], [(b, t) for b, t, _ in result])
        self.assertEqual([call(11, WAIT_OPTIONS), call(12, WAIT_OPTIONS),
                          call(11, WAIT_OPTIONS)], self.transport.wait.call_args_list)
        self.assertEqual(1, owner.reaper.drained_stops)
        self.assertEqual([], self.fixture.fixture.wire.mock_calls)

    def test_ptrace_events_after_kill_remain_unhandled_and_fail_closed(self):
        for event in (1, 2, 3, 4):
            with self.subTest(event=event):
                self.setUp()
                self.transport.wait.return_value = (11, (event << 16) | 0x057f)
                owner = self.owner()
                with self.assertRaisesRegex(CleanupReapError, 'observed stop'):
                    owner.run()
                self.assertEqual((2, 2), (self.ledger.totals.live, self.ledger.totals.wait_stops))
                self.assertEqual(0, owner.reaper.drained_stops)
                self.assertEqual([], self.fixture.fixture.wire.mock_calls)

    def test_wrong_target_or_invalid_stop_cannot_enter_drain(self):
        for tid, raw in ((99, 0x137f), (11, 0x7f), (11, 0x99057f)):
            self.setUp()
            self.transport.wait.return_value = (tid, raw)
            owner = self.owner()
            with self.assertRaises(ValueError):
                owner.run()
            self.assertEqual(2, self.ledger.totals.wait_stops)
            self.assertEqual(0, owner.reaper.drained_stops)
            self.assertEqual(2, self.ledger.totals.live)

    def test_queued_stop_after_deadline_is_charged_but_not_drained(self):
        def wait(*args):
            self.fixture.fixture.clock.return_value = 100.0
            return (11, 0x857f)
        self.transport.wait.side_effect = wait
        owner = self.owner()
        with self.assertRaisesRegex(CleanupReapError, 'deadline'):
            owner.run()
        self.assertEqual(2, self.ledger.totals.wait_stops)
        self.assertEqual(0, owner.reaper.drained_stops)
        self.assertEqual(2, self.ledger.totals.live)

    def test_queued_stops_cannot_allocate_a_new_budget(self):
        self.fixture.setUp(wait_stops=2)
        self.launch, self.ledger = self.fixture.launch, self.fixture.ledger
        self.fixture.stopped()
        self.transport.wait.return_value = (11, 0x857f)
        owner = self.owner()
        with self.assertRaises(TaskAccountingError):
            owner.run()
        self.assertEqual(0, owner.reaper.drained_stops)
        self.assertEqual(2, self.ledger.totals.wait_stops)
        self.assertEqual(2, self.ledger.totals.live)
        self.assertEqual('ledger failure', self.ledger.totals.stopped)

    def test_endless_stop_stream_expires_on_original_deadline(self):
        self.transport.wait.return_value = (11, 0x137f)
        self.fixture.sleep.side_effect = lambda seconds: setattr(
            self.fixture.fixture.clock, 'return_value', 100.0)
        owner = self.owner()
        with self.assertRaisesRegex(CleanupReapError, 'deadline'):
            owner.run()
        self.transport.wait.assert_called_once()
        self.transport.send.assert_called_once()
        self.assertEqual(2, self.ledger.totals.live)

    def test_terminal_consumed_at_cleanup_expiry_is_quarantined(self):
        def wait(*args):
            self.fixture.fixture.clock.return_value = 100.0
            return (11, 9)
        self.transport.wait.side_effect = wait
        with self.assertRaisesRegex(CleanupReapError, 'deadline'):
            self.owner().run()
        self.assertEqual({11}, self.launch.unsettled_terminal_waits)
        self.assertEqual(2, self.ledger.totals.live)

    def test_mismatched_terminal_and_failed_reap_keep_consumed_marker(self):
        for failure in ('target', 'reap'):
            self.setUp()
            self.transport.wait.return_value = (99 if failure == 'target' else 11, 9)
            if failure == 'reap':
                self.launch.dispatcher.reaped = Mock(side_effect=ValueError('failed reap'))
            with self.assertRaises(ValueError):
                self.owner().run()
            self.assertEqual({99 if failure == 'target' else 11},
                             self.launch.unsettled_terminal_waits)
            self.assertEqual(2, self.ledger.totals.live)

    def test_cleanup_authenticated_terminal_clears_only_its_marker(self):
        self.launch.unsettled_terminal_waits.add(99)
        self.owner().run()
        self.assertEqual({99}, self.launch.unsettled_terminal_waits)

    def test_changed_birth_during_queued_stop_is_not_drained(self):
        def wait(*args):
            self.ledger.reaped(1)
            return (11, 0x857f)
        self.transport.wait.side_effect = wait
        owner = self.owner()
        with self.assertRaisesRegex(CleanupReapError, 'birth changed'):
            owner.run()
        self.assertEqual(0, owner.reaper.drained_stops)

    def test_echild_after_signal_is_not_reap(self):
        self.transport.wait.side_effect = ChildProcessError('not reaped here')
        with self.assertRaises(ChildProcessError):
            self.owner().run()
        self.assertEqual(2, self.ledger.totals.live)
        self.transport.close.assert_called_once_with(70)

    def test_descriptor_close_error_does_not_hide_primary_error_or_retry(self):
        self.transport.send.side_effect = PermissionError('primary failure')
        self.transport.close.side_effect = InterruptedError('close uncertain')
        owner = self.owner()
        with self.assertRaisesRegex(PermissionError, 'primary failure'):
            owner.run()
        self.assertEqual(1, len(owner.close_errors))
        self.transport.close.assert_called_once_with(70)

    def test_descriptor_close_error_after_reap_is_incomplete(self):
        self.transport.close.side_effect = OSError('close failed')
        owner = self.owner()
        with self.assertRaisesRegex(CleanupReapError, 'descriptor release'):
            owner.run()
        self.assertEqual(1, self.ledger.totals.live)
        self.assertEqual('reaped', self.ledger.identity(1).terminal)
        self.assertEqual(1, len(owner.close_errors))

    def test_invalid_or_duplicate_descriptor_never_signals_and_never_double_closes(self):
        for invalid in (True, -1, None, 2**31, 70):
            with self.subTest(invalid=invalid):
                self.setUp()
                self.tree()
                self.transport.open.side_effect = [70, invalid]
                with self.assertRaises(CleanupReapError):
                    self.owner().run()
                self.transport.send.assert_not_called()
                self.transport.wait.assert_not_called()
                self.transport.close.assert_called_once_with(70)

    def test_unadmitted_or_unbound_launch_rejects_before_pidfd_and_empty_is_supported(self):
        for missing in ('admitted', 'dispatcher'):
            self.setUp()
            setattr(self.launch, missing, False if missing == 'admitted' else None)
            with self.assertRaisesRegex(CleanupReapError, 'authenticated traced launch'):
                self.owner()
            self.assertEqual([], self.transport.mock_calls)
        self.setUp()
        self.fixture.fixture.owner.accept(11, 9)
        self.assertEqual((), self.owner().run())
        self.assertEqual([], self.transport.mock_calls)

    def test_takeover_revokes_normal_work_and_second_cleanup_owner(self):
        owner = self.owner()
        with self.assertRaises(ValueError):
            self.fixture.fixture.owner.accept(11, 0x857f)
        with self.assertRaises(CleanupReapError):
            self.owner()
        owner.run()
        with self.assertRaises(CleanupReapError):
            owner.run()
        self.transport.send.assert_called_once()

    def test_failed_actual_initial_admission_never_opens_a_possibly_reaped_pid(self):
        for raw in (0, 9, 0x857f):
            with self.subTest(raw=raw):
                self.setUp()
                fresh = launch_fixture.CoordinatorLaunchTest()
                fresh.setUp()
                fresh.launcher.launch()
                with self.assertRaises(ValueError):
                    fresh.launcher.accept_initial_wait(11, raw)
                self.assertTrue(fresh.launcher.admitted)  # Attempted, NOT completed.
                self.assertFalse(fresh.launcher.handshake.completed)
                self.launch = fresh.launcher
                with self.assertRaisesRegex(CleanupReapError, 'authenticated traced launch'):
                    self.owner()
                self.assertEqual([], self.transport.mock_calls)

    def test_consumed_terminal_rejected_after_handshake_never_opens_stale_pid(self):
        self.fixture.stopped()
        with self.assertRaises(ValueError):
            self.fixture.fixture.owner.accept(11, 0)
        self.assertEqual({11}, self.launch.unsettled_terminal_waits)
        self.assertTrue(self.ledger.identity(1).alive)
        with self.assertRaisesRegex(CleanupReapError, 'consumed terminal'):
            self.owner().run()
        self.assertEqual([], self.transport.mock_calls)

    def test_actual_wait_mismatched_terminal_retained_before_dispatch_rejection(self):
        def wait(*args):
            return (11, 0)
        owner = BoundedWaitOwner(self.fixture.fixture.owner, waitpid=wait)
        # Force target validation failure AFTER the native wait consumed PID11.
        self.launch.dispatcher.waiting_births = Mock(return_value=((1, 99),))
        with self.assertRaises(ValueError):
            owner.run()
        self.assertEqual({11}, self.launch.unsettled_terminal_waits)
        with self.assertRaisesRegex(CleanupReapError, 'consumed terminal'):
            self.owner().run()
        self.assertEqual([], self.transport.mock_calls)

    def test_successful_authenticated_reap_clears_only_its_terminal_marker(self):
        self.tree()
        self.launch.unsettled_terminal_waits.add(99)
        self.fixture.fixture.owner.accept(12, 9)
        self.assertEqual({99}, self.launch.unsettled_terminal_waits)
        self.owner().run()
        self.transport.open.assert_called_once_with(11, 0)


if __name__ == '__main__':
    unittest.main()
