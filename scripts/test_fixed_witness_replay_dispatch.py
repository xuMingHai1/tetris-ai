"""Synthetic wire/lifecycle composition; never loads libc or starts tracees."""
import unittest

from fixed_witness_replay_dispatch import CreationSyscallDispatcher, CreationDispatchError
from fixed_witness_replay_lifecycle import CreationLifecycle
from fixed_witness_replay_tasks import TaskLedger
import fixed_witness_replay_ptrace as p
from test_fixed_witness_replay_ptrace import KernelFixture, entry, exit_info


class CreationDispatchTest(unittest.TestCase):
    def setup_dispatch(self, **limits):
        self.ledger = TaskLedger(10, **limits)
        ticket = self.ledger.reserve(0, 'coordinator')
        self.ledger.created(ticket, 11)
        self.ledger.finish(ticket, 11)
        self.kernel = KernelFixture()
        self.accesses = []
        def factory():
            access = p.PtraceAccess(self.kernel)
            self.accesses.append(access)
            return access
        self.dispatch = CreationSyscallDispatcher(
            CreationLifecycle(self.ledger), factory, ticket, 11)
        return ticket

    def stop(self, ticket=1, tid=11, number=57, result=None, error=0, arch=p.AUDIT_ARCH_X86_64):
        self.kernel.info, self.kernel.available = (
            entry(number, arch) if result is None else exit_info(result, error, arch))
        return self.dispatch.syscall_stop(ticket, tid)

    def test_wire_factory_failure_preserves_child_for_cleanup(self):
        for error in (p.PtraceBoundaryError('unavailable'), OSError('load failed')):
            self.setup_dispatch()
            self.stop()
            def broken_factory():
                raise error
            self.dispatch._factory = broken_factory
            with self.assertRaises(CreationDispatchError):
                self.dispatch.child_event(1, 11, 'FORK', 12)
            self.assertIsNotNone(self.dispatch.stopped)
            self.dispatch.reaped(2, 12)
            self.dispatch.reaped(1, 11)
            self.assertEqual((1, 0), (self.ledger.totals.live, self.ledger.totals.pending))
            with self.assertRaises(CreationDispatchError):
                self.dispatch.require_drained()

    def test_reused_wire_preserves_child_for_cleanup(self):
        self.setup_dispatch()
        self.stop()
        self.dispatch._factory = lambda: self.accesses[0]
        with self.assertRaisesRegex(CreationDispatchError, 'exclusive'):
            self.dispatch.child_event(1, 11, 'FORK', 12)
        self.dispatch.reaped(2, 12)
        self.dispatch.reaped(1, 11)
        self.assertEqual((1, 0), (self.ledger.totals.live, self.ledger.totals.pending))

    def test_fork_binds_event_and_result_then_drains(self):
        parent = self.setup_dispatch()
        self.stop()
        child = self.dispatch.child_event(parent, 11, 'FORK', 12)
        self.stop(result=12)
        self.dispatch.reaped(parent, 11)
        self.dispatch.reaped(child, 12)
        self.dispatch.require_drained()
        self.assertEqual((3, 1), (self.ledger.totals.attempts, self.ledger.totals.live))

    def test_clone3_debit_precedes_register_intervention(self):
        self.setup_dispatch()
        original = self.kernel.__call__
        def observed(request, *args):
            if request in (p.GETREGSET, p.SETREGSET):
                self.assertEqual(3, self.ledger.totals.attempts)
            return original(request, *args)
        self.accesses[0]._call = observed
        self.stop(number=435)
        self.kernel.suppressed_exit()
        self.dispatch.syscall_stop(1, 11)
        self.assertEqual(2, len(self.kernel.writes))
        self.dispatch.reaped(1, 11)
        self.dispatch.require_drained()

    def test_exhausted_attempt_never_reads_or_writes_registers(self):
        self.setup_dispatch(attempts=2)
        with self.assertRaises(CreationDispatchError):
            self.stop(number=435)
        self.assertTrue(all(request == p.GET_SYSCALL_INFO
                            for request, _, _ in self.kernel.requests))
        self.assertEqual([], self.kernel.writes)

    def test_failed_wire_intervention_latches_and_reap_cleans_pending(self):
        self.setup_dispatch()
        self.kernel.failure = p.SETREGSET
        with self.assertRaises(CreationDispatchError):
            self.stop(number=435)
        with self.assertRaisesRegex(CreationDispatchError, 'stopped'):
            self.stop()
        self.dispatch.reaped(1, 11)
        self.assertEqual((1, 0), (self.ledger.totals.live, self.ledger.totals.pending))
        with self.assertRaises(CreationDispatchError):
            self.dispatch.require_drained()

    def test_clone3_exit_requires_wire_register_confirmation(self):
        self.setup_dispatch()
        self.stop(number=435)
        self.kernel.info, self.kernel.available = exit_info()
        # Deliberately omit the kernel's rax update.
        with self.assertRaisesRegex(CreationDispatchError, 'register mismatch'):
            self.dispatch.syscall_stop(1, 11)
        self.assertEqual(1, self.ledger.totals.pending)

    def test_wrong_birth_binding_prevents_kernel_access(self):
        self.setup_dispatch()
        for ticket, tid in ((1, 12), (True, 11), (2, 11)):
            with self.assertRaises(CreationDispatchError):
                self.dispatch.syscall_stop(ticket, tid)
        self.assertEqual([], self.kernel.requests)

    def test_wrong_creation_event_latches(self):
        self.setup_dispatch()
        self.stop()
        with self.assertRaisesRegex(CreationDispatchError, 'event kind'):
            self.dispatch.child_event(1, 11, 'CLONE', 12)
        self.dispatch.reaped(1, 11)
        self.assertEqual(1, self.ledger.totals.live)

    def test_duplicate_entry_rejected_before_second_debit(self):
        self.setup_dispatch()
        self.stop()
        with self.assertRaisesRegex(CreationDispatchError, 'before prior syscall result'):
            self.stop()
        self.assertEqual(3, self.ledger.totals.attempts)

    def test_vfork_child_can_reap_before_parent_result(self):
        self.setup_dispatch()
        self.stop(number=58)
        child = self.dispatch.child_event(1, 11, 'VFORK', 12)
        self.dispatch.reaped(child, 12)
        self.stop(result=12)
        self.dispatch.reaped(1, 11)
        self.dispatch.require_drained()

    def test_success_without_child_event_is_not_accepted(self):
        self.setup_dispatch()
        self.stop()
        with self.assertRaisesRegex(CreationDispatchError, 'matching child'):
            self.stop(result=12)

    def test_native_abi_guard_rejects_compat_and_x32(self):
        for arch, number in ((0x40000003, 120), (p.AUDIT_ARCH_X86_64, p.X32_BIT + 435)):
            self.setup_dispatch()
            with self.assertRaisesRegex(CreationDispatchError, 'CAPABILITY_UNAVAILABLE'):
                self.stop(arch=arch, number=number)
            self.assertEqual(2, self.ledger.totals.attempts)

    def test_noncreation_and_child_initial_exit_do_not_debit(self):
        self.setup_dispatch()
        self.stop(number=39)
        self.stop(result=11)
        self.assertEqual(2, self.ledger.totals.attempts)
        self.assertEqual([], self.kernel.writes)

    def test_reaped_clone3_birth_does_not_poison_reused_tid(self):
        self.setup_dispatch()
        self.stop()
        child = self.dispatch.child_event(1, 11, 'FORK', 12)
        self.stop(result=12)
        self.stop(ticket=child, tid=12, result=0)
        self.stop(ticket=child, tid=12, number=435)
        self.dispatch.reaped(child, 12)
        self.stop()
        next_child = self.dispatch.child_event(1, 11, 'FORK', 12)
        self.stop(result=12)
        self.assertIsNot(self.accesses[1], self.accesses[2])
        self.stop(ticket=next_child, tid=12, result=0)
        self.kernel.registers[15] = 435
        self.stop(ticket=next_child, tid=12, number=435)
        self.kernel.suppressed_exit()
        self.dispatch.syscall_stop(next_child, 12)
        self.dispatch.reaped(next_child, 12)
        self.dispatch.reaped(1, 11)
        self.dispatch.require_drained()

    def test_coordinator_stray_exit_latches_without_creation_debit(self):
        self.setup_dispatch()
        with self.assertRaisesRegex(CreationDispatchError, 'without entry'):
            self.stop(result=0)
        self.assertEqual(2, self.ledger.totals.attempts)
        with self.assertRaisesRegex(CreationDispatchError, 'stopped'):
            self.stop()
        self.dispatch.reaped(1, 11)
        self.assertEqual({}, self.dispatch._sequence)

    def test_noncreation_entry_cannot_hide_following_creation(self):
        self.setup_dispatch()
        self.stop(number=39)
        with self.assertRaisesRegex(CreationDispatchError, 'before prior syscall result'):
            self.stop(number=435)
        self.assertEqual(2, self.ledger.totals.attempts)
        self.assertEqual([], self.kernel.writes)

    def test_duplicate_noncreation_exit_is_rejected(self):
        self.setup_dispatch()
        self.stop(number=39)
        self.stop(result=11)
        with self.assertRaisesRegex(CreationDispatchError, 'without entry'):
            self.stop(result=11)

    def newborn(self):
        self.setup_dispatch()
        self.stop()
        child = self.dispatch.child_event(1, 11, 'FORK', 12)
        self.stop(result=12)
        return child

    def test_newborn_initial_exit_is_one_shot_and_not_a_debit(self):
        child = self.newborn()
        self.stop(ticket=child, tid=12, result=0)
        self.assertEqual(3, self.ledger.totals.attempts)
        with self.assertRaisesRegex(CreationDispatchError, 'without entry'):
            self.stop(ticket=child, tid=12, result=0)

    def test_newborn_initial_exit_rejects_errors_and_parent_return(self):
        for result, error in ((12, 0), (-38, 1)):
            child = self.newborn()
            with self.assertRaisesRegex(CreationDispatchError, 'successful zero'):
                self.stop(ticket=child, tid=12, result=result, error=error)
            self.dispatch.reaped(child, 12)
            self.dispatch.reaped(1, 11)
            self.assertEqual((1, 0), (self.ledger.totals.live, self.ledger.totals.pending))

    def test_newborn_cannot_skip_initial_exit(self):
        child = self.newborn()
        with self.assertRaisesRegex(CreationDispatchError, 'before prior syscall result'):
            self.stop(ticket=child, tid=12, number=435)
        self.assertEqual(3, self.ledger.totals.attempts)
        self.assertEqual([], self.kernel.writes)

    def test_managed_ptrace_is_rejected_before_any_resume_or_write(self):
        self.setup_dispatch()
        with self.assertRaisesRegex(CreationDispatchError, 'ptrace syscall forbidden'):
            self.stop(number=101)
        self.assertEqual(2, self.ledger.totals.attempts)
        self.assertEqual([p.GET_SYSCALL_INFO], [r[0] for r in self.kernel.requests])


if __name__ == '__main__':
    unittest.main()
