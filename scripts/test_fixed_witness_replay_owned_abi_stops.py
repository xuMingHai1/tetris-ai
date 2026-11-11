"""Fake complete EXEC/ENTRY/delivery/reap composition, no native execution."""
from dataclasses import replace
import unittest
from unittest.mock import patch

from fixed_witness_replay_abi_model import AbiModelError
from fixed_witness_replay_abi_observation import AbiObservationError
from fixed_witness_replay_ptrace import SignalInfo, decode_syscall_info
import test_fixed_witness_replay_exec_binding as fixture
from test_fixed_witness_replay_ptrace import entry, exit_info


class OwnedAbiStopsTest(unittest.TestCase):
    def setup_bound(self, *, mode='x32', thread=False, selected=True, finish_exec=True):
        self.f = fixture.OwnedExecBindingTest()
        self.f.setup_binding(mode=mode, thread=thread, selected=selected)
        f = self.f
        self.config, self.model, self.owner = f.config, f.model, f.owner
        self.launch, self.ledger, self.wire = f.f.launch, f.f.ledger, f.f.wire
        f.step()
        if finish_exec:
            self.syscall(exit_info(0, 0))
        self.wire.reset_mock()
        f.wait.reset_mock()

    def setUp(self):
        self.setup_bound()

    def syscall(self, raw):
        self.wire.syscall_info.return_value = decode_syscall_info(*raw)
        self.f.wait.return_value = (11, 0x857f)
        return self.f.step()

    def abi_entry(self):
        mode = self.f.record.mode
        return self.syscall(entry(0x40000029, 0xc000003e) if mode == 'x32'
                            else entry(102, 0x40000003))

    def delivery(self, info=None):
        arch, number = ((0xc000003e, 0x40000029) if self.f.record.mode == 'x32'
                        else (0x40000003, 102))
        self.wire.signal_info.return_value = info or SignalInfo(
            31, 0, 1, call_address=123, syscall=number, arch=arch)
        self.f.wait.return_value = (11, (31 << 8) | 0x7f)
        return self.f.step()

    def assert_failed(self, state='BOUND'):
        self.assertEqual(state, self.config.abi_state(self.f.record.mode))
        self.assertIsNotNone(self.model.stopped)
        self.assertIsNotNone(self.launch.stopped)
        self.assertTrue(self.ledger.identity(self.f.f.birth).alive)
        self.wire.resume_syscall.assert_not_called()

    def test_both_modes_complete_owned_path_with_optional_delivery(self):
        for mode in ('x32', 'compat'):
            for delivery in (False, True):
                for thread in (False, True):
                    self.setup_bound(mode=mode, thread=thread)
                    before = self.ledger.totals
                    self.abi_entry()
                    self.assertEqual('AWAITING_SIGSYS', self.config.abi_state(mode))
                    self.wire.syscall_info.assert_called_once_with(11)
                    self.wire.resume_syscall.assert_called_once_with(11, 0)
                    self.assertEqual(before.wait_stops + 1, self.ledger.totals.wait_stops)
                    self.assertEqual(before.attempts, self.ledger.totals.attempts)
                    if delivery:
                        self.wire.reset_mock()
                        self.delivery()
                        self.wire.signal_info.assert_called_once_with(11, 31)
                        self.wire.resume_syscall.assert_called_once_with(11, 31)
                    self.wire.reset_mock()
                    self.f.wait.return_value = (11, 31)
                    self.owner.run()
                    self.assertEqual('TERMINAL_FACT_MATCHED', self.config.abi_state(mode))
                    self.assertEqual(1, len(self.owner.settled_reaps))
                    self.assertEqual(before.live - 1, self.ledger.totals.live)
                    self.assertEqual([], self.wire.mock_calls)

    def test_native_sequence_keeps_existing_policy_before_exact_entry(self):
        self.syscall(entry(39))
        self.syscall(exit_info(11, 0))
        self.wire.reset_mock()
        self.abi_entry()
        self.wire.resume_syscall.assert_called_once_with(11, 0)

    def test_unfinished_native_entry_or_exec_exit_blocks_exception(self):
        for finish_exec in (False, True):
            self.setup_bound(finish_exec=finish_exec)
            if finish_exec:
                self.syscall(entry(39))
            self.wire.reset_mock()
            with self.assertRaises(AbiObservationError):
                self.abi_entry()
            self.assert_failed()

    def test_other_abi_numbers_and_wrong_mode_fail_without_masking(self):
        for raw in (entry(0x40000000 + 56), entry(0x80000029),
                    entry(102, 0x40000003), entry(2, 0x40000003)):
            self.setup_bound()
            with self.assertRaises(AbiModelError):
                self.syscall(raw)
            self.assert_failed()

    def test_unselected_birth_keeps_native_guard(self):
        self.setup_bound(selected=False)
        with self.assertRaises(ValueError):
            self.abi_entry()
        self.assertEqual('UNBOUND', self.config.abi_state('x32'))
        self.wire.resume_syscall.assert_not_called()

    def test_raw_accept_cannot_consume_bound_entry(self):
        self.wire.syscall_info.return_value = decode_syscall_info(*entry(0x40000029))
        with self.assertRaises(AbiObservationError):
            self.f.dispatch.accept(11, 0x857f)
        self.assert_failed()

    def test_copied_facts_cannot_replace_original_read(self):
        observe = self.config.observe_stop
        with patch.object(self.config, 'observe_stop', side_effect=lambda d, b, facts:
                          observe(d, b, replace(facts))):
            with self.assertRaises(AbiObservationError):
                self.abi_entry()
        self.assert_failed()

    def test_retained_receipt_after_dispatch_cannot_be_replayed(self):
        self.abi_entry()
        receipt = self.owner.last_wait
        self.wire.reset_mock()
        with self.assertRaises(AbiObservationError):
            self.config.observe_stop(self.f.dispatch, self.f.f.birth, receipt.facts)
        self.assert_failed('AWAITING_SIGSYS')

    def test_exec_epoch_drift_during_read_blocks_consumption(self):
        def drift(*args):
            self.f.dispatch._exec_generations[self.f.f.birth] += 1
            return decode_syscall_info(*entry(0x40000029))
        self.wire.syscall_info.side_effect = drift
        with self.assertRaises(AbiObservationError):
            self.abi_entry()
        self.assert_failed()

    def test_missing_completed_observation_cannot_supply_binding(self):
        self.config.observations = ()
        with self.assertRaises(AbiObservationError):
            self.abi_entry()
        self.assert_failed()

    def test_post_consumption_deadline_failure_keeps_used_slot(self):
        consume = self.model.consume_entry
        def expire(*args):
            consume(*args)
            self.f.f.fixture.clock.return_value = 100.0
        with patch.object(self.model, 'consume_entry', side_effect=expire):
            with self.assertRaises(ValueError):
                self.abi_entry()
        self.assert_failed('AWAITING_SIGSYS')

    def test_uncertain_resume_latches_consumed_slot_no_retry(self):
        self.wire.resume_syscall.side_effect = OSError('uncertain resume')
        with self.assertRaises(OSError):
            self.abi_entry()
        self.assertEqual('AWAITING_SIGSYS', self.config.abi_state('x32'))
        self.assertIsNotNone(self.model.stopped)
        self.wire.resume_syscall.assert_called_once_with(11, 0)
        self.wire.reset_mock()
        with self.assertRaises(ValueError):
            self.f.step()
        self.wire.resume_syscall.assert_not_called()

    def test_awaiting_rejects_entry_exit_creation_and_exec_before_policy(self):
        for raw in (entry(39), entry(56), entry(0x40000029), exit_info(0, 0), None):
            self.setup_bound()
            self.abi_entry()
            self.wire.reset_mock()
            before = self.ledger.totals
            with self.assertRaises(AbiObservationError):
                if raw is None:
                    self.wire.event_tid.return_value = 11
                    self.f.wait.return_value = (11, 0x4057f)
                    self.f.step()
                else:
                    self.syscall(raw)
            self.assert_failed('AWAITING_SIGSYS')
            self.assertEqual(before.attempts, self.ledger.totals.attempts)
            self.assertEqual(1, self.f.dispatch._exec_generations[self.f.f.birth])

    def test_sigsys_requires_consumption_and_exact_union(self):
        invalid = (SignalInfo(31, 0, 0, 11, 1000),
                   SignalInfo(31, 0, -6, 11, 1000),
                   SignalInfo(31, 0, 1, call_address=1, syscall=102, arch=0x40000003))
        for info in (None,) + invalid:
            self.setup_bound()
            if info is not None:
                self.abi_entry()
            self.wire.reset_mock()
            with self.assertRaises(AbiModelError):
                self.delivery(info)
            self.assert_failed('BOUND' if info is None else 'AWAITING_SIGSYS')

    def test_other_signal_preserved_without_consuming_another_ticket(self):
        self.abi_entry()
        self.wire.reset_mock()
        self.wire.signal_info.return_value = SignalInfo(10, 0, 0, 22, 1000)
        self.f.wait.return_value = (11, (10 << 8) | 0x7f)
        self.f.step()
        self.wire.resume_syscall.assert_called_once_with(11, 10)
        self.assertEqual('AWAITING_SIGSYS', self.config.abi_state('x32'))

    def test_shared_failure_after_delivery_prevents_resume_and_retains_entry(self):
        self.abi_entry()
        self.wire.reset_mock()
        observe = self.model.observe_sigsys
        def fail(*args):
            observe(*args)
            self.launch.dispatcher.lifecycle.policy.stopped = 'shared gate failed'
        with patch.object(self.model, 'observe_sigsys', side_effect=fail):
            with self.assertRaises(ValueError):
                self.delivery()
        self.assert_failed('AWAITING_SIGSYS')


if __name__ == '__main__':
    unittest.main()
