"""Original wait/resume provenance fixtures only; no native operations."""
from dataclasses import replace
import unittest
from unittest.mock import Mock, patch

from fixed_witness_replay_abi_observation import AbiObservationError
from fixed_witness_replay_ptrace import decode_syscall_info
import test_fixed_witness_replay_owned_abi_stops as fixture
from test_fixed_witness_replay_ptrace import entry


class OwnedResumeTest(unittest.TestCase):
    def setUp(self):
        self.f = fixture.OwnedAbiStopsTest()
        self.f.setup_bound()
        self.config = self.f.config
        self.birth = self.f.f.f.birth

    def test_original_entry_receipt_matches_both_modes_and_thread_exec(self):
        for mode in ('x32', 'compat'):
            for thread in (False, True):
                self.f.setup_bound(mode=mode, thread=thread)
                self.config = self.f.config
                self.f.abi_entry()
                attempt, = self.config._entry_resumes.values()
                self.assertIs(attempt.receipt, self.f.owner.last_wait)
                self.assertIs(attempt.facts, attempt.receipt.facts)
                self.assertEqual(self.config._abi_model.manifest_sha256, attempt.manifest)
                self.config._require_entry_resume(attempt.identity)
                self.f.wire.resume_syscall.assert_called_once_with(11, 0)

    def test_model_consumption_without_actual_resume_cannot_match_reap(self):
        model = self.config._abi_model
        model.consume_entry('x32', model._identities['x32'], model.manifest_sha256,
                            decode_syscall_info(*entry(0x40000029)))
        self.f.f.wait.return_value = (11, 31)
        with self.assertRaises(AbiObservationError):
            self.f.owner.run()
        self.assertEqual('AWAITING_SIGSYS', model.state('x32'))
        self.assertEqual(1, len(self.f.owner.settled_reaps))
        self.assertEqual('reaped', self.f.ledger.identity(self.birth).terminal)

    def test_uncertain_wire_retains_attempt_without_completion_or_retry(self):
        self.f.wire.resume_syscall.side_effect = OSError('uncertain')
        with self.assertRaises(OSError):
            self.f.abi_entry()
        self.assertIn(self.birth, self.config._entry_resumes)
        self.assertNotIn(self.birth, self.config._completed_entries)
        self.assertIsNotNone(self.config.stopped)
        self.f.wire.resume_syscall.assert_called_once_with(11, 0)

    def test_post_resume_expiry_retains_returned_attempt_but_revokes_session(self):
        self.f.wire.resume_syscall.side_effect = lambda *args: setattr(
            self.f.f.f.fixture.clock, 'return_value', 100.0)
        with self.assertRaises(ValueError):
            self.f.abi_entry()
        self.assertIn(self.birth, self.config._completed_entries)
        self.assertIsNotNone(self.config.stopped)
        self.assertEqual('AWAITING_SIGSYS', self.config.abi_state('x32'))
        self.f.wire.resume_syscall.assert_called_once_with(11, 0)

    def intercept(self, transform):
        resume = self.config.resume
        return patch.object(self.config, 'resume', side_effect=lambda *args: resume(*transform(args)))

    def test_foreign_wire_cannot_resume(self):
        foreign = Mock()
        with self.intercept(lambda args: args[:3] + (foreign, args[4])):
            with self.assertRaises(AbiObservationError):
                self.f.abi_entry()
        self.f.wire.resume_syscall.assert_not_called()
        foreign.resume_syscall.assert_not_called()

    def test_copied_facts_cannot_resume(self):
        with self.intercept(lambda args: args[:2] + (replace(args[2]),) + args[3:]):
            with self.assertRaises(AbiObservationError):
                self.f.abi_entry()
        self.f.wire.resume_syscall.assert_not_called()

    def test_injected_entry_signal_never_reaches_wire(self):
        with self.intercept(lambda args: args[:4] + (31,)):
            with self.assertRaises(AbiObservationError):
                self.f.abi_entry()
        self.f.wire.resume_syscall.assert_not_called()

    def test_suppressed_delivery_never_reaches_wire(self):
        self.f.abi_entry()
        self.f.wire.reset_mock()
        with self.intercept(lambda args: args[:4] + (0,)):
            with self.assertRaises(AbiObservationError):
                self.f.delivery()
        self.f.wire.resume_syscall.assert_not_called()

    def test_delivery_retains_original_signal_receipt_without_new_entry(self):
        self.f.abi_entry()
        attempt = self.config._entry_resumes[self.birth]
        for _ in range(3):
            self.f.delivery()
            delivery = self.config._last_signal_resumes[self.birth]
            self.assertIs(self.f.owner.last_wait, delivery.receipt)
            self.assertEqual(31, delivery.signal)
        self.assertIs(attempt, self.config._entry_resumes[self.birth])
        self.assertEqual(1, len(self.config._last_signal_resumes))

    def test_retained_receipt_cannot_resume_again_after_dispatch(self):
        self.f.abi_entry()
        attempt = self.config._entry_resumes[self.birth]
        self.f.wire.reset_mock()
        with self.assertRaises(AbiObservationError):
            self.config.resume(self.f.f.dispatch, self.birth, attempt.facts, self.f.wire, 0)
        self.f.wire.resume_syscall.assert_not_called()

    def test_entry_receipt_drift_rejects_terminal_after_real_settlement(self):
        self.f.abi_entry()
        self.config._entry_resumes[self.birth].receipt.raw = 0x057f
        self.f.f.wait.return_value = (11, 31)
        with self.assertRaises(AbiObservationError):
            self.f.owner.run()
        self.assertEqual(1, len(self.f.owner.settled_reaps))
        self.assertEqual('AWAITING_SIGSYS', self.config.abi_state('x32'))

    def test_shared_failure_between_selection_and_resume_prevents_wire(self):
        resume = self.config.resume
        def fail(*args):
            self.f.launch.dispatcher.lifecycle.policy.stopped = 'shared failure'
            return resume(*args)
        with patch.object(self.config, 'resume', side_effect=fail):
            with self.assertRaises(ValueError):
                self.f.abi_entry()
        self.f.wire.resume_syscall.assert_not_called()


if __name__ == '__main__':
    unittest.main()
