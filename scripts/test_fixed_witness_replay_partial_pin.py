"""Real cleanup composition, injected kernel transports only; no native tasks."""
import unittest
from unittest.mock import Mock, call

from fixed_witness_replay_cleanup_reap import CleanupReapError, CleanupReaper
from fixed_witness_replay_ptrace import SignalInfo, decode_syscall_info
from fixed_witness_replay_wait_owner import WAIT_OPTIONS
from test_fixed_witness_replay_ptrace import entry, exit_info
import test_fixed_witness_replay_settled_cleanup as settled
import test_fixed_witness_replay_thread_cleanup as threaded
import test_fixed_witness_replay_supervisor as supervisor


class PartialPinTest(unittest.TestCase):
    def setUp(self):
        self.f = settled.SettledCleanupTest()
        self.f.setUp()
        self.f.tree()
        self.f.fixture.stopped()
        self.original = OSError('second group pin failed')
        self.f.transport.open.side_effect = [70, self.original]

    def attempt(self):
        owner = self.f.owner()
        with self.assertRaises(OSError) as raised:
            owner.run()
        self.assertIs(raised.exception, self.original)
        self.assertIsNotNone(owner.stopped)
        self.assertTrue(self.f.ledger.identity(2).alive)
        self.assertEqual(2, self.f.transport.open.call_count)
        self.f.transport.close.assert_called_once_with(70)
        return owner

    def test_reap_and_original_failure_both_retained(self):
        owner = self.attempt()
        self.assertEqual([(1, 11)], [(b, tid) for b, tid, _ in owner.partial_reaped])
        self.assertEqual('reaped', self.f.ledger.identity(1).terminal)
        with self.assertRaises(CleanupReapError):
            owner.run()
        self.assertEqual([], self.f.fixture.fixture.wire.mock_calls)

    def test_failed_partial_signal_does_not_wait_or_replace_original(self):
        failure = InterruptedError('uncertain signal')
        self.f.transport.send.side_effect = failure
        owner = self.attempt()
        self.assertIs(owner.partial_error, failure)
        self.f.transport.send.assert_called_once_with(70, 9, None, 0)
        self.f.transport.wait.assert_not_called()
        self.assertTrue(self.f.ledger.identity(1).alive)

    def test_echild_never_counts_as_reap(self):
        failure = ChildProcessError('unknown terminal')
        self.f.transport.wait.side_effect = failure
        owner = self.attempt()
        self.assertIs(owner.partial_error, failure)
        self.assertTrue(self.f.ledger.identity(1).alive)

    def test_unselected_terminal_is_quarantined_without_releasing_credit(self):
        self.f.transport.wait.return_value = (12, 9)
        owner = self.attempt()
        self.assertIsInstance(owner.partial_error, CleanupReapError)
        self.assertIn(12, self.f.launch.unsettled_terminal_waits)
        self.assertEqual(3, self.f.ledger.totals.live)

    def test_deadline_at_failed_open_prevents_partial_signal(self):
        def acquire(tid, flags):
            if tid == 11:
                return 70
            self.f.fixture.fixture.clock.return_value = 100.0
            raise self.original
        self.f.transport.open.side_effect = acquire
        owner = self.attempt()
        self.assertIsInstance(owner.partial_error, CleanupReapError)
        self.f.transport.send.assert_not_called()
        self.f.transport.wait.assert_not_called()

    def test_inventory_drift_at_failed_open_prevents_partial_signal(self):
        def acquire(tid, flags):
            if tid == 11:
                return 70
            self.f.ledger._tasks[2].tid = 99
            raise self.original
        self.f.transport.open.side_effect = acquire
        owner = self.attempt()
        self.assertIsNotNone(owner.partial_error)
        self.f.transport.send.assert_not_called()
        self.f.transport.wait.assert_not_called()

    def test_unselected_birth_drift_during_partial_wait_is_not_success(self):
        def wait(tid, flags):
            self.f.ledger._tasks[2].tid = 99
            return tid, 9
        self.f.transport.wait.side_effect = wait
        owner = self.attempt()
        self.assertIsNotNone(owner.partial_error)
        self.assertEqual(1, len(owner.reaper.reaped))
        self.assertEqual((), owner.partial_reaped)

    def test_partial_stops_use_original_shared_budget(self):
        self.f.ledger._event_limit = self.f.ledger.totals.wait_stops + 1
        self.f.transport.wait.return_value = (11, 0x137f)
        before = self.f.ledger.totals
        owner = self.attempt()
        self.assertIsNotNone(owner.partial_error)
        self.assertEqual(before.wait_stops + 1, self.f.ledger.totals.wait_stops)
        self.assertEqual(before.stopped, self.f.ledger.totals.stopped)
        self.assertEqual(before.attempts, self.f.ledger.totals.attempts)

    def test_full_selected_thread_group_reaps_every_member(self):
        f = threaded.ThreadCleanupTest()
        f.setUp()
        f.launch.dispatcher._factory = Mock(return_value=Mock())
        f.f.syscall(entry(57))
        f.f.wire.event_tid.return_value = 13
        f.f.owner.accept(11, 0x1057f)
        f.f.syscall(exit_info(13, 0))
        f.stop()
        f.transport.open.side_effect = [70, self.original]
        owner = f.owner()
        with self.assertRaises(OSError) as raised:
            owner.run()
        self.assertIs(raised.exception, self.original)
        self.assertEqual([(1, 11), (2, 12)], [(b, tid) for b, tid, _ in owner.partial_reaped])
        self.assertEqual([call(11, WAIT_OPTIONS), call(12, WAIT_OPTIONS)],
                         f.transport.wait.call_args_list)
        self.assertTrue(f.ledger.identity(3).alive)
        self.assertIsNone(owner.partial_error)

    def test_partial_close_error_retains_original_and_actual_reaps(self):
        failure = OSError('close failed')
        self.f.transport.close.side_effect = failure
        owner = self.attempt()
        self.assertIs(owner.close_errors[0][2], failure)
        self.assertEqual(1, len(owner.partial_reaped))

    def test_supervisor_keeps_normal_and_pin_failures_after_partial_reap(self):
        f = supervisor.SupervisorTest()
        f.setUp()
        normal = OSError('normal wait failed')
        f.f.access.syscall_info.side_effect = [decode_syscall_info(*entry(57)),
                                              decode_syscall_info(*exit_info(12, 0))]
        f.f.access.event_tid.return_value = 12
        f.f.factory.return_value.signal_info.return_value = SignalInfo(19, 0, 0, 0, 0)
        f.io.wait.side_effect = [(11, 0x137f), (11, 0x857f), (11, 0x1057f),
                                 (12, 0x137f), (11, 0x857f), normal, (11, 9)]
        f.io.open.side_effect = [70, self.original]
        owner = f.owner()
        with self.assertRaises(OSError) as raised:
            owner.run()
        self.assertIs(raised.exception, normal)
        self.assertIs(owner.failure, normal)
        self.assertIs(owner.cleanup_error, self.original)
        self.assertFalse(owner.drained)
        self.assertEqual(1, len(owner.cleanup.partial_reaped))
        self.assertEqual(2, f.ledger.totals.live)

    def test_partial_selection_cannot_split_thread_group(self):
        f = threaded.ThreadCleanupTest()
        f.setUp()
        f.stop()
        owner = CleanupReaper(f.launch, waitpid=f.transport.wait)
        with self.assertRaisesRegex(CleanupReapError, 'complete pinned groups'):
            owner._after_pidfd_kill((f.ledger.identity(1),), partial=True)
        f.transport.wait.assert_not_called()
        self.assertEqual((), owner._killed_births)

    def test_partial_selection_cannot_be_empty_or_duplicate(self):
        owner = CleanupReaper(self.f.launch, waitpid=self.f.transport.wait)
        identity = self.f.ledger.identity(1)
        for selected in ((), (identity, identity)):
            with self.subTest(selected=selected), self.assertRaises(CleanupReapError):
                owner._after_pidfd_kill(selected, partial=True)
        self.f.transport.wait.assert_not_called()


if __name__ == '__main__':
    unittest.main()
