"""Fake owned wait/model composition; no native ABI entry or probe execution.

ENTRY consumption below is injected into the private model, deliberately NOT
through the still-native-only dispatcher. These tests certify only the terminal
join, never a usable ABI exception or the full capability sequence.
"""
from dataclasses import replace
import errno
import unittest
from unittest.mock import patch

from fixed_witness_replay_abi_model import AbiModelError
from fixed_witness_replay_abi_observation import AbiObservationError
from fixed_witness_replay_ptrace import decode_syscall_info
from fixed_witness_replay_wait_owner import WaitOwnerError
import test_fixed_witness_replay_exec_binding as fixture
from test_fixed_witness_replay_ptrace import entry, exit_info


class OwnedAbiTerminalTest(unittest.TestCase):
    def setup_bound(self, *, mode='x32', thread=False, selected=True):
        self.f = fixture.OwnedExecBindingTest()
        self.f.setup_binding(mode=mode, thread=thread, selected=selected)
        f = self.f
        self.config, self.model, self.owner = f.config, f.model, f.owner
        self.launch, self.ledger = f.f.launch, f.f.ledger
        f.step()
        f.f.wire.syscall_info.return_value = decode_syscall_info(*exit_info(0, 0))
        f.wait.return_value = (11, 0x857f)
        f.step()
        f.f.wire.reset_mock()
        f.wait.reset_mock()

    def setUp(self):
        self.setup_bound()

    def consume_synthetic_entry(self):
        mode = self.f.record.mode
        arch, number = (0xc000003e, 0x40000029) if mode == 'x32' else (0x40000003, 102)
        self.model.consume_entry(mode, self.model._identities[mode],
                                 self.model.manifest_sha256,
                                 decode_syscall_info(*entry(number, arch)))

    def reap(self, raw=31):
        self.f.wait.return_value = (11, raw)
        self.owner.run()
        record, = self.owner.settled_reaps
        return record

    def assert_retained_failure(self, state='AWAITING_SIGSYS'):
        self.assertEqual(state, self.config.abi_state(self.f.record.mode))
        self.assertIsNotNone(self.model.stopped)
        self.assertIsNotNone(self.config.stopped)
        self.assertIsNotNone(self.launch.stopped)
        self.assertEqual(1, self.ledger.totals.live)
        self.assertEqual('reaped', self.ledger.identity(self.f.f.birth).terminal)
        self.assertEqual(1, len(self.owner.settled_reaps))
        self.f.f.wire.resume_syscall.assert_not_called()

    def test_both_modes_join_owned_epoch_without_extra_wait_or_resume(self):
        for mode in ('x32', 'compat'):
            for raw in (31, 31 | 0x80):
                self.setup_bound(mode=mode)
                self.consume_synthetic_entry()
                before = self.ledger.totals
                record = self.reap(raw)
                self.assertEqual('TERMINAL_FACT_MATCHED', self.config.abi_state(mode))
                self.assertIsNone(self.model.stopped)
                self.assertEqual(self.model._identities[mode], record.exec_identity)
                self.assertEqual(before.live - 1, self.ledger.totals.live)
                self.assertEqual(before.attempts, self.ledger.totals.attempts)
                self.assertEqual(before.wait_stops, self.ledger.totals.wait_stops)
                self.f.wait.assert_called_once()
                self.assertEqual([], self.f.f.wire.mock_calls)

    def test_thread_exec_joins_executor_birth_not_retired_leader(self):
        self.setup_bound(thread=True)
        self.consume_synthetic_entry()
        record = self.reap()
        self.assertEqual((2, 1, 11, 1), (record.exec_identity.birth,
                         record.exec_identity.group_birth, record.exec_identity.tid,
                         record.exec_identity.generation))
        self.assertEqual('exec-replaced', self.ledger.identity(1).terminal)
        self.assertEqual('TERMINAL_FACT_MATCHED', self.config.abi_state('x32'))

    def test_without_consumed_entry_terminal_latches_but_keeps_real_reap(self):
        with self.assertRaises(AbiModelError):
            self.reap()
        self.assert_retained_failure('BOUND')

    def test_normal_exit_or_other_signal_cannot_match_terminal(self):
        for raw in (0, 7 << 8, 9, 15):
            self.setup_bound()
            self.consume_synthetic_entry()
            with self.assertRaises(AbiModelError):
                self.reap(raw)
            self.assert_retained_failure()

    def test_unselected_birth_reap_does_not_consume_either_slot(self):
        self.setup_bound(selected=False)
        self.reap()
        self.assertEqual('UNBOUND', self.config.abi_state('x32'))
        self.assertEqual('UNBOUND', self.config.abi_state('compat'))
        self.assertIsNone(self.model.stopped)

    def test_missing_completed_observation_cannot_substitute_model_binding(self):
        self.consume_synthetic_entry()
        self.config.observations = ()
        with self.assertRaises(AbiObservationError):
            self.reap()
        self.assert_retained_failure()

    def test_mismatched_bound_identity_never_uses_reap_epoch_as_replacement(self):
        for field in ('birth', 'group_birth', 'tid', 'generation'):
            self.setup_bound()
            self.consume_synthetic_entry()
            original = self.model._identities['x32']
            self.model._identities['x32'] = replace(original, **{field: 90})
            with self.assertRaises(AbiModelError):
                self.reap()
            self.assert_retained_failure()

    def test_existing_model_failure_keeps_original_cause(self):
        self.consume_synthetic_entry()
        with self.assertRaises(AbiModelError):
            self.model.fail('original evidence failure')
        with self.assertRaises(AbiModelError):
            self.reap()
        self.assert_retained_failure()
        self.assertEqual('original evidence failure', self.model.stopped)

    def test_copied_retained_record_cannot_be_replayed(self):
        self.consume_synthetic_entry()
        record = self.reap()
        with self.assertRaises(WaitOwnerError):
            self.config.observe_reap(self.f.dispatch, replace(record))
        self.assert_retained_failure('TERMINAL_FACT_MATCHED')

    def test_original_record_cannot_consume_terminal_twice(self):
        self.consume_synthetic_entry()
        record = self.reap()
        with self.assertRaises(AbiModelError):
            self.config.observe_reap(self.f.dispatch, record)
        self.assert_retained_failure('TERMINAL_FACT_MATCHED')

    def test_foreign_owner_record_cannot_supply_terminal(self):
        self.consume_synthetic_entry()
        original_f = self.f
        record = self.reap()
        self.setup_bound()
        self.consume_synthetic_entry()
        with self.assertRaises(WaitOwnerError):
            self.config.observe_reap(self.f.dispatch, record)
        self.assertEqual('AWAITING_SIGSYS', self.config.abi_state('x32'))
        self.assertIsNotNone(self.model.stopped)
        self.assertTrue(self.ledger.identity(1).alive)
        self.assertIsNone(original_f.model.stopped)

    def test_raw_accept_terminal_cannot_create_model_fact(self):
        self.consume_synthetic_entry()
        self.f.dispatch.accept(11, 31)
        self.assertEqual((), self.owner.settled_reaps)
        self.assertEqual('AWAITING_SIGSYS', self.config.abi_state('x32'))

    def test_echild_never_creates_reap_or_terminal_fact(self):
        self.consume_synthetic_entry()
        self.f.wait.side_effect = ChildProcessError(errno.ECHILD, 'gone')
        with self.assertRaises(ChildProcessError):
            self.owner.run()
        self.assertEqual((), self.owner.settled_reaps)
        self.assertEqual('AWAITING_SIGSYS', self.config.abi_state('x32'))
        self.assertIsNotNone(self.launch.stopped)
        self.assertEqual(2, self.ledger.totals.live)

    def test_post_match_deadline_failure_retains_consumed_slot_and_real_reap(self):
        self.consume_synthetic_entry()
        observe = self.model.observe_terminal
        def expire(*args):
            observe(*args)
            self.f.f.fixture.clock.return_value = 100.0
        with patch.object(self.model, 'observe_terminal', side_effect=expire):
            with self.assertRaises(ValueError):
                self.reap()
        self.assert_retained_failure('TERMINAL_FACT_MATCHED')

    def test_shared_failure_before_join_keeps_reap_but_cannot_match(self):
        self.consume_synthetic_entry()
        join = self.config.observe_reap
        def fail(*args):
            self.launch.dispatcher.lifecycle.policy.stopped = 'evidence gate failed'
            return join(*args)
        with patch.object(self.config, 'observe_reap', side_effect=fail):
            with self.assertRaises(WaitOwnerError):
                self.reap()
        self.assert_retained_failure()

    def test_post_match_epoch_drift_latches_without_rollback(self):
        self.consume_synthetic_entry()
        observe = self.model.observe_terminal
        def drift(*args):
            observe(*args)
            self.f.dispatch._exec_generations[1] = 2
        with patch.object(self.model, 'observe_terminal', side_effect=drift):
            with self.assertRaises(WaitOwnerError):
                self.reap()
        self.assert_retained_failure('TERMINAL_FACT_MATCHED')


if __name__ == '__main__':
    unittest.main()
