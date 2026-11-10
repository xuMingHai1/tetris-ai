"""Synthetic cleanup waits only; no native signal, wait, fork or ptrace."""
import unittest
from unittest.mock import Mock, call

from fixed_witness_replay_cleanup_reap import CleanupReaper, CleanupReapError
from fixed_witness_replay_tasks import TaskAccountingError
from fixed_witness_replay_ptrace import decode_syscall_info
from fixed_witness_replay_wait_owner import WAIT_OPTIONS
from test_fixed_witness_replay_ptrace import entry, exit_info
import test_fixed_witness_replay_stop_dispatch as dispatch_fixture


class CleanupReaperTest(unittest.TestCase):
    def setUp(self, **limits):
        self.fixture = dispatch_fixture.PostHandshakeDispatchTest()
        self.fixture.setUp(**limits)
        self.launch = self.fixture.launch
        self.ledger = self.fixture.ledger
        self.wait = Mock(return_value=(11, 9))
        self.sleep = Mock()

    def owner(self):
        return CleanupReaper(self.launch, waitpid=self.wait, sleep=self.sleep)

    def stopped(self):
        self.launch.stopped = 'original failure'
        self.launch.reader.stopped = 'reader failure'
        self.launch.dispatcher.stopped = 'dispatch failure'
        self.launch.dispatcher.lifecycle.stopped = 'lifecycle failure'
        self.ledger._stopped = 'ledger failure'

    def test_failed_layers_reap_actual_terminal_without_clearing_any_latch(self):
        self.stopped()
        owner = self.owner()
        result = owner.run()
        self.assertEqual((1, 11, 'signaled'), (result[0][0], result[0][1], result[0][2].kind))
        self.assertEqual((2, 1, 0, 1), (self.ledger.totals.attempts, self.ledger.totals.live,
                                       self.ledger.totals.pending, self.ledger.totals.wait_stops))
        self.assertEqual('ledger failure', self.ledger.totals.stopped)
        self.assertEqual('original failure', self.launch.stopped)
        self.assertEqual('reader failure', self.launch.reader.stopped)
        self.wait.assert_called_once_with(11, WAIT_OPTIONS)
        self.assertEqual([], self.fixture.wire.mock_calls)
        self.assertEqual(11, self.launch.cleanup_tid)
        self.assertEqual('reaped', self.ledger.identity(1).terminal)
        with self.assertRaises(ValueError):
            self.fixture.owner.accept(11, 0x857f)

    def test_normal_owner_is_permanently_revoked_and_cleanup_is_one_shot(self):
        owner = self.owner()
        with self.assertRaises(ValueError):
            self.fixture.owner.accept(11, 0x857f)
        owner.run()
        with self.assertRaises(CleanupReapError):
            owner.run()
        with self.assertRaises(CleanupReapError):
            self.owner()
        self.wait.assert_called_once()

    def test_settled_process_tree_reaps_each_birth_once(self):
        child = self.fixture.child()
        self.fixture.wire.syscall_info.return_value = decode_syscall_info(*exit_info(12, 0))
        self.fixture.owner.accept(11, 0x857f)
        self.fixture.wire.reset_mock()
        child.reset_mock()
        self.stopped()
        self.wait.side_effect = [(11, 0), (12, 9)]
        result = self.owner().run()
        self.assertEqual([(1, 11), (2, 12)], [(b, tid) for b, tid, _ in result])
        self.assertEqual([call(11, WAIT_OPTIONS), call(12, WAIT_OPTIONS)], self.wait.call_args_list)
        self.assertEqual((3, 1), (self.ledger.totals.attempts, self.ledger.totals.live))
        self.assertEqual([], self.fixture.wire.mock_calls + child.mock_calls)

    def test_parent_initialization_failure_without_dispatcher_still_reaps(self):
        self.launch.dispatcher = None
        self.launch.stopped = 'wire construction failed'
        self.owner().run()
        self.assertEqual(1, self.ledger.totals.live)
        self.assertEqual('wire construction failed', self.launch.stopped)

    def test_idle_and_eintr_sleep_within_original_deadline(self):
        self.wait.side_effect = [InterruptedError(), (0, 0), (11, 9)]
        self.owner().run()
        self.assertEqual([call(0.01), call(0.01)], self.sleep.call_args_list)
        self.assertEqual(1, self.ledger.totals.wait_stops)

    def test_idle_deadline_exhaustion_never_signals_or_refunds(self):
        self.wait.return_value = (0, 0)
        self.fixture.clock.return_value = 99.997
        self.sleep.side_effect = lambda seconds: setattr(self.fixture.clock, 'return_value', 100.0)
        owner = self.owner()
        with self.assertRaisesRegex(CleanupReapError, 'deadline'):
            owner.run()
        self.assertAlmostEqual(0.003, self.sleep.call_args.args[0])
        self.wait.assert_called_once()
        self.assertEqual(2, self.ledger.totals.live)
        self.assertIsNotNone(owner.stopped)

    def test_observed_stop_after_deadline_still_charges_latched_ledger(self):
        self.stopped()
        def wait(tid, options):
            self.fixture.clock.return_value = 100.0
            return (11, 0x137f)
        self.wait.side_effect = wait
        owner = self.owner()
        with self.assertRaisesRegex(CleanupReapError, 'deadline'):
            owner.run()
        self.assertEqual(2, self.ledger.totals.wait_stops)
        self.assertEqual((1, (11, 0x137f)), owner.last_wait)
        self.assertEqual('ledger failure', self.ledger.totals.stopped)
        self.assertEqual([], self.fixture.wire.mock_calls)

    def test_observed_stop_after_owner_change_still_charges(self):
        def wait(tid, options):
            self.fixture.system.getpid.return_value = 99
            return (11, 0x857f)
        self.wait.side_effect = wait
        with self.assertRaisesRegex(CleanupReapError, 'owner'):
            self.owner().run()
        self.assertEqual(2, self.ledger.totals.wait_stops)

    def test_stop_rejected_without_ptrace_or_resume(self):
        self.stopped()
        self.wait.return_value = (11, 0x137f)
        with self.assertRaisesRegex(CleanupReapError, 'termination remains'):
            self.owner().run()
        self.assertEqual((2, 2), (self.ledger.totals.live, self.ledger.totals.wait_stops))
        self.assertEqual([], self.fixture.wire.mock_calls)

    def test_exhausted_shared_stop_budget_is_not_replaced(self):
        self.setUp(wait_stops=2)
        self.stopped()
        self.wait.return_value = (11, 0x137f)
        with self.assertRaises(TaskAccountingError):
            self.owner().run()
        self.assertEqual(2, self.ledger.totals.wait_stops)
        self.assertEqual('ledger failure', self.ledger.totals.stopped)

    def test_wrong_target_stop_charged_and_terminal_never_settles(self):
        for raw, expected in ((0x137f, 2), (0, 1)):
            self.setUp()
            self.wait.return_value = (99, raw)
            with self.assertRaisesRegex(CleanupReapError, 'target mismatch'):
                self.owner().run()
            self.assertEqual((2, expected), (self.ledger.totals.live, self.ledger.totals.wait_stops))

    def test_malformed_results_never_release_credit(self):
        for result in (None, [11, 0], (11,), (True, 0), (11, True), (0, 1), (11, 0xffff)):
            self.setUp()
            self.wait.return_value = result
            with self.assertRaises(ValueError):
                self.owner().run()
            self.assertEqual(2, self.ledger.totals.live)
            self.wait.assert_called_once()

    def test_echild_and_native_error_never_infer_reap_or_retry(self):
        for error in (ChildProcessError(), OSError('wait failed')):
            self.setUp()
            self.wait.side_effect = error
            owner = self.owner()
            with self.assertRaises(OSError):
                owner.run()
            with self.assertRaises(CleanupReapError):
                owner.run()
            self.wait.assert_called_once()
            self.assertEqual(2, self.ledger.totals.live)

    def test_unresolved_creation_refuses_false_drained_claim(self):
        self.fixture.syscall(entry(57))
        self.stopped()
        with self.assertRaisesRegex(CleanupReapError, 'unresolved creation'):
            self.owner().run()
        self.wait.assert_not_called()
        self.assertEqual((3, 1), (self.ledger.totals.live, self.ledger.totals.pending))

    def test_thread_group_or_exec_migration_refuses_ambiguous_reap(self):
        reservation = self.ledger.reserve(1, 'clone', thread=True)
        self.ledger.created(reservation, 12)
        self.ledger.finish(reservation, 12)
        self.launch.dispatcher._bind(reservation, 12, newborn=True)
        self.stopped()
        with self.assertRaisesRegex(CleanupReapError, 'thread/exec'):
            self.owner().run()
        self.wait.assert_not_called()
        self.assertEqual(3, self.ledger.totals.live)

    def test_foreign_owner_or_expired_deadline_prevents_wait(self):
        for change in ('owner', 'thread', 'deadline'):
            self.setUp()
            if change == 'owner':
                self.fixture.system.getpid.return_value = 99
            elif change == 'thread':
                self.fixture.system.listdir.return_value = ['10', '20']
            else:
                self.fixture.clock.return_value = 100.0
            with self.assertRaises(CleanupReapError):
                self.owner()
            self.wait.assert_not_called()


if __name__ == '__main__':
    unittest.main()
