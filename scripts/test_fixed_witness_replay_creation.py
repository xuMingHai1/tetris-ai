"""Synthetic stopped-entry inputs only; no native syscalls or tracees."""
import unittest

import fixed_witness_replay_creation as c
from fixed_witness_replay_tasks import TaskLedger


class CreationPolicyTest(unittest.TestCase):
    def policy(self, **limits):
        ledger = TaskLedger(10, **limits)
        parent = ledger.reserve(0, 'coordinator')
        ledger.created(parent, 11)
        ledger.finish(parent, 11)
        return c.CreationPolicy(ledger), parent

    def entry(self, policy, parent, syscall=c.SYS_CLONE, flags=c.SIGCHLD):
        return policy.arbitrate(parent, c.AUDIT_ARCH_X86_64, syscall, (flags, 0, 0, 0, 0, 0))

    def test_fork_and_vfork_reserve_before_returning_obligation(self):
        for syscall, kind in ((c.SYS_FORK, 'fork'), (c.SYS_VFORK, 'vfork')):
            with self.subTest(kind=kind):
                policy, parent = self.policy()
                result = self.entry(policy, parent, syscall)
                self.assertEqual((kind, kind, False),
                                 (result.kind, result.expected_event, result.suppress_enosys))
                self.assertEqual((3, 3, 1), (policy.ledger.totals.attempts,
                                            policy.ledger.totals.live, policy.ledger.totals.pending))

    def test_legacy_process_clone_expects_fork_event(self):
        for flags in (c.SIGCHLD, c.SIGCHLD | c.CLONE_CHILD_CLEARTID | c.CLONE_CHILD_SETTID,
                      c.SIGCHLD | c.CLONE_VM):
            with self.subTest(flags=flags):
                policy, parent = self.policy()
                self.assertEqual('fork', self.entry(policy, parent, flags=flags).expected_event)

    def test_spawn_vfork_and_pthread_event_types_are_distinct(self):
        pthread = (c.CLONE_VM | c.CLONE_FS | c.CLONE_FILES | c.CLONE_SIGHAND
                   | c.CLONE_THREAD | c.CLONE_SYSVSEM | c.CLONE_SETTLS
                   | c.CLONE_PARENT_SETTID | c.CLONE_CHILD_CLEARTID)
        for flags, event in ((pthread, 'clone'), (c.CLONE_VM | c.CLONE_VFORK | c.SIGCHLD, 'vfork')):
            with self.subTest(event=event):
                policy, parent = self.policy()
                self.assertEqual(event, self.entry(policy, parent, flags=flags).expected_event)

    def test_clone3_always_costs_attempt_without_live_slot_or_pointer_read(self):
        policy, parent = self.policy(live=2)
        result = policy.arbitrate(parent, c.AUDIT_ARCH_X86_64, c.SYS_CLONE3,
                                  (2**64 - 1, 2**64 - 1, 0, 0, 0, 0))
        self.assertTrue(result.suppress_enosys)
        self.assertIsNone(result.expected_event)
        self.assertEqual((3, 2, 1), (policy.ledger.totals.attempts, policy.ledger.totals.live,
                                    policy.ledger.totals.clone3_enosys))
        # Only a future authenticated ENOSYS exit may finish this reservation.
        self.assertEqual(1, policy.ledger.totals.pending)

    def test_untraced_parent_namespace_and_unknown_flags_fail_before_permission(self):
        for bit in (c.CLONE_UNTRACED, 0x8000, 0x10000000, 0x1000, 0x2000,
                    0x100000000, 0x200000000, 1 << 63):
            with self.subTest(bit=bit):
                policy, parent = self.policy()
                with self.assertRaises(c.CreationPolicyError):
                    self.entry(policy, parent, flags=c.SIGCHLD | bit)
                self.assertEqual((3, 3, 1), (policy.ledger.totals.attempts,
                                            policy.ledger.totals.live, policy.ledger.totals.pending))
                policy.ledger.reaped(parent)
                self.assertEqual(1, policy.ledger.totals.live)
                with self.assertRaisesRegex(c.CreationPolicyError, 'stopped'):
                    self.entry(policy, parent)

    def test_invalid_signal_and_thread_relationships_are_rejected(self):
        for flags in (0, 9, c.CLONE_SIGHAND | c.SIGCHLD, c.CLONE_THREAD,
                      c.CLONE_VM | c.CLONE_THREAD,
                      c.CLONE_VM | c.CLONE_SIGHAND | c.CLONE_THREAD | c.SIGCHLD,
                      c.CLONE_VM | c.CLONE_SIGHAND | c.CLONE_THREAD | c.CLONE_VFORK):
            with self.subTest(flags=flags):
                policy, parent = self.policy()
                with self.assertRaises(c.CreationPolicyError):
                    self.entry(policy, parent, flags=flags)
                self.assertEqual(3, policy.ledger.totals.attempts)

    def test_compat_x32_and_other_syscalls_receive_no_obligation(self):
        for arch, syscall in ((0x40000003, c.SYS_FORK), (c.AUDIT_ARCH_X86_64, 0x40000000 | c.SYS_CLONE),
                              (c.AUDIT_ARCH_X86_64, 101), (True, c.SYS_FORK)):
            with self.subTest(arch=arch, syscall=syscall):
                policy, parent = self.policy()
                with self.assertRaises(c.CreationPolicyError):
                    policy.arbitrate(parent, arch, syscall, (0,) * 6)
                self.assertEqual(2, policy.ledger.totals.attempts)
                self.assertIsNotNone(policy.stopped)

    def test_register_arguments_are_bounded_exact_integers(self):
        for args in ([0] * 6, (0,) * 5, (True,) * 6, (-1,) * 6, (2**64,) * 6):
            with self.subTest(args=args):
                policy, parent = self.policy()
                with self.assertRaises(c.CreationPolicyError):
                    policy.arbitrate(parent, c.AUDIT_ARCH_X86_64, c.SYS_CLONE, args)
                self.assertEqual(2, policy.ledger.totals.attempts)

    def test_attempt_and_live_exhaustion_never_return_permission(self):
        for limits in ({'attempts': 2}, {'live': 2}):
            with self.subTest(limits=limits):
                policy, parent = self.policy(**limits)
                with self.assertRaisesRegex(c.CreationPolicyError, 'reservation failed'):
                    self.entry(policy, parent)
                self.assertIsNotNone(policy.ledger.totals.stopped)
                self.assertIsNotNone(policy.stopped)

    def test_kernel_failure_releases_live_but_never_attempt_credit(self):
        policy, parent = self.policy()
        result = self.entry(policy, parent)
        policy.ledger.finish(result.reservation, None)
        self.assertEqual((3, 2, 0), (policy.ledger.totals.attempts, policy.ledger.totals.live,
                                    policy.ledger.totals.pending))
        next_result = self.entry(policy, parent, c.SYS_FORK)
        self.assertNotEqual(result.reservation, next_result.reservation)
        self.assertEqual(4, policy.ledger.totals.attempts)

    def test_duplicate_pending_entry_latches_policy_and_preserves_reservation(self):
        policy, parent = self.policy()
        self.entry(policy, parent)
        with self.assertRaises(c.CreationPolicyError):
            self.entry(policy, parent)
        self.assertEqual((3, 3, 1), (policy.ledger.totals.attempts, policy.ledger.totals.live,
                                    policy.ledger.totals.pending))


if __name__ == '__main__':
    unittest.main()
