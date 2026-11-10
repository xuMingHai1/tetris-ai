"""Injected pidfd/kill/wait only; never signals or creates a native task."""
import unittest
from unittest.mock import Mock, call

from fixed_witness_replay_settled_cleanup import SettledProcessCleanup
from fixed_witness_replay_cleanup_reap import CleanupReapError
from fixed_witness_replay_ptrace import decode_syscall_info
from fixed_witness_replay_wait_owner import WAIT_OPTIONS
from test_fixed_witness_replay_ptrace import entry, exit_info
import test_fixed_witness_replay_cleanup_reap as reap_fixture


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

    def test_partial_acquisition_failure_closes_prior_pin_without_kill_or_wait(self):
        self.tree()
        self.transport.open.side_effect = [70, ProcessLookupError('gone')]
        owner = self.owner()
        with self.assertRaises(ProcessLookupError):
            owner.run()
        self.assertEqual([call.open(11, 0), call.open(12, 0), call.close(70)],
                         self.transport.mock_calls)
        self.assertEqual(3, self.ledger.totals.live)
        self.assertEqual([], owner.signalled)

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
        self.transport.wait.assert_not_called()
        self.assertEqual(3, self.ledger.totals.live)

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

    def test_unresolved_creation_or_threads_reject_before_acquisition(self):
        for kind in ('pending', 'thread'):
            with self.subTest(kind=kind):
                self.setUp()
                if kind == 'pending':
                    self.fixture.fixture.syscall(entry(57))
                else:
                    ticket = self.ledger.reserve(1, 'clone', thread=True)
                    self.ledger.created(ticket, 12)
                    self.ledger.finish(ticket, 12)
                    self.launch.dispatcher._bind(ticket, 12, newborn=True)
                self.fixture.stopped()
                with self.assertRaises(CleanupReapError):
                    self.owner().run()
                self.assertEqual([], self.transport.mock_calls)

    def test_pending_stop_after_kill_debits_shared_budget_and_fails_without_resume(self):
        self.fixture.stopped()
        self.transport.wait.return_value = (11, 0x137f)
        with self.assertRaisesRegex(CleanupReapError, 'observed stop'):
            self.owner().run()
        self.assertEqual((2, 2), (self.ledger.totals.live, self.ledger.totals.wait_stops))
        self.transport.close.assert_called_once_with(70)
        self.assertEqual([], self.fixture.fixture.wire.mock_calls)

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


if __name__ == '__main__':
    unittest.main()
