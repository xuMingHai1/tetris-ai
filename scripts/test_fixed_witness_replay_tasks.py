"""Synthetic event sequences only: no fork/ptrace, JVM or historical inputs."""
import unittest

from fixed_witness_replay_tasks import TaskAccountingError, TaskLedger


class TaskLedgerTest(unittest.TestCase):
    def coordinator(self, **limits):
        ledger = TaskLedger(10, **limits)
        ticket = ledger.reserve(0, 'coordinator')
        self.assertEqual((2, 2, 1), (ledger.totals.attempts, ledger.totals.live,
                                    ledger.totals.pending))
        ledger.created(ticket, 11)
        ledger.finish(ticket, 11)
        return ledger, ticket

    def test_tracer_and_coordinator_are_charged_before_binding(self):
        ledger, coordinator = self.coordinator()
        self.assertEqual((2, 2, 0), (ledger.totals.attempts, ledger.totals.live,
                                    ledger.totals.pending))
        ledger.reaped(coordinator)
        ledger.require_drained()
        self.assertEqual(2, ledger.totals.attempts)

    def test_failed_kernel_creations_never_refund_attempts(self):
        ledger, parent = self.coordinator(attempts=4)
        for kind in ('fork', 'clone'):
            ticket = ledger.reserve(parent, kind)
            self.assertEqual(3, ledger.totals.live)
            ledger.finish(ticket, None)
        self.assertEqual((4, 2), (ledger.totals.attempts, ledger.totals.live))
        with self.assertRaisesRegex(TaskAccountingError, 'attempt limit'):
            ledger.reserve(parent, 'fork')
        self.assertEqual(4, ledger.totals.attempts)
        ledger.reaped(parent)
        with self.assertRaisesRegex(TaskAccountingError, 'stopped'):
            ledger.require_drained()

    def test_live_slot_is_not_released_by_success_or_leader_exit(self):
        ledger, parent = self.coordinator(live=3)
        child = ledger.reserve(parent, 'fork')
        ledger.created(child, 12)
        ledger.finish(child, 12)
        ledger.reaped(parent)
        self.assertEqual(2, ledger.totals.live)
        ledger.reaped(child)
        ledger.require_drained()

    def test_pending_live_slots_compete_for_last_slot(self):
        ledger, parent = self.coordinator(live=4)
        child = ledger.reserve(parent, 'clone')
        ledger.created(child, 12)
        ledger.finish(child, 12)
        pending = ledger.reserve(parent, 'fork')
        with self.assertRaisesRegex(TaskAccountingError, 'live task limit'):
            ledger.reserve(child, 'clone')
        self.assertEqual((5, 4, 1), (ledger.totals.attempts, ledger.totals.live,
                                    ledger.totals.pending))
        # Already-authorized events must still be accounted during cleanup.
        newborn = ledger.created(pending, 13)
        ledger.finish(pending, 13)
        for ticket in (newborn, child, parent):
            ledger.reaped(ticket)
        self.assertEqual(1, ledger.totals.live)
        with self.assertRaisesRegex(TaskAccountingError, 'stopped'):
            ledger.reserve(0, 'coordinator')

    def test_clone3_denial_costs_attempt_but_never_live_credit(self):
        ledger, parent = self.coordinator(live=2)
        denied = ledger.reserve(parent, 'clone3')
        self.assertEqual((3, 2, 1), (ledger.totals.attempts, ledger.totals.live,
                                    ledger.totals.clone3_enosys))
        ledger.finish(denied, None)
        self.assertEqual((3, 2, 0), (ledger.totals.attempts, ledger.totals.live,
                                    ledger.totals.pending))
        ledger.reaped(parent)
        ledger.require_drained()

    def test_clone3_cannot_bind_a_child(self):
        ledger, parent = self.coordinator()
        ticket = ledger.reserve(parent, 'clone3')
        with self.assertRaisesRegex(TaskAccountingError, 'unexpected'):
            ledger.created(ticket, 12)

    def test_vfork_child_can_reap_before_parent_syscall_result(self):
        ledger, parent = self.coordinator()
        child = ledger.reserve(parent, 'vfork')
        ledger.created(child, 12)
        ledger.reaped(child)
        ledger.finish(child, 12)
        self.assertEqual((3, 2, 0), (ledger.totals.attempts, ledger.totals.live,
                                    ledger.totals.pending))

    def test_tid_reuse_does_not_allow_old_birth_ticket_to_release_new_task(self):
        ledger, parent = self.coordinator()
        old = ledger.reserve(parent, 'fork')
        ledger.created(old, 12)
        ledger.finish(old, 12)
        ledger.reaped(old)
        new = ledger.reserve(parent, 'fork')
        ledger.created(new, 12)
        ledger.finish(new, 12)
        self.assertNotEqual(old, new)
        with self.assertRaisesRegex(TaskAccountingError, 'duplicate reap'):
            ledger.reaped(old)
        self.assertEqual(3, ledger.totals.live)
        ledger.reaped(new)
        ledger.reaped(parent)
        self.assertEqual(1, ledger.totals.live)

    def test_parent_terminal_reap_closes_unresolved_reservations(self):
        for kind, bound in (('fork', False), ('fork', True), ('clone3', False)):
            with self.subTest(kind=kind, bound=bound):
                ledger, parent = self.coordinator()
                ticket = ledger.reserve(parent, kind)
                if bound:
                    ledger.created(ticket, 12)
                ledger.reaped(parent)
                self.assertEqual(0, ledger.totals.pending)
                self.assertEqual(2 if bound else 1, ledger.totals.live)
                if bound:
                    ledger.reaped(ticket)
                ledger.require_drained()

    def test_event_ceiling_latches_without_reopening_for_cleanup(self):
        ledger, parent = self.coordinator(wait_stops=3)
        ledger.wait_stop()
        ledger.wait_stop()
        with self.assertRaisesRegex(TaskAccountingError, 'wait-stop limit'):
            ledger.wait_stop()
        ledger.reaped(parent)
        with self.assertRaises(TaskAccountingError):
            ledger.wait_stop()
        self.assertEqual(3, ledger.totals.wait_stops)
        self.assertEqual('wait-stop limit', ledger.totals.stopped)

    def test_unknown_duplicate_or_mismatched_events_fail_closed(self):
        cases = (
            lambda q, p, c: q.created(c, 12),  # duplicate event
            lambda q, p, c: q.finish(c, 99),
            lambda q, p, c: q.finish(c, None),
            lambda q, p, c: q.created(999, 12),
            lambda q, p, c: q.reserve(p, 'fork'),  # pending syscall
            lambda q, p, c: q.reserve(True, 'fork'),
            lambda q, p, c: q.reaped(0),
            lambda q, p, c: q.require_drained(),
        )
        for action in cases:
            with self.subTest(action=action):
                ledger, parent = self.coordinator()
                child = ledger.reserve(parent, 'clone')
                ledger.created(child, 12)
                before = ledger.totals
                with self.assertRaises(TaskAccountingError):
                    action(ledger, parent, child)
                self.assertIsNotNone(ledger.totals.stopped)
                self.assertEqual(before.attempts, ledger.totals.attempts)
                self.assertEqual(before.live, ledger.totals.live)

    def test_invalid_policy_values_cannot_expand_limits(self):
        for field, ceiling in (('attempts', 512), ('live', 128), ('wait_stops', 2_000_000)):
            for value in (True, 0, -1, 1.5, '4', ceiling + 1):
                with self.subTest(field=field, value=value), self.assertRaises(ValueError):
                    TaskLedger(10, **{field: value})

    def test_trusted_tracer_cannot_launch_more_than_the_coordinator(self):
        ledger, parent = self.coordinator()
        ledger.reaped(parent)
        with self.assertRaisesRegex(TaskAccountingError, 'one trusted coordinator'):
            ledger.reserve(0, 'coordinator')

    def test_success_requires_event_and_duplicate_result_cannot_refund(self):
        ledger, parent = self.coordinator()
        ticket = ledger.reserve(parent, 'fork')
        with self.assertRaisesRegex(TaskAccountingError, 'matching child event'):
            ledger.finish(ticket, 12)
        ledger.finish(ticket, None)
        with self.assertRaisesRegex(TaskAccountingError, 'already finished'):
            ledger.finish(ticket, None)
        self.assertEqual((3, 2), (ledger.totals.attempts, ledger.totals.live))


if __name__ == '__main__':
    unittest.main()
