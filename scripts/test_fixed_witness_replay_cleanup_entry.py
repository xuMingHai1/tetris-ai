"""One cleanup entry, fake wait/ptrace/pidfd only; no native tasks."""
import unittest
from unittest.mock import call

from fixed_witness_replay_cleanup_reap import CleanupReapError
from fixed_witness_replay_creation import CLONE_VM, CLONE_VFORK, SIGCHLD
from fixed_witness_replay_wait_owner import WAIT_OPTIONS
import test_fixed_witness_replay_inflight_cleanup as inflight
import test_fixed_witness_replay_retained_cleanup as retained


class CleanupEntryTest(unittest.TestCase):
    def setUp(self):
        self.fixture = inflight.InflightCleanupTest()
        self.fixture.setUp()
        self.launch, self.ledger = self.fixture.launch, self.fixture.ledger
        self.transport, self.dispatch = self.fixture.transport, self.fixture.dispatch

    def test_single_entry_binds_fork_then_pins_all_kills_and_reaps(self):
        owner = self.fixture.pending()
        before = self.ledger.totals
        self.transport.wait.side_effect = [(11, 0x1057f), (11, 9), (12, 9)]
        result = owner.run()
        self.assertEqual([(1, 11), (2, 12)], [(b, t) for b, t, _ in result])
        self.assertEqual([call.wait(11, WAIT_OPTIONS), call.open(11, 0), call.open(12, 0),
                          call.send(70, 9, None, 0), call.send(71, 9, None, 0),
                          call.wait(11, WAIT_OPTIONS), call.wait(12, WAIT_OPTIONS),
                          call.close(71), call.close(70)], self.transport.mock_calls)
        self.assertEqual([call.event_tid(11)], self.dispatch.wire.mock_calls)
        self.assertEqual((before.attempts, 1, 0, before.wait_stops + 1),
                         (self.ledger.totals.attempts, self.ledger.totals.live,
                          self.ledger.totals.pending, self.ledger.totals.wait_stops))
        self.assertEqual('original failure', self.launch.stopped)
        self.assertEqual('ledger failure', self.ledger.totals.stopped)

    def test_vfork_and_legacy_clone_events_enter_same_cleanup(self):
        for number, flags, event in ((58, 0, 0x2057f),
                                    (56, SIGCHLD | CLONE_VM, 0x1057f),
                                    (56, SIGCHLD | CLONE_VM | CLONE_VFORK, 0x2057f)):
            self.setUp()
            owner = self.fixture.pending(number, flags)
            self.transport.wait.side_effect = [(11, event), (11, 9), (12, 9)]
            owner.run()
            self.assertEqual(((1, 2, 12),), tuple(owner.reaper.bound_creations))
            self.assertEqual((3, 1, 0), (self.ledger.totals.attempts,
                                       self.ledger.totals.live, self.ledger.totals.pending))

    def test_retained_normal_event_is_adopted_without_second_wait_or_read(self):
        fixture = retained.RetainedCleanupTest()
        fixture.setUp()
        fixture.fail_after_read()
        owner = fixture.fixture.owner()
        before = fixture.ledger.totals.wait_stops
        fixture.transport.wait.side_effect = [(11, 9), (12, 9)]
        owner.run()
        self.assertIs(owner.reaper.adopted_wait, fixture.normal.last_wait)
        self.assertEqual([call.event_tid(11)], fixture.dispatch.wire.mock_calls)
        self.assertEqual([call(11, WAIT_OPTIONS), call(12, WAIT_OPTIONS)],
                         fixture.transport.wait.call_args_list)
        self.assertEqual(before, fixture.ledger.totals.wait_stops)
        self.assertEqual(1, fixture.ledger.totals.live)

    def test_uncertain_retained_event_never_retries_or_pins(self):
        fixture = retained.RetainedCleanupTest()
        fixture.setUp()
        fixture.dispatch.wire.event_tid.side_effect = OSError('uncertain')
        with self.assertRaises(ValueError):
            fixture.normal.run()
        owner = fixture.fixture.owner()
        with self.assertRaisesRegex(CleanupReapError, 'uncertain'):
            owner.run()
        with self.assertRaisesRegex(CleanupReapError, 'already attempted'):
            owner.run()
        fixture.dispatch.wire.event_tid.assert_called_once_with(11)
        self.assertEqual([], fixture.transport.mock_calls)

    def test_failed_binding_consumes_terminal_but_never_opens_pid(self):
        owner = self.fixture.pending()
        self.transport.wait.return_value = (11, 9)
        with self.assertRaisesRegex(CleanupReapError, 'matching in-flight'):
            owner.run()
        self.assertEqual({11}, self.launch.unsettled_terminal_waits)
        self.transport.open.assert_not_called()
        self.transport.send.assert_not_called()
        self.assertEqual((3, 3, 1), (self.ledger.totals.attempts,
                                   self.ledger.totals.live, self.ledger.totals.pending))

    def test_binding_wire_setup_failure_retains_birth_without_pinning(self):
        owner = self.fixture.pending()
        self.transport.wait.return_value = (11, 0x1057f)
        self.launch.dispatcher._factory = lambda: (_ for _ in ()).throw(OSError('wire failed'))
        with self.assertRaises(ValueError):
            owner.run()
        self.assertEqual(12, self.ledger.identity(2).tid)
        self.assertEqual(2, self.ledger.cleanup_pending()[0].child)
        self.transport.open.assert_not_called()
        self.transport.send.assert_not_called()

    def test_binding_deadline_expires_before_pin_without_new_allowance(self):
        owner = self.fixture.pending()
        self.transport.wait.return_value = (0, 0)
        self.fixture.fixture.fixture.fixture.sleep.side_effect = lambda _: setattr(
            self.dispatch.clock, 'return_value', 100.0)
        with self.assertRaisesRegex(CleanupReapError, 'deadline'):
            owner.run()
        self.transport.wait.assert_called_once_with(11, WAIT_OPTIONS)
        self.transport.open.assert_not_called()
        self.assertEqual(3, self.ledger.totals.live)

    def test_event_stop_exhausts_original_budget_before_read_or_pin(self):
        owner = self.fixture.pending()
        self.ledger._event_limit = self.ledger.totals.wait_stops + 1
        self.transport.wait.return_value = (11, 0x1057f)
        with self.assertRaises(ValueError):
            owner.run()
        self.dispatch.wire.event_tid.assert_not_called()
        self.transport.open.assert_not_called()
        self.assertEqual('ledger failure', self.ledger.totals.stopped)

    def test_explicit_successful_binding_is_not_repeated_by_entry(self):
        owner = self.fixture.pending()
        self.transport.wait.side_effect = [(11, 0x1057f), (11, 9), (12, 9)]
        owner.reaper.bind_creation_events()
        owner.run()
        self.dispatch.wire.event_tid.assert_called_once_with(11)
        self.assertEqual(3, self.transport.wait.call_count)

    def test_prior_failed_explicit_binding_cannot_be_restarted_by_entry(self):
        owner = self.fixture.pending()
        self.transport.wait.return_value = (11, 0x137f)
        with self.assertRaises(ValueError):
            owner.reaper.bind_creation_events()
        with self.assertRaisesRegex(CleanupReapError, 'already failed'):
            owner.run()
        self.transport.wait.assert_called_once_with(11, WAIT_OPTIONS)
        self.transport.open.assert_not_called()

    def test_unmatched_and_thread_reservations_fail_before_any_transport(self):
        for kind, thread in (('fork', False), ('clone', True), ('clone3', False)):
            self.setUp()
            self.ledger.reserve(1, kind, thread=thread)
            self.fixture.fixture.stop()
            owner = self.fixture.fixture.fixture.owner()
            with self.assertRaises(ValueError):
                owner.run()
            self.assertEqual([], self.transport.mock_calls)

    def test_fully_bound_inventory_does_not_start_creation_wait_phase(self):
        self.fixture.fixture.child()
        self.fixture.fixture.stop()
        owner = self.fixture.fixture.fixture.owner()
        self.transport.wait.side_effect = [(11, 9), (12, 9)]
        owner.run()
        self.assertFalse(owner.reaper.creation_binding_started)
        self.assertEqual(call.open(11, 0), self.transport.mock_calls[0])


if __name__ == '__main__':
    unittest.main()
