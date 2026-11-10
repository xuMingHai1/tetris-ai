"""Sole-waiter failure takeover with fake transports only; no native tasks."""
import unittest
import struct
from unittest.mock import Mock, call

from fixed_witness_replay_cleanup_reap import CleanupReapError
from fixed_witness_replay_wait_owner import BoundedWaitOwner, WAIT_OPTIONS
from fixed_witness_replay_creation import SIGCHLD, CLONE_VM, CLONE_VFORK
from test_fixed_witness_replay_ptrace import entry
import test_fixed_witness_replay_settled_cleanup as fixture


class RetainedCleanupTest(unittest.TestCase):
    def setUp(self, number=57, flags=0):
        self.fixture = fixture.SettledCleanupTest()
        self.fixture.setUp()
        self.launch, self.ledger = self.fixture.launch, self.fixture.ledger
        self.dispatch = self.fixture.fixture.fixture
        self.transport = self.fixture.transport
        raw, size = entry(number)
        raw = bytearray(raw)
        struct.pack_into('<6Q', raw, 32, flags, 0, 0, 0, 0, 0)
        self.dispatch.syscall((bytes(raw), size))
        self.dispatch.wire.reset_mock()
        self.wait = Mock(return_value=(11, 0x1057f))
        self.normal = BoundedWaitOwner(self.dispatch.owner, waitpid=self.wait)

    def fail_after_read(self, tid=12):
        def event(_):
            self.launch.dispatcher.stopped = 'binding failure'
            return tid
        self.dispatch.wire.event_tid.side_effect = event
        with self.assertRaises(ValueError):
            self.normal.run()

    def cleanup(self):
        owner = self.fixture.owner()
        self.transport.wait.side_effect = [(11, 9), (12, 9)]
        owner.reaper.bind_creation_events()
        return owner

    def test_read_event_adopted_without_second_wait_read_debit_or_resume(self):
        self.fail_after_read()
        before = self.ledger.totals
        original = self.launch.stopped
        owner = self.cleanup()
        self.transport.wait.assert_not_called()
        self.assertIs(owner.reaper.adopted_wait, self.normal.last_wait)
        owner.run()
        self.wait.assert_called_once_with(11, WAIT_OPTIONS)
        self.assertEqual([call.event_tid(11)], self.dispatch.wire.mock_calls)
        self.assertEqual((before.attempts, 1, 0, before.wait_stops),
                         (self.ledger.totals.attempts, self.ledger.totals.live,
                          self.ledger.totals.pending, self.ledger.totals.wait_stops))
        self.assertEqual(original, self.launch.stopped)
        self.assertEqual('binding failure', self.launch.dispatcher.stopped)
        self.assertIsNotNone(self.normal.stopped)

    def test_debited_budget_failure_before_read_allows_only_one_cleanup_read(self):
        self.ledger._event_limit = self.ledger.totals.wait_stops + 1
        self.dispatch.wire.event_tid.return_value = 12
        with self.assertRaises(ValueError):
            self.normal.run()
        self.assertFalse(self.normal.last_wait.event_attempted)
        before = self.ledger.totals
        owner = self.cleanup()
        owner.run()
        self.assertEqual(before.wait_stops, self.ledger.totals.wait_stops)
        self.assertEqual([call.event_tid(11)], self.dispatch.wire.mock_calls)
        self.assertIsNotNone(self.ledger.totals.stopped)
        self.assertIsNotNone(self.launch.reader.stopped)

    def test_uncertain_normal_read_never_retried_or_inferred(self):
        self.dispatch.wire.event_tid.side_effect = OSError('uncertain event read')
        with self.assertRaises(ValueError):
            self.normal.run()
        owner = self.fixture.owner()
        with self.assertRaisesRegex(CleanupReapError, 'uncertain'):
            owner.reaper.bind_creation_events()
        self.dispatch.wire.event_tid.assert_called_once_with(11)
        self.assertEqual([], self.transport.mock_calls)
        self.assertIsNone(self.ledger.cleanup_pending()[0].child)

    def test_uncertain_cleanup_read_is_one_shot(self):
        self.ledger._event_limit = self.ledger.totals.wait_stops + 1
        with self.assertRaises(ValueError):
            self.normal.run()
        self.dispatch.wire.event_tid.side_effect = OSError('cleanup read failed')
        owner = self.fixture.owner()
        with self.assertRaises(OSError):
            owner.reaper.bind_creation_events()
        with self.assertRaises(CleanupReapError):
            owner.reaper.bind_creation_events()
        with self.assertRaises(CleanupReapError):
            owner.run()
        self.dispatch.wire.event_tid.assert_called_once_with(11)
        self.assertEqual([], self.transport.mock_calls)

    def test_deadline_after_normal_read_retains_facts_but_never_extends_time(self):
        def event(_):
            self.dispatch.clock.return_value = 100.0
            return 12
        self.dispatch.wire.event_tid.side_effect = event
        with self.assertRaises(ValueError):
            self.normal.run()
        self.assertTrue(self.normal.last_wait.event_read)
        self.assertEqual(12, self.normal.last_wait.event_tid)
        with self.assertRaisesRegex(CleanupReapError, 'deadline'):
            self.fixture.owner()
        self.assertEqual([], self.transport.mock_calls)

    def test_wrong_creation_event_retained_but_not_adopted(self):
        self.wait.return_value = (11, 0x2057f)
        self.fail_after_read()
        owner = self.fixture.owner()
        with self.assertRaisesRegex(CleanupReapError, 'matching creation'):
            owner.reaper.bind_creation_events()
        self.assertEqual([], self.transport.mock_calls)
        self.assertIsNone(self.ledger.cleanup_pending()[0].child)

    def test_noncreation_stop_failure_never_becomes_creation_receipt(self):
        self.wait.return_value = (11, 0x857f)
        self.dispatch.wire.syscall_info.side_effect = OSError('read failed')
        with self.assertRaises(ValueError):
            self.normal.run()
        owner = self.fixture.owner()
        with self.assertRaisesRegex(CleanupReapError, 'matching creation'):
            owner.reaper.bind_creation_events()
        self.dispatch.wire.event_tid.assert_not_called()
        self.assertEqual([], self.transport.mock_calls)

    def test_parent_or_reservation_change_prevents_adoption(self):
        for change in ('parent', 'reservation'):
            self.setUp()
            self.fail_after_read()
            if change == 'parent':
                # Simulate an intervening identity transition without claiming
                # an actual reap or silently closing the pending reservation.
                self.ledger._tasks[1].tid = 13
            else:
                self.ledger.created(2, 12)
                self.ledger.finish(2, 12)
                self.ledger.reserve(1, 'fork')
            owner = self.fixture.owner()
            with self.assertRaises(ValueError):
                owner.reaper.bind_creation_events()
            self.assertEqual([], self.transport.mock_calls)

    def test_invalid_or_duplicate_read_child_never_binds(self):
        for tid in (True, 0, 11, 10, 2**31):
            self.setUp()
            self.fail_after_read(tid)
            owner = self.fixture.owner()
            with self.assertRaises(ValueError):
                owner.reaper.bind_creation_events()
            self.assertIsNone(self.ledger.cleanup_pending()[0].child)
            self.assertEqual([], self.transport.mock_calls)

    def test_cleanup_wire_failure_preserves_child_and_forbids_replay(self):
        self.fail_after_read()
        self.launch.dispatcher._factory = Mock(side_effect=OSError('wire setup failed'))
        owner = self.fixture.owner()
        with self.assertRaises(ValueError):
            owner.reaper.bind_creation_events()
        self.assertEqual(12, self.ledger.identity(2).tid)
        self.assertEqual(2, self.ledger.cleanup_pending()[0].child)
        with self.assertRaises(CleanupReapError):
            owner.reaper.bind_creation_events()
        self.dispatch.wire.event_tid.assert_called_once_with(11)
        self.assertEqual([], self.transport.mock_calls)

    def test_direct_accept_facts_never_fabricate_a_wait_owner_receipt(self):
        self.dispatch.wire.event_tid.side_effect = lambda _: (
            setattr(self.launch.dispatcher, 'stopped', 'binding failure') or 12)
        with self.assertRaises(ValueError):
            self.dispatch.owner.accept(11, 0x1057f)
        self.assertIsNone(self.normal.last_wait)
        owner = self.fixture.owner()
        # No retained provenance: only the existing new actual wait phase is
        # available; an unconsumed non-event must fail rather than use old facts.
        self.transport.wait.return_value = (11, 0x137f)
        with self.assertRaises(CleanupReapError):
            owner.reaper.bind_creation_events()
        self.assertIsNone(self.ledger.cleanup_pending()[0].child)
        self.dispatch.wire.event_tid.assert_called_once_with(11)

    def test_already_bound_child_is_not_read_or_adopted_twice(self):
        self.dispatch.wire.event_tid.return_value = 12
        self.dispatch.wire.resume_syscall.side_effect = OSError('resume uncertain')
        with self.assertRaises(OSError):
            self.normal.run()
        owner = self.cleanup()
        self.assertIsNone(owner.reaper.adopted_wait)
        owner.run()
        self.dispatch.wire.event_tid.assert_called_once_with(11)
        self.assertEqual(1, self.dispatch.wire.resume_syscall.call_count)

    def test_vfork_and_clone_use_original_native_event_mapping(self):
        for number, flags, raw in ((58, 0, 0x2057f),
                                   (56, SIGCHLD | CLONE_VM, 0x1057f),
                                   (56, SIGCHLD | CLONE_VM | CLONE_VFORK, 0x2057f)):
            self.setUp(number, flags)
            self.wait.return_value = (11, raw)
            self.fail_after_read()
            owner = self.cleanup()
            owner.run()
            self.assertEqual((3, 1, 0), (self.ledger.totals.attempts,
                                       self.ledger.totals.live, self.ledger.totals.pending))
            self.dispatch.wire.event_tid.assert_called_once_with(11)

    def test_owner_loss_before_read_retains_debit_and_requires_original_owner(self):
        def wait(*_):
            self.dispatch.system.getpid.return_value = 99
            return (11, 0x1057f)
        self.wait.side_effect = wait
        before = self.ledger.totals.wait_stops
        with self.assertRaises(ValueError):
            self.normal.run()
        self.assertEqual(before + 1, self.ledger.totals.wait_stops)
        self.assertFalse(self.normal.last_wait.event_attempted)
        with self.assertRaisesRegex(CleanupReapError, 'owner'):
            self.fixture.owner()
        self.dispatch.system.getpid.return_value = self.launch.owner
        self.dispatch.wire.event_tid.return_value = 12
        owner = self.cleanup()
        owner.run()
        self.assertEqual(before + 1, self.ledger.totals.wait_stops)
        self.dispatch.wire.event_tid.assert_called_once_with(11)

    def test_raw_tuple_cannot_replace_the_registered_owned_wait(self):
        self.fail_after_read()
        self.normal.last_wait = (11, 0x1057f, 12)
        owner = self.fixture.owner()
        with self.assertRaisesRegex(CleanupReapError, 'retained wait owner'):
            owner.reaper.bind_creation_events()
        self.assertEqual([], self.transport.mock_calls)

    def test_deadline_crossed_by_wait_retains_status_without_event_read(self):
        def wait(*_):
            self.dispatch.clock.return_value = 100.0
            return (11, 0x1057f)
        self.wait.side_effect = wait
        before = self.ledger.totals.wait_stops
        with self.assertRaises(ValueError):
            self.normal.run()
        self.assertEqual(before + 1, self.ledger.totals.wait_stops)
        self.assertEqual((11, 0x1057f), (self.normal.last_wait.tid, self.normal.last_wait.raw))
        self.assertFalse(self.normal.last_wait.event_attempted)
        self.dispatch.wire.event_tid.assert_not_called()
        with self.assertRaisesRegex(CleanupReapError, 'deadline'):
            self.fixture.owner()


if __name__ == '__main__':
    unittest.main()
