"""Retained EXEC takeover to pidfd kill/reap; injected transports only."""
import unittest
from unittest.mock import Mock, call

from fixed_witness_replay_cleanup_reap import CleanupReapError
from fixed_witness_replay_wait_owner import BoundedWaitOwner, WAIT_OPTIONS
from fixed_witness_replay_ptrace import decode_syscall_info
from test_fixed_witness_replay_ptrace import entry
import test_fixed_witness_replay_thread_cleanup as fixture


class RetainedExecCleanupTest(unittest.TestCase):
    def setUp(self, number=59):
        self.f = fixture.ThreadCleanupTest()
        self.f.setUp()
        self.launch, self.ledger = self.f.launch, self.f.ledger
        self.post = self.f.f.owner
        self.f.child.syscall_info.return_value = decode_syscall_info(*entry(number))
        self.post.accept(12, 0x857f)
        self.f.child.reset_mock()
        self.f.f.wire.reset_mock()
        self.wait = Mock(return_value=(11, 0x4057f))
        self.normal = BoundedWaitOwner(self.post, waitpid=self.wait)
        self.f.transport.wait.side_effect = [(11, 9)]

    def fail_after_read(self, old_tid=12):
        def read(_):
            self.launch.dispatcher.stopped = 'original creation failure'
            return old_tid
        self.f.f.wire.event_tid.side_effect = read
        with self.assertRaises(ValueError):
            self.normal.run()

    def assert_no_pin(self):
        self.f.transport.open.assert_not_called()
        self.f.transport.send.assert_not_called()
        self.f.transport.wait.assert_not_called()

    def test_original_read_migrates_then_kills_and_reaps_executor(self):
        self.fail_after_read()
        before, reason = self.ledger.totals, self.launch.stopped
        owner = self.f.owner()
        result = owner.run()
        self.assertIs(owner.reaper.adopted_exec_wait, self.normal.last_wait)
        self.assertEqual([(2, 11)], [(b, tid) for b, tid, _ in result])
        self.assertEqual('exec-replaced', self.ledger.identity(1).terminal)
        self.assertEqual('reaped', self.ledger.identity(2).terminal)
        self.assertEqual([call.open(11, 0), call.send(70, 9, None, 0),
                          call.wait(11, WAIT_OPTIONS), call.close(70)], self.f.transport.mock_calls)
        self.assertEqual([call.event_tid(11)], self.f.f.wire.mock_calls)
        self.assertEqual([], self.f.child.mock_calls)
        self.assertEqual((before.attempts, 1, before.wait_stops),
                         (self.ledger.totals.attempts, self.ledger.totals.live,
                          self.ledger.totals.wait_stops))
        self.assertEqual(reason, self.launch.stopped)
        self.assertEqual('original creation failure', self.launch.dispatcher.stopped)
        self.wait.assert_called_once_with(11, WAIT_OPTIONS)

    def test_exhausted_stop_budget_allows_one_original_event_read_no_refund(self):
        self.ledger._event_limit = self.ledger.totals.wait_stops + 1
        self.f.f.wire.event_tid.return_value = 12
        with self.assertRaises(ValueError):
            self.normal.run()
        self.assertFalse(self.normal.last_wait.event_attempted)
        before = self.ledger.totals
        self.f.owner().run()
        self.f.f.wire.event_tid.assert_called_once_with(11)
        self.assertEqual((before.attempts, before.wait_stops, before.stopped, 1),
                         (self.ledger.totals.attempts, self.ledger.totals.wait_stops,
                          self.ledger.totals.stopped, self.ledger.totals.live))
        self.assertIsNotNone(self.launch.reader.stopped)

    def test_uncertain_original_read_never_retried(self):
        self.f.f.wire.event_tid.side_effect = OSError('uncertain')
        with self.assertRaises(ValueError):
            self.normal.run()
        owner = self.f.owner()
        with self.assertRaisesRegex(CleanupReapError, 'uncertain'):
            owner.run()
        with self.assertRaises(CleanupReapError):
            owner.run()
        self.f.f.wire.event_tid.assert_called_once_with(11)
        self.assert_no_pin()
        self.assertEqual(3, self.ledger.totals.live)

    def test_uncertain_cleanup_read_is_not_retried(self):
        self.ledger._event_limit = self.ledger.totals.wait_stops + 1
        with self.assertRaises(ValueError):
            self.normal.run()
        self.f.f.wire.event_tid.side_effect = OSError('uncertain cleanup')
        owner = self.f.owner()
        with self.assertRaises(OSError):
            owner.run()
        with self.assertRaises(CleanupReapError):
            owner.reaper.bind_retained_exec()
        self.f.f.wire.event_tid.assert_called_once_with(11)
        self.assert_no_pin()

    def test_wrong_executor_or_group_fails_before_pin(self):
        for old_tid in (True, 0, 13, 10, 2**31):
            with self.subTest(old_tid=old_tid):
                self.setUp()
                self.fail_after_read(old_tid)
                with self.assertRaises(ValueError):
                    self.f.owner().run()
                self.assert_no_pin()
                self.assertEqual(3, self.ledger.totals.live)

    def test_parent_snapshot_change_rejects_retained_event(self):
        self.fail_after_read()
        self.ledger._tasks[1].tid = 13
        with self.assertRaisesRegex(CleanupReapError, 'birth mismatch'):
            self.f.owner().run()
        self.assert_no_pin()

    def test_replaced_owner_cannot_supply_receipt(self):
        self.fail_after_read()
        self.normal.dispatcher = Mock()
        with self.assertRaisesRegex(CleanupReapError, 'owner mismatch'):
            self.f.owner().run()
        self.assert_no_pin()

    def test_direct_accept_cannot_supply_cleanup_exec_provenance(self):
        self.f.f.wire.event_tid.side_effect = lambda _: (
            setattr(self.launch.dispatcher, 'stopped', 'original failure') or 12)
        with self.assertRaises(ValueError):
            self.post.accept(11, 0x4057f)
        with self.assertRaisesRegex(ValueError, 'thread/exec'):
            self.f.owner().run()
        self.assertIsNone(self.normal.last_wait)
        self.assert_no_pin()

    def test_adoption_is_once_only_even_before_pin(self):
        self.fail_after_read()
        owner = self.f.owner()
        owner.reaper.bind_retained_exec()
        with self.assertRaises(CleanupReapError):
            owner.reaper.bind_retained_exec()
        self.assert_no_pin()

    def test_expired_deadline_never_reads_or_pins(self):
        self.fail_after_read()
        self.f.f.clock.return_value = 100.0
        with self.assertRaisesRegex(CleanupReapError, 'deadline'):
            self.f.owner()
        self.f.f.wire.event_tid.assert_called_once_with(11)
        self.assert_no_pin()

    def test_owner_loss_never_reads_or_pins(self):
        self.fail_after_read()
        self.f.f.system.getpid.return_value = 99
        with self.assertRaisesRegex(CleanupReapError, 'owner'):
            self.f.owner()
        self.assert_no_pin()

    def test_execveat_uses_same_original_entry_join(self):
        self.setUp(322)
        self.fail_after_read()
        self.f.owner().run()
        self.assertEqual('exec-replaced', self.ledger.identity(1).terminal)
        self.assertEqual(1, self.ledger.totals.live)

    def test_terminal_quarantine_prevents_migration(self):
        self.fail_after_read()
        self.launch.unsettled_terminal_waits.add(11)
        with self.assertRaisesRegex(CleanupReapError, 'birth mismatch'):
            self.f.owner().run()
        self.assert_no_pin()

    def test_already_installed_normal_exec_is_not_installed_or_read_twice(self):
        self.f.f.wire.event_tid.return_value = 12
        self.f.child.resume_syscall.side_effect = OSError('resume uncertain')
        with self.assertRaises(OSError):
            self.normal.run()
        self.assertTrue(self.normal.last_wait.exec_installed)
        self.f.owner().run()
        self.f.f.wire.event_tid.assert_called_once_with(11)
        self.f.child.resume_syscall.assert_called_once_with(11, 0)
        self.assertEqual(1, self.ledger.totals.live)

    def test_unreaped_sibling_or_pending_creation_rejects_migration(self):
        for pending in (False, True):
            self.setUp()
            if pending:
                self.ledger.reserve(1, 'fork')
            else:
                ticket = self.ledger.reserve(1, 'clone', thread=True)
                self.ledger.created(ticket, 13)
                self.ledger.finish(ticket, 13)
            self.fail_after_read()
            with self.assertRaises(ValueError):
                self.f.owner().run()
            self.assert_no_pin()
            self.assertTrue(self.ledger.identity(1).alive)

    def test_matched_event_before_failed_install_reuses_exact_entry(self):
        self.f.f.wire.event_tid.return_value = 12
        original = self.launch.dispatcher.install_exec_identity
        def fail(facts):
            self.ledger._stopped = 'original ledger failure'
            return original(facts)
        self.launch.dispatcher.install_exec_identity = fail
        with self.assertRaises(ValueError):
            self.normal.run()
        self.assertIn(2, self.launch.dispatcher._exec_events)
        self.f.owner().run()
        self.assertEqual('original ledger failure', self.ledger.totals.stopped)
        self.assertEqual(1, self.ledger.totals.live)
        self.f.f.wire.event_tid.assert_called_once_with(11)

    def test_missing_or_changed_native_entry_never_migrates(self):
        for entry_facts in (None, decode_syscall_info(*entry(39))):
            self.setUp()
            self.fail_after_read()
            self.launch.dispatcher._sequence[2] = entry_facts
            with self.assertRaisesRegex(ValueError, 'native exec entry'):
                self.f.owner().run()
            self.assert_no_pin()
            self.assertTrue(self.ledger.identity(1).alive)

    def test_cleanup_read_crossing_deadline_retains_value_without_migration(self):
        self.ledger._event_limit = self.ledger.totals.wait_stops + 1
        with self.assertRaises(ValueError):
            self.normal.run()
        def read(_):
            self.f.f.clock.return_value = 100.0
            return 12
        self.f.f.wire.event_tid.side_effect = read
        owner = self.f.owner()
        with self.assertRaisesRegex(CleanupReapError, 'deadline'):
            owner.run()
        self.assertTrue(self.normal.last_wait.event_read)
        self.assertEqual(12, self.normal.last_wait.event_tid)
        self.assertTrue(self.ledger.identity(1).alive)
        self.assert_no_pin()

    def test_pin_failure_keeps_migrated_birth_and_forbids_second_cleanup(self):
        self.fail_after_read()
        self.f.transport.open.side_effect = OSError('pidfd failure')
        owner = self.f.owner()
        with self.assertRaises(OSError):
            owner.run()
        with self.assertRaises(CleanupReapError):
            owner.run()
        self.assertEqual((11, 'exec-replaced', 2),
                         (self.ledger.identity(2).tid, self.ledger.identity(1).terminal,
                          self.ledger.totals.live))
        self.f.transport.send.assert_not_called()
        self.f.transport.wait.assert_not_called()

    def test_same_tid_exec_uses_same_join_without_retiring_another_birth(self):
        # First actually reap the sibling, then execute on the surviving leader.
        self.launch.dispatcher._sequence[2] = None
        self.post.accept(12, 9)
        self.f.f.syscall(entry(59))
        self.f.f.wire.reset_mock()
        self.fail_after_read(11)
        self.f.owner().run()
        self.assertEqual('reaped', self.ledger.identity(1).terminal)
        self.assertEqual('reaped', self.ledger.identity(2).terminal)
        self.assertEqual(1, self.ledger.totals.live)


if __name__ == '__main__':
    unittest.main()
