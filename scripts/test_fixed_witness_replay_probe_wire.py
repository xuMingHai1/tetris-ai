"""Original network EXEC/wire/ENTRY/reap composition with fake native I/O only."""
from dataclasses import replace
import unittest
from unittest.mock import Mock

import fixed_witness_replay_ptrace as p
from fixed_witness_replay_abi_model import ExecIdentity
import test_fixed_witness_replay_filter_exec_chain as chain_fixture
from test_fixed_witness_replay_ptrace import KernelFixture, entry, exit_info


class ProbeWireTest(unittest.TestCase):
    def setUp(self):
        self.f = chain_fixture.FilterExecChainTest()
        self.f.setUp()

    def bound(self):
        self.f.prepare_probe()
        self.f.step()
        return self.f.typed_wire, self.f.config.observations[0].identity

    def entered(self):
        wire, identity = self.bound()
        self.f.syscall(exit_info(0, 0))
        self.f.syscall(entry(0x40000029))
        return wire, identity

    def test_network_chain_requires_original_typed_wire(self):
        self.f.launch.dispatcher._tasks[self.f.birth] = (11, self.f.wire)
        with self.assertRaisesRegex(ValueError, 'typed network'):
            self.f.step()
        self.f.wire.resume_syscall.assert_not_called()
        self.assertIsNotNone(self.f.config.stopped)

    def test_intervention_during_exec_source_observation_cannot_bind(self):
        def intervene(*args):
            if not self.f.typed_wire._wire_failed:
                with self.assertRaises(ValueError):
                    self.f.typed_wire.resume_syscall(11, 31)
        self.f.files.hook = intervene
        with self.assertRaises(ValueError):
            self.f.step()
        self.assertFalse(self.f.config.observations)
        self.assertFalse(self.f.files.fds)
        self.f.wire.resume_syscall.assert_not_called()

    def test_successful_unscoped_write_before_launcher_cannot_be_hidden(self):
        wire = self.f.typed_wire
        original = wire._call
        wire._call = Mock(return_value=0)
        wire._request(0x4203, 11, 0, 0)  # SETSIGINFO, before the seal.
        wire._call = original
        with self.assertRaisesRegex(ValueError, 'modified'):
            self.f.step()
        self.f.wire.resume_syscall.assert_not_called()

    def test_raw_writes_and_unknown_requests_block_before_kernel(self):
        for request in (4, 5, 6, 7, 12, 13, 17, 0x4203, p.SETREGSET, p.SETOPTIONS):
            self.setUp()
            wire, _ = self.bound()
            kernel = Mock(return_value=0)
            wire._call = kernel
            with self.assertRaisesRegex(ValueError, 'unowned'):
                wire._request(request, 11, 0, 0)
            kernel.assert_not_called()
            self.assertTrue(wire._wire_failed)

    def test_raw_resume_cannot_inject_or_suppress_signal(self):
        for signal in (0, 10, 31):
            self.setUp()
            wire, _ = self.bound()
            self.f.wire.resume_syscall.reset_mock()
            with self.assertRaisesRegex(ValueError, 'unowned'):
                wire.resume_syscall(11, signal)
            self.f.wire.resume_syscall.assert_not_called()

    def test_foreign_owner_manifest_epoch_and_tid_cannot_resume(self):
        _, identity = self.bound()
        manifest = self.f.config._abi_model.manifest_sha256
        for owner, who, digest in ((object(), identity, manifest),
                                   (self.f.config, identity, '0' * 64),
                                   (self.f.config, replace(identity, generation=1), manifest),
                                   (self.f.config, replace(identity, tid=12), manifest)):
            self.setUp()
            wire, _ = self.bound()
            # Rebind only the fixture's valid original owner in same-owner rows.
            if type(owner) is type(self.f.config):
                owner = self.f.config
            with self.assertRaisesRegex(ValueError, 'clean probe'):
                wire.resume_probe(owner, who, digest, 0)

    def test_original_owner_identity_is_not_a_transferable_resume_token(self):
        for signal in (0, 9, 31):
            self.setUp()
            wire, identity = self.entered()
            self.f.wire.resume_syscall.reset_mock()
            with self.assertRaisesRegex(ValueError, 'original active wire'):
                wire.resume_probe(self.f.config, identity,
                                  self.f.config._abi_model.manifest_sha256, signal)
            self.f.wire.resume_syscall.assert_not_called()
            self.f.wait.return_value = (11, 31)
            with self.assertRaises(ValueError):
                self.f.step()
            self.assertEqual('AWAITING_SIGSYS', self.f.config.abi_state('x32'))
            self.assertEqual(1, len(self.f.owner.settled_reaps))

    def test_wire_replacement_after_probe_exec_revokes_before_entry(self):
        self.bound()
        self.f.launch.dispatcher._tasks[self.f.birth] = (11, p.PtraceAccess(Mock(return_value=0)))
        # The facts read itself is invalid, and the original wire never resumes.
        self.f.wire.resume_syscall.reset_mock()
        with self.assertRaises(ValueError):
            self.f.syscall(exit_info(0, 0))
        self.f.wire.resume_syscall.assert_not_called()
        self.assertIsNotNone(self.f.config.stopped)

    def test_uncertain_entry_resume_poisoned_without_retry_or_refund(self):
        wire, identity = self.bound()
        self.f.syscall(exit_info(0, 0))
        self.f.wire.resume_syscall.side_effect = OSError('uncertain')
        before = self.f.launch.ledger.totals
        with self.assertRaises(OSError):
            self.f.syscall(entry(0x40000029))
        count = self.f.wire.resume_syscall.call_count
        with self.assertRaises(ValueError):
            wire.resume_probe(self.f.config, identity, self.f.config._abi_model.manifest_sha256, 0)
        self.assertEqual(count, self.f.wire.resume_syscall.call_count)
        self.assertIn(self.f.birth, self.f.config._entry_resumes)
        self.assertNotIn(self.f.birth, self.f.config._completed_entries)
        self.assertEqual(before.live, self.f.launch.ledger.totals.live)

    def test_failed_intervention_after_entry_keeps_real_reap_but_rejects_terminal(self):
        wire, _ = self.entered()
        with self.assertRaises(ValueError):
            wire._request(0x4203, 11, 0, 0)
        before = self.f.launch.ledger.totals.live
        self.f.wait.return_value = (11, 31)
        with self.assertRaises(ValueError):
            self.f.step()
        self.assertEqual(1, len(self.f.owner.settled_reaps))
        self.assertEqual(before - 1, self.f.launch.ledger.totals.live)
        self.assertEqual('AWAITING_SIGSYS', self.f.config.abi_state('x32'))
        self.assertIsNotNone(self.f.config.stopped)

    def test_original_wire_counters_cover_full_two_exec_terminal_path(self):
        wire, _ = self.entered()
        self.f.wait.return_value = (11, 31)
        self.f.step()
        self.assertEqual('TERMINAL_FACT_MATCHED', self.f.config.abi_state('x32'))
        self.assertEqual(wire._wire_attempts, wire._wire_completed)
        self.assertGreater(wire._wire_attempts, 8)
        self.assertIsNone(wire._resume_scope)
        self.assertIsNone(wire._write_scope)

    def test_wire_replacement_before_terminal_preserves_reap_without_success(self):
        self.entered()
        self.f.launch.dispatcher._tasks[self.f.birth] = (11, p.PtraceAccess(Mock(return_value=0)))
        self.f.wait.return_value = (11, 31)
        before = self.f.launch.ledger.totals.live
        with self.assertRaisesRegex(ValueError, 'settled probe wire'):
            self.f.step()
        self.assertEqual(before - 1, self.f.launch.ledger.totals.live)
        self.assertEqual(1, len(self.f.owner.settled_reaps))
        self.assertEqual('AWAITING_SIGSYS', self.f.config.abi_state('x32'))

    def test_validated_clone3_is_separate_from_probe_register_injection(self):
        kernel = KernelFixture()
        wire = p.PtraceAccess(kernel)
        identity = ExecIdentity(1, 1, 101, 1)
        wire.bind_probe(self, identity, 'a' * 64)
        before = kernel.registers.copy()
        token = wire.suppress_clone3(101)
        expected = before.copy()
        expected[15] = p.U64_MASK
        self.assertEqual(expected, kernel.registers)
        kernel.suppressed_exit()
        before = kernel.registers.copy()
        wire.finish_clone3(token)
        self.assertEqual(before, kernel.registers)
        wire.check_probe(self, identity, 'a' * 64)
        self.assertEqual(2, len(kernel.writes))
        self.assertFalse(wire._unscoped_mutation)

    def test_failed_clone3_write_remains_uncertain_and_cannot_repeat(self):
        kernel = KernelFixture()
        wire = p.PtraceAccess(kernel)
        identity = ExecIdentity(1, 1, 101, 1)
        wire.bind_probe(self, identity, 'a' * 64)
        kernel.failure = p.SETREGSET
        with self.assertRaises(ValueError):
            wire.suppress_clone3(101)
        calls = len(kernel.requests)
        with self.assertRaises(ValueError):
            wire.suppress_clone3(101)
        self.assertEqual(calls, len(kernel.requests))
        self.assertIsNone(wire._write_scope)

    def test_rebinding_and_pending_clone3_cannot_reset_wire_history(self):
        kernel = KernelFixture()
        wire = p.PtraceAccess(kernel)
        identity = ExecIdentity(1, 1, 101, 1)
        wire.bind_probe(self, identity, 'a' * 64)
        with self.assertRaises(ValueError):
            wire.bind_probe(self, identity, 'a' * 64)
        wire.suppress_clone3(101)
        with self.assertRaises(ValueError):
            wire.bind_probe(self, replace(identity, generation=2), 'a' * 64)


if __name__ == '__main__':
    unittest.main()
