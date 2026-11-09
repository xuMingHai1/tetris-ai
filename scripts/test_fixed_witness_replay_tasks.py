"""Synthetic event sequences only: no fork/ptrace, JVM or historical inputs."""
import unittest
from dataclasses import FrozenInstanceError

from fixed_witness_replay_tasks import TaskAccountingError, TaskLedger


class TaskLedgerTest(unittest.TestCase):
    def test_leader_exec_preserves_birth_and_budgets(self):
        ledger, leader = self.coordinator()
        before = ledger.totals
        result = ledger.exec_identity(leader, 11, 11)
        self.assertEqual((leader, 11, leader, True, None),
                         (result.birth, result.tid, result.group_birth,
                          result.alive, result.terminal))
        self.assertEqual(before, ledger.totals)

    def test_nonleader_exec_retires_only_replaced_leader(self):
        ledger, leader = self.coordinator()
        executor = self.child(ledger, leader, 12, thread=True)
        sibling = self.child(ledger, leader, 13, thread=True)
        ledger.reaped(sibling)
        before = ledger.identity(executor)
        result = ledger.exec_identity(executor, 12, 11)
        self.assertEqual(12, before.tid)
        self.assertEqual((executor, 11, leader, 11, True),
                         (result.birth, result.tid, result.group_birth,
                          result.tgid, result.alive))
        self.assertEqual('exec-replaced', ledger.identity(leader).terminal)
        self.assertEqual('reaped', ledger.identity(sibling).terminal)
        self.assertEqual((4, 2), (ledger.totals.attempts, ledger.totals.live))
        ledger.reaped(executor)
        ledger.require_drained()

    def test_successive_execs_use_current_leader_and_keep_original_group(self):
        ledger, original = self.coordinator()
        first = self.child(ledger, original, 12, thread=True)
        ledger.exec_identity(first, 12, 11)
        ledger.exec_identity(first, 11, 11)
        second = self.child(ledger, first, 13, thread=True)
        ledger.exec_identity(second, 13, 11)
        self.assertEqual('exec-replaced', ledger.identity(first).terminal)
        self.assertEqual((original, 11), (ledger.identity(second).group_birth,
                                        ledger.identity(second).tgid))
        self.assertEqual((4, 2), (ledger.totals.attempts, ledger.totals.live))

    def test_exec_cannot_infer_other_sibling_reaps(self):
        for use_leader in (True, False):
            ledger, leader = self.coordinator()
            thread = self.child(ledger, leader, 12, thread=True)
            self.child(ledger, leader, 13, thread=True)
            before = ledger.totals
            with self.assertRaisesRegex(TaskAccountingError, 'siblings'):
                ledger.exec_identity(leader if use_leader else thread,
                                     11 if use_leader else 12, 11)
            self.assertEqual(before.live, ledger.totals.live)
            self.assertTrue(ledger.identity(leader).alive)
            self.assertEqual(12, ledger.identity(thread).tid)

    def test_exec_rejects_wrong_birth_tid_group_and_noninteger_facts(self):
        for birth, old, new in ((0, 10, 10), (99, 12, 11), (2, 99, 11),
                                (2, 12, 99), (2, True, 11), (2, 12, True),
                                (True, 11, 11)):
            with self.subTest(facts=(birth, old, new)):
                ledger, leader = self.coordinator()
                thread = self.child(ledger, leader, 12, thread=True)
                with self.assertRaises(TaskAccountingError):
                    ledger.exec_identity(birth, old, new)
                self.assertEqual((3, 3), (ledger.totals.attempts, ledger.totals.live))
                self.assertEqual(12, ledger.identity(thread).tid)

    def test_exec_rejects_unresolved_group_creation_without_partial_mutation(self):
        for pending_owner in ('leader', 'executor'):
            ledger, leader = self.coordinator()
            thread = self.child(ledger, leader, 12, thread=True)
            ledger.reserve(leader if pending_owner == 'leader' else thread, 'fork')
            with self.assertRaisesRegex(TaskAccountingError, 'unresolved'):
                ledger.exec_identity(thread, 12, 11)
            self.assertEqual((4, 1), (ledger.totals.live, ledger.totals.pending))
            self.assertTrue(ledger.identity(leader).alive)
            self.assertEqual(12, ledger.identity(thread).tid)

    def test_exec_rejects_dead_or_stopped_birth_and_reaped_leader(self):
        for state in ('executor-dead', 'leader-dead', 'stopped'):
            ledger, leader = self.coordinator(wait_stops=1)
            thread = self.child(ledger, leader, 12, thread=True)
            if state == 'stopped':
                with self.assertRaises(TaskAccountingError):
                    ledger.wait_stop()
            else:
                ledger.reaped(thread if state == 'executor-dead' else leader)
            before = ledger.totals
            with self.assertRaises(TaskAccountingError):
                ledger.exec_identity(thread, 12, 11)
            self.assertEqual(before.live, ledger.totals.live)

    def test_replaced_leader_cannot_release_credit_of_migrated_executor(self):
        ledger, leader = self.coordinator()
        thread = self.child(ledger, leader, 12, thread=True)
        ledger.exec_identity(thread, 12, 11)
        with self.assertRaisesRegex(TaskAccountingError, 'duplicate reap'):
            ledger.reaped(leader)
        self.assertEqual(2, ledger.totals.live)
        ledger.reaped(thread)
        self.assertEqual(1, ledger.totals.live)

    def test_exec_releases_old_numeric_tid_without_rebinding_old_birth(self):
        ledger, leader = self.coordinator()
        thread = self.child(ledger, leader, 12, thread=True)
        ledger.exec_identity(thread, 12, 11)
        process = self.child(ledger, thread, 12, kind='fork')
        self.assertEqual((process, 12), (ledger.identity(process).group_birth,
                                       ledger.identity(process).tgid))
        self.assertEqual(11, ledger.identity(thread).tid)
        self.assertFalse(ledger.identity(leader).alive)

    def test_parent_creation_result_keeps_original_child_tid_after_exec(self):
        ledger, parent = self.coordinator()
        leader = ledger.reserve(parent, 'fork')
        ledger.created(leader, 12)
        thread = self.child(ledger, leader, 13, thread=True)
        ledger.exec_identity(thread, 13, 12)
        # Parent's original fork result names its original child, even though
        # the leader was silently retired and its TID now belongs to thread.
        ledger.finish(leader, 12)
        ledger.reaped(thread)
        ledger.reaped(parent)
        ledger.require_drained()

    def child(self, ledger, parent, tid, *, thread=False, kind='clone'):
        birth = ledger.reserve(parent, kind, thread=thread)
        ledger.created(birth, tid)
        ledger.finish(birth, tid)
        return birth

    def test_thread_of_thread_keeps_original_group_birth(self):
        ledger, leader = self.coordinator()
        first = self.child(ledger, leader, 12, thread=True)
        second = self.child(ledger, first, 13, thread=True)
        for birth, tid in ((leader, 11), (first, 12), (second, 13)):
            identity = ledger.identity(birth)
            self.assertEqual((birth, tid, leader, 11, True),
                             (identity.birth, identity.tid, identity.group_birth,
                              identity.tgid, identity.alive))
        self.assertEqual((4, 4), (ledger.totals.attempts, ledger.totals.live))

    def test_process_from_thread_starts_its_own_group(self):
        for kind in ('fork', 'vfork', 'clone'):
            with self.subTest(kind=kind):
                ledger, leader = self.coordinator()
                thread = self.child(ledger, leader, 12, thread=True)
                process = self.child(ledger, thread, 13, kind=kind)
                identity = ledger.identity(process)
                self.assertEqual((process, 13), (identity.group_birth, identity.tgid))

    def test_reaped_leader_retains_group_and_each_thread_live_credit(self):
        ledger, leader = self.coordinator()
        thread = self.child(ledger, leader, 12, thread=True)
        snapshot = ledger.identity(leader)
        ledger.reaped(leader)
        self.assertTrue(snapshot.alive)
        self.assertFalse(ledger.identity(leader).alive)
        self.assertEqual(2, ledger.totals.live)
        descendant = self.child(ledger, thread, 13, thread=True)
        self.assertEqual((leader, 11), (ledger.identity(descendant).group_birth,
                                      ledger.identity(descendant).tgid))
        with self.assertRaises(FrozenInstanceError):
            snapshot.tid = 99
        ledger.reaped(thread)
        ledger.reaped(descendant)
        ledger.require_drained()

    def test_reused_group_tid_cannot_replace_old_group_birth(self):
        ledger, parent = self.coordinator()
        old = self.child(ledger, parent, 12, kind='fork')
        ledger.reaped(old)
        new = self.child(ledger, parent, 12, kind='fork')
        thread = self.child(ledger, new, 13, thread=True)
        self.assertNotEqual(old, ledger.identity(thread).group_birth)
        self.assertEqual(new, ledger.identity(thread).group_birth)
        self.assertFalse(ledger.identity(old).alive)
        self.assertTrue(ledger.identity(new).alive)

    def test_thread_group_does_not_collapse_live_budget_or_refund_attempts(self):
        ledger, leader = self.coordinator(live=3)
        thread = self.child(ledger, leader, 12, thread=True)
        with self.assertRaisesRegex(TaskAccountingError, 'live task limit'):
            ledger.reserve(thread, 'clone', thread=True)
        self.assertEqual((4, 3), (ledger.totals.attempts, ledger.totals.live))
        # Snapshots remain usable for cleanup after a latched budget failure.
        self.assertEqual(leader, ledger.identity(thread).group_birth)
        ledger.reaped(leader)
        ledger.reaped(thread)
        self.assertEqual(1, ledger.totals.live)

    def test_thread_marker_requires_exact_boolean_and_clone(self):
        for kind, marker in (('fork', True), ('vfork', True), ('clone3', True),
                             ('clone', 1), ('clone', None)):
            with self.subTest(kind=kind, marker=marker):
                ledger, parent = self.coordinator()
                with self.assertRaisesRegex(TaskAccountingError, 'thread relation'):
                    ledger.reserve(parent, kind, thread=marker)
                self.assertEqual((2, 2, 0), (ledger.totals.attempts, ledger.totals.live,
                                           ledger.totals.pending))

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
