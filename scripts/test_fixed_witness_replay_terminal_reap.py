"""Fake wait/ptrace only: retained reap provenance, never native probes."""
from dataclasses import FrozenInstanceError, replace
import errno
import unittest
from unittest.mock import Mock

from fixed_witness_replay_wait_owner import BoundedWaitOwner, WaitOwnerError, WAIT_OPTIONS
from fixed_witness_replay_ptrace import decode_syscall_info
from test_fixed_witness_replay_ptrace import exit_info
import test_fixed_witness_replay_stop_dispatch as fixture_module


class SettledReapTest(unittest.TestCase):
    def setUp(self):
        fixture = fixture_module.PostHandshakeDispatchTest()
        fixture.setUp()
        self.fixture = fixture
        self.dispatch, self.launch, self.ledger = fixture.owner, fixture.launch, fixture.ledger
        self.wait, self.sleep = Mock(), Mock()
        self.owner = BoundedWaitOwner(self.dispatch, waitpid=self.wait, sleep=self.sleep)

    def reap(self, raw=31):
        self.wait.return_value = (11, raw)
        before = self.ledger.identity(1)
        totals = self.owner.run()
        record, = self.owner.settled_reaps
        self.assertEqual(before, record.before)
        self.assertEqual(self.ledger.identity(1), record.after)
        self.assertEqual((False, 'reaped'), (record.after.alive, record.after.terminal))
        self.assertEqual(1, totals.live)
        self.assertIsNone(self.owner.require_settled_reap(record))
        return record

    def test_exit_and_signaled_core_variants_preserve_exact_bits_without_extra_work(self):
        for raw in (0, 7 << 8, 31, 31 | 0x80, 9):
            with self.subTest(raw=raw):
                self.setUp()
                record = self.reap(raw)
                self.assertEqual(raw, record.raw)
                self.assertEqual(bool(raw & 0x80), record.status.core_dumped)
                self.wait.assert_called_once_with(11, WAIT_OPTIONS)
                self.sleep.assert_not_called()
                self.assertEqual([], self.fixture.wire.mock_calls)
                self.assertEqual(1, self.ledger.totals.wait_stops)
                self.assertEqual(2, self.ledger.totals.attempts)

    def test_snapshots_are_immutable_and_cannot_extend_owner_inventory(self):
        empty = self.owner.settled_reaps
        record = self.reap()
        with self.assertRaises(FrozenInstanceError):
            record.raw = 9
        with self.assertRaises(FrozenInstanceError):
            record.before.tid = 99
        self.assertEqual((), empty)
        self.assertEqual((record,), self.owner.settled_reaps)
        self.owner.require_settled_reap(record)  # Read validation is not consumption.

    def test_equal_copy_or_raw_value_has_no_provenance_and_latches_failure(self):
        for form in ('copy', 'tuple', 'none'):
            self.setUp()
            record = self.reap()
            untrusted = {'copy': replace(record), 'tuple': (11, 31), 'none': None}[form]
            with self.assertRaises(WaitOwnerError):
                self.owner.require_settled_reap(untrusted)
            with self.assertRaises(WaitOwnerError):
                self.owner.require_settled_reap(record)
            self.assertEqual((record,), self.owner.settled_reaps)
            self.wait.assert_called_once()

    def test_another_owner_cannot_validate_a_record_with_equal_birth_and_status(self):
        first = self.reap()
        self.setUp()
        second = self.reap()
        self.assertEqual(first, second)
        with self.assertRaises(WaitOwnerError):
            self.owner.require_settled_reap(first)

    def test_raw_dispatch_terminal_never_creates_an_owned_record(self):
        self.dispatch.accept(11, 31)
        self.owner.run()
        self.assertEqual((), self.owner.settled_reaps)
        self.wait.assert_not_called()
        self.assertEqual(1, self.ledger.totals.live)

    def test_wait_error_and_echild_never_issue_a_reap(self):
        for error in (ChildProcessError(errno.ECHILD, 'gone'), OSError('wait failed')):
            self.setUp()
            self.wait.side_effect = error
            with self.assertRaises(OSError):
                self.owner.run()
            self.assertEqual((), self.owner.settled_reaps)
            self.assertEqual(2, self.ledger.totals.live)
            self.wait.assert_called_once()

    def test_wrong_target_terminal_is_quarantined_without_settlement(self):
        self.fixture.child()
        self.wait.return_value = (12, 31)
        with self.assertRaises(WaitOwnerError):
            self.owner.run()
        self.assertEqual((), self.owner.settled_reaps)
        self.assertEqual({12}, self.launch.unsettled_terminal_waits)
        self.assertEqual(3, self.ledger.totals.live)

    def test_terminal_returned_past_deadline_retains_consumed_wait_without_receipt(self):
        def wait(tid, options):
            self.fixture.clock.return_value = 100.0
            return (11, 31)
        self.wait.side_effect = wait
        with self.assertRaises(ValueError):
            self.owner.run()
        self.assertEqual((11, 31), (self.owner.last_wait.tid, self.owner.last_wait.raw))
        self.assertEqual({11}, self.launch.unsettled_terminal_waits)
        self.assertEqual(2, self.ledger.totals.live)
        self.assertEqual((), self.owner.settled_reaps)

    def test_settlement_failure_retains_actual_consumed_wait_without_retry(self):
        self.wait.return_value = (11, 31)
        self.launch.dispatcher.reaped = Mock(side_effect=OSError('settlement failed'))
        with self.assertRaises(OSError):
            self.owner.run()
        self.assertEqual({11}, self.launch.unsettled_terminal_waits)
        self.assertEqual((), self.owner.settled_reaps)
        self.assertEqual(31, self.owner.last_wait.raw)
        self.assertEqual(2, self.ledger.totals.live)
        self.wait.assert_called_once()

    def test_post_settlement_deadline_failure_preserves_real_credit_release(self):
        original = self.launch.dispatcher.reaped
        def settle(*args):
            original(*args)
            self.fixture.clock.return_value = 100.0
        self.launch.dispatcher.reaped = settle
        self.wait.return_value = (11, 31)
        with self.assertRaises(ValueError):
            self.owner.run()
        self.assertEqual(1, self.ledger.totals.live)
        self.assertEqual('reaped', self.ledger.identity(1).terminal)
        self.assertEqual((), self.owner.settled_reaps)
        self.assertEqual(31, self.owner.last_wait.raw)
        self.assertEqual(set(), self.launch.unsettled_terminal_waits)

    def test_post_settlement_identity_mismatch_never_issues_record(self):
        original = self.launch.dispatcher.reaped
        def settle(*args):
            original(*args)
            self.ledger._tasks[1].tid = 99
        self.launch.dispatcher.reaped = settle
        self.wait.return_value = (11, 31)
        with self.assertRaisesRegex(WaitOwnerError, 'settlement mismatch'):
            self.owner.run()
        self.assertEqual((), self.owner.settled_reaps)
        self.assertEqual(1, self.ledger.totals.live)

    def test_later_shared_failure_revokes_validation_but_retains_diagnostics(self):
        for component in ('owner', 'dispatch', 'launch', 'reader', 'creation', 'lifecycle', 'policy'):
            self.setUp()
            record = self.reap()
            target = dict(owner=self.owner, dispatch=self.dispatch, launch=self.launch,
                          reader=self.launch.reader, creation=self.launch.dispatcher,
                          lifecycle=self.launch.dispatcher.lifecycle,
                          policy=self.launch.dispatcher.lifecycle.policy)[component]
            target.stopped = 'later failure'
            with self.assertRaises(ValueError):
                self.owner.require_settled_reap(record)
            self.assertEqual((record,), self.owner.settled_reaps)

    def test_later_owner_deadline_registration_and_ledger_drift_reject_record(self):
        for drift in ('deadline', 'native-owner', 'registration', 'identity', 'ledger'):
            self.setUp()
            record = self.reap()
            if drift == 'deadline':
                self.fixture.clock.return_value = 100.0
            elif drift == 'native-owner':
                self.fixture.system.getpid.return_value = 99
            elif drift == 'registration':
                self.dispatch._wait_owner = object()
            elif drift == 'identity':
                self.ledger._tasks[1].tid = 99
            else:
                self.ledger._stopped = 'shared ledger failed'
            with self.assertRaises(ValueError):
                self.owner.require_settled_reap(record)
            self.assertEqual((record,), self.owner.settled_reaps)

    def test_later_wait_failure_revokes_prior_success_without_erasing_record(self):
        self.fixture.child()
        self.wait.side_effect = [(11, 31), OSError('child wait failed')]
        with self.assertRaises(OSError):
            self.owner.run()
        record, = self.owner.settled_reaps
        self.assertEqual(1, record.before.birth)
        self.assertEqual(2, self.ledger.totals.live)
        with self.assertRaises(WaitOwnerError):
            self.owner.require_settled_reap(record)

    def test_thread_exec_reap_uses_migrated_birth_not_retired_leader(self):
        thread = self.fixture.exec_thread()
        self.owner._last_birth = 1
        thread.syscall_info.return_value = decode_syscall_info(*exit_info(0, 0))
        self.wait.side_effect = [ChildProcessError(errno.ECHILD, 'old tid gone'),
                                 (11, 0x4057f), (11, 0x857f), (11, 31)]
        self.owner.run()
        record, = self.owner.settled_reaps
        self.assertEqual((2, 1, 11), (record.before.birth, record.before.group_birth, record.before.tid))
        self.assertEqual('exec-replaced', self.ledger.identity(1).terminal)
        self.owner.require_settled_reap(record)
        self.assertEqual(1, self.ledger.totals.live)


if __name__ == '__main__':
    unittest.main()
