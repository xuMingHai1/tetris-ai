"""Synthetic lifecycle sequences only; no native process or ptrace calls."""
import unittest

import fixed_witness_replay_creation as c
from fixed_witness_replay_lifecycle import CreationLifecycle, CreationLifecycleError
from fixed_witness_replay_tasks import TaskLedger


class CreationLifecycleTest(unittest.TestCase):
    def setup_lifecycle(self, **limits):
        ledger = TaskLedger(10, **limits)
        parent = ledger.reserve(0, 'coordinator')
        ledger.created(parent, 11)
        ledger.finish(parent, 11)
        return CreationLifecycle(ledger), parent

    def entry(self, lifecycle, parent, syscall=c.SYS_FORK, flags=0):
        return lifecycle.entry(parent, c.AUDIT_ARCH_X86_64, syscall, (flags, 0, 0, 0, 0, 0))

    def test_success_requires_event_then_result_and_reap(self):
        lifecycle, parent = self.setup_lifecycle()
        obligation = self.entry(lifecycle, parent)
        child = lifecycle.child_event(parent, 'fork', 12)
        self.assertEqual(obligation.reservation, child)
        lifecycle.result(parent, 12, False)
        self.assertEqual((3, 3, 0), (lifecycle.ledger.totals.attempts,
                                    lifecycle.ledger.totals.live, lifecycle.ledger.totals.pending))
        lifecycle.reaped(parent)
        lifecycle.reaped(child)
        lifecycle.require_drained()

    def test_vfork_child_can_reap_before_parent_exit(self):
        lifecycle, parent = self.setup_lifecycle()
        self.entry(lifecycle, parent, c.SYS_VFORK)
        child = lifecycle.child_event(parent, 'vfork', 12)
        lifecycle.reaped(child)
        lifecycle.result(parent, 12, False)
        lifecycle.reaped(parent)
        lifecycle.require_drained()

    def test_clone_event_uses_policy_classification(self):
        thread = c.CLONE_VM | c.CLONE_SIGHAND | c.CLONE_THREAD
        for flags, event in ((c.SIGCHLD, 'fork'), (c.CLONE_VM | c.CLONE_VFORK | c.SIGCHLD, 'vfork'),
                             (thread, 'clone')):
            with self.subTest(event=event):
                lifecycle, parent = self.setup_lifecycle()
                self.entry(lifecycle, parent, c.SYS_CLONE, flags)
                child = lifecycle.child_event(parent, event, 12)
                identity = lifecycle.ledger.identity(child)
                self.assertEqual(parent if event == 'clone' else child, identity.group_birth)
                self.assertEqual(11 if event == 'clone' else 12, identity.tgid)
                lifecycle.result(parent, 12, False)
                lifecycle.reaped(child)
                lifecycle.reaped(parent)
                lifecycle.require_drained()

    def test_thread_parent_process_clone_with_shared_vm_gets_new_group(self):
        lifecycle, leader = self.setup_lifecycle()
        flags = c.CLONE_VM | c.CLONE_SIGHAND | c.CLONE_THREAD
        self.entry(lifecycle, leader, c.SYS_CLONE, flags)
        thread = lifecycle.child_event(leader, 'clone', 12)
        lifecycle.result(leader, 12, False)
        self.entry(lifecycle, thread, c.SYS_CLONE, c.CLONE_VM | c.SIGCHLD)
        process = lifecycle.child_event(thread, 'fork', 13)
        lifecycle.result(thread, 13, False)
        self.assertEqual((process, 13), (lifecycle.ledger.identity(process).group_birth,
                                        lifecycle.ledger.identity(process).tgid))
        self.assertEqual(leader, lifecycle.ledger.identity(thread).group_birth)

    def test_rejected_thread_flags_keep_reservation_without_bound_identity(self):
        lifecycle, parent = self.setup_lifecycle()
        with self.assertRaises(CreationLifecycleError):
            self.entry(lifecycle, parent, c.SYS_CLONE, c.CLONE_THREAD)
        self.assertEqual((3, 3, 1), (lifecycle.ledger.totals.attempts,
                                    lifecycle.ledger.totals.live, lifecycle.ledger.totals.pending))
        with self.assertRaisesRegex(CreationLifecycleError, 'no pending'):
            lifecycle.child_event(parent, 'clone', 12)
        lifecycle.reaped(parent)
        self.assertEqual((3, 1, 0), (lifecycle.ledger.totals.attempts,
                                    lifecycle.ledger.totals.live, lifecycle.ledger.totals.pending))

    def test_wrong_event_never_binds_or_allows_more_entries(self):
        lifecycle, parent = self.setup_lifecycle()
        self.entry(lifecycle, parent)
        with self.assertRaisesRegex(CreationLifecycleError, 'event kind'):
            lifecycle.child_event(parent, 'clone', 12)
        with self.assertRaisesRegex(CreationLifecycleError, 'stopped'):
            self.entry(lifecycle, parent)
        # Legitimate in-flight accounting remains possible during cleanup.
        child = lifecycle.child_event(parent, 'fork', 12)
        lifecycle.result(parent, 12, False)
        lifecycle.reaped(parent)
        lifecycle.reaped(child)
        with self.assertRaisesRegex(CreationLifecycleError, 'stopped'):
            lifecycle.require_drained()
        self.assertEqual(1, lifecycle.ledger.totals.live)

    def test_kernel_failure_refunds_only_live_reservation(self):
        lifecycle, parent = self.setup_lifecycle()
        self.entry(lifecycle, parent)
        lifecycle.result(parent, -11, True)
        self.assertEqual((3, 2, 0), (lifecycle.ledger.totals.attempts,
                                    lifecycle.ledger.totals.live, lifecycle.ledger.totals.pending))
        self.entry(lifecycle, parent)
        self.assertEqual(4, lifecycle.ledger.totals.attempts)

    def test_clone3_requires_enosys_and_never_child_event(self):
        for action in ('event', 'wrong_errno', 'success'):
            with self.subTest(action=action):
                lifecycle, parent = self.setup_lifecycle(live=2)
                self.entry(lifecycle, parent, c.SYS_CLONE3)
                with self.assertRaises(CreationLifecycleError):
                    if action == 'event':
                        lifecycle.child_event(parent, 'fork', 12)
                    elif action == 'wrong_errno':
                        lifecycle.result(parent, -1, True)
                    else:
                        lifecycle.result(parent, 12, False)
                lifecycle.result(parent, -38, True)
                self.assertEqual((3, 2, 0, 1), (lifecycle.ledger.totals.attempts,
                                              lifecycle.ledger.totals.live,
                                              lifecycle.ledger.totals.pending,
                                              lifecycle.ledger.totals.clone3_enosys))

    def test_invalid_results_preserve_reservation_for_cleanup(self):
        for value, error in ((True, False), (0, False), (-1, False), (12, True),
                             (-4096, True), (-38, 1), (2**31, False)):
            with self.subTest(value=value, error=error):
                lifecycle, parent = self.setup_lifecycle()
                self.entry(lifecycle, parent)
                with self.assertRaisesRegex(CreationLifecycleError, 'invalid creation'):
                    lifecycle.result(parent, value, error)
                self.assertEqual(1, lifecycle.ledger.totals.pending)
                lifecycle.reaped(parent)
                self.assertEqual((3, 1, 0), (lifecycle.ledger.totals.attempts,
                                            lifecycle.ledger.totals.live, lifecycle.ledger.totals.pending))

    def test_missing_duplicate_and_mismatched_events_or_results_fail(self):
        actions = (
            lambda q, p: q.result(p, 12, False),
            lambda q, p: q.child_event(999, 'fork', 12),
            lambda q, p: q.child_event(True, 'fork', 12),
        )
        for action in actions:
            lifecycle, parent = self.setup_lifecycle()
            self.entry(lifecycle, parent)
            with self.assertRaises(CreationLifecycleError):
                action(lifecycle, parent)
        for action in (lambda q, p: q.child_event(p, 'fork', 12),
                       lambda q, p: q.result(p, 13, False),
                       lambda q, p: q.result(p, -11, True)):
            lifecycle, parent = self.setup_lifecycle()
            self.entry(lifecycle, parent)
            lifecycle.child_event(parent, 'fork', 12)
            with self.assertRaises(CreationLifecycleError):
                action(lifecycle, parent)

    def test_reaped_parent_closes_pending_but_keeps_bound_child_live(self):
        for bound in (False, True):
            with self.subTest(bound=bound):
                lifecycle, parent = self.setup_lifecycle()
                self.entry(lifecycle, parent)
                if bound:
                    child = lifecycle.child_event(parent, 'fork', 12)
                lifecycle.reaped(parent)
                self.assertEqual(2 if bound else 1, lifecycle.ledger.totals.live)
                if bound:
                    lifecycle.reaped(child)
                lifecycle.require_drained()
                with self.assertRaisesRegex(CreationLifecycleError, 'no pending'):
                    lifecycle.result(parent, -11, True)

    def test_reused_tid_and_old_parent_ticket_do_not_match(self):
        lifecycle, parent = self.setup_lifecycle()
        self.entry(lifecycle, parent)
        old = lifecycle.child_event(parent, 'fork', 12)
        lifecycle.result(parent, 12, False)
        lifecycle.reaped(old)
        self.entry(lifecycle, parent)
        new = lifecycle.child_event(parent, 'fork', 12)
        lifecycle.result(parent, 12, False)
        self.entry(lifecycle, new)
        with self.assertRaisesRegex(CreationLifecycleError, 'no pending'):
            lifecycle.result(old, -11, True)
        self.assertEqual(1, lifecycle.ledger.totals.pending)
        lifecycle.result(new, -11, True)
        lifecycle.reaped(new)
        lifecycle.reaped(parent)
        self.assertEqual(1, lifecycle.ledger.totals.live)

    def test_entry_failure_latches_lifecycle_even_without_pending_obligation(self):
        for limits, syscall, flags in (({'live': 2}, c.SYS_FORK, 0),
                                      ({}, c.SYS_CLONE, c.CLONE_UNTRACED | c.SIGCHLD)):
            lifecycle, parent = self.setup_lifecycle(**limits)
            with self.assertRaises(CreationLifecycleError):
                self.entry(lifecycle, parent, syscall, flags)
            lifecycle.reaped(parent)
            with self.assertRaisesRegex(CreationLifecycleError, 'stopped'):
                lifecycle.require_drained()

    def test_duplicate_completed_result_cannot_refund_or_replace_new_attempt(self):
        lifecycle, parent = self.setup_lifecycle()
        self.entry(lifecycle, parent)
        lifecycle.result(parent, -11, True)
        with self.assertRaisesRegex(CreationLifecycleError, 'no pending'):
            lifecycle.result(parent, -11, True)
        self.assertEqual((3, 2, 0), (lifecycle.ledger.totals.attempts,
                                    lifecycle.ledger.totals.live, lifecycle.ledger.totals.pending))
        with self.assertRaisesRegex(CreationLifecycleError, 'stopped'):
            self.entry(lifecycle, parent)


if __name__ == '__main__':
    unittest.main()
