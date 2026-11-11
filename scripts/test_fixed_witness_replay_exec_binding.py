"""Bounded fake-only EXEC-to-model composition; no native probe execution."""
from dataclasses import replace
import unittest
from unittest.mock import Mock, patch

from fixed_witness_replay_abi_model import AbiModelError
from fixed_witness_replay_abi_observation import AbiObservationError
from fixed_witness_replay_exec_observation import OwnedExecObservations
from fixed_witness_replay_stop_dispatch import PostHandshakeDispatcher
from fixed_witness_replay_wait_owner import BoundedWaitOwner
from fixed_witness_replay_ptrace import decode_syscall_info
import test_fixed_witness_replay_exec_observation as fixture
from test_fixed_witness_replay_ptrace import entry, exit_info


class OwnedExecBindingTest(unittest.TestCase):
    def setup_binding(self, *, mode='x32', thread=False, selected=True):
        self.f = fixture.OwnedExecObservationTest()
        self.f.setup_session(thread=thread)
        f = self.f
        record = replace(f.record, mode=mode, argv=f.record.argv[:-1] + (mode,))
        other_mode = 'compat' if mode == 'x32' else 'x32'
        other = replace(record, mode=other_mode, argv=record.argv[:-1] + (other_mode,))
        self.record, self.other = record, other
        f.files.data['/proc/11/cmdline'] = b'\0'.join(a.encode() for a in record.argv) + b'\0'
        self.config = OwnedExecObservations(
            ((f.birth if selected else 98, record), (99, other)), files=f.files, bind_abi=True)
        self.model = self.config._abi_model
        f.launch._post_handshake_claimed = False
        self.dispatch = PostHandshakeDispatcher(f.launch, exec_observations=self.config)
        self.wait = Mock(return_value=(11, 0x4057f))
        self.owner = BoundedWaitOwner(self.dispatch, waitpid=self.wait, sleep=Mock())

    def setUp(self):
        self.setup_binding()

    def step(self):
        return self.owner._round()

    def test_both_modes_bind_observed_identity_before_native_resume(self):
        for mode in ('x32', 'compat'):
            self.setup_binding(mode=mode)
            before = self.f.ledger.totals
            self.f.wire.resume_syscall.side_effect = lambda *args: self.assertEqual(
                'BOUND', self.config.abi_state(mode))
            self.assertEqual('event', self.step())
            observed, = self.config.observations
            self.assertEqual(observed.identity, self.model._identities[mode])
            self.assertEqual('UNBOUND', self.config.abi_state(self.other.mode))
            self.assertEqual(before.wait_stops + 1, self.f.ledger.totals.wait_stops)
            self.assertEqual(before.attempts, self.f.ledger.totals.attempts)
            self.assertEqual(before.live, self.f.ledger.totals.live)
            self.f.wire.resume_syscall.assert_called_once_with(11, 0)

    def test_thread_exec_binds_executor_birth_after_leader_retirement(self):
        self.setup_binding(thread=True)
        self.step()
        identity = self.model._identities['x32']
        self.assertEqual((2, 1, 11, 1), (identity.birth, identity.group_birth,
                                       identity.tid, identity.generation))
        self.assertEqual('exec-replaced', self.f.ledger.identity(1).terminal)

    def test_observation_only_default_does_not_construct_model(self):
        self.f.step()
        self.assertIsNone(self.f.config._abi_model)
        with self.assertRaises(AbiObservationError):
            self.f.config.abi_state('x32')

    def test_binding_requires_two_consistent_frozen_records(self):
        for records in (((1, self.record),),
                        ((1, self.record), (2, replace(self.other, source_sha256='0' * 64)))):
            with self.assertRaises(AbiModelError):
                OwnedExecObservations(records, bind_abi=True, files=self.f.files)
        for value in (1, None, 'true'):
            with self.assertRaises(AbiObservationError):
                OwnedExecObservations(((1, self.record),), bind_abi=value)

    def test_raw_accept_cannot_bind_and_latches_model(self):
        with self.assertRaises(AbiObservationError):
            self.dispatch.accept(11, 0x4057f)
        self.assertEqual('UNBOUND', self.config.abi_state('x32'))
        self.assertIsNotNone(self.model.stopped)
        self.assertEqual([], self.f.files.opens)
        self.f.wire.resume_syscall.assert_not_called()

    def test_mismatched_observed_argv_never_binds(self):
        self.f.files.data['/proc/11/cmdline'] = b'wrong\0'
        with self.assertRaises(AbiObservationError):
            self.step()
        self.assertEqual('UNBOUND', self.config.abi_state('x32'))
        self.assertIsNotNone(self.model.stopped)
        self.assertEqual((), self.config.observations)
        self.f.wire.resume_syscall.assert_not_called()

    def test_preexisting_model_failure_blocks_all_observation(self):
        with self.assertRaises(AbiModelError):
            self.model.fail('original failure')
        with self.assertRaises(AbiObservationError):
            self.step()
        self.assertEqual('original failure', self.model.stopped)
        self.assertEqual([], self.f.files.opens)
        self.f.wire.resume_syscall.assert_not_called()

    def test_model_failure_during_io_blocks_binding_and_closes_files(self):
        def fail(*args):
            if args[0] == 'read':
                self.model.fail('I/O gate failure')
        self.f.files.hook = fail
        with self.assertRaises(AbiModelError):
            self.step()
        self.assertEqual('UNBOUND', self.config.abi_state('x32'))
        self.assertEqual({}, self.f.files.fds)
        self.f.wire.resume_syscall.assert_not_called()

    def test_gate_failure_after_binding_retains_slot_and_latches_no_retry(self):
        bind = self.model.bind
        def expire(*args):
            bind(*args)
            self.f.fixture.clock.return_value = 100.0
        with patch.object(self.model, 'bind', side_effect=expire):
            with self.assertRaises(ValueError):
                self.step()
        self.assertEqual('BOUND', self.config.abi_state('x32'))
        self.assertIsNotNone(self.model.stopped)
        self.assertTrue(self.f.ledger.identity(self.f.birth).alive)
        self.assertEqual((), self.config.observations)
        with self.assertRaises(ValueError):
            self.step()
        self.wait.assert_called_once()
        self.f.wire.resume_syscall.assert_not_called()

    def test_binding_mismatch_keeps_outer_session_failed(self):
        # A consumed slot cannot be restored by another authenticated EXEC.
        identity = fixture.file_fixture.ExecIdentity(90, 90, 90, 1)
        self.model.bind('x32', identity, self.record)
        with self.assertRaises(AbiObservationError):
            self.step()
        self.assertEqual(identity, self.model._identities['x32'])
        self.assertIsNotNone(self.f.launch.stopped)
        self.f.wire.resume_syscall.assert_not_called()

    def test_second_exec_never_rebinds_or_reads_files_again(self):
        self.step()
        original = self.model._identities['x32']
        reads = len(self.f.files.opens)
        for raw in (exit_info(0, 0), entry(59)):
            self.f.wire.syscall_info.return_value = decode_syscall_info(*raw)
            self.wait.return_value = (11, 0x857f)
            self.step()
        self.f.wire.reset_mock()
        self.f.wire.event_tid.return_value = 11
        self.wait.return_value = (11, 0x4057f)
        with self.assertRaises(AbiObservationError):
            self.step()
        self.assertEqual(original, self.model._identities['x32'])
        self.assertEqual(reads, len(self.f.files.opens))
        self.assertIsNotNone(self.model.stopped)
        self.f.wire.resume_syscall.assert_not_called()

    def test_binding_does_not_enable_other_non_native_entry(self):
        self.step()
        self.f.wire.syscall_info.return_value = decode_syscall_info(*exit_info(0, 0))
        self.wait.return_value = (11, 0x857f)
        self.step()
        self.f.wire.syscall_info.return_value = decode_syscall_info(*entry(0x4000002a))
        self.wait.return_value = (11, 0x857f)
        self.f.wire.reset_mock()
        with self.assertRaises(ValueError):
            self.step()
        self.assertIsNotNone(self.f.launch.stopped)
        self.assertEqual('BOUND', self.config.abi_state('x32'))
        self.f.wire.resume_syscall.assert_not_called()

    def test_unselected_exec_never_binds_or_reads_files(self):
        self.setup_binding(selected=False)
        self.step()
        self.assertEqual('UNBOUND', self.config.abi_state('x32'))
        self.assertEqual([], self.f.files.opens)
        self.assertIsNone(self.model.stopped)


if __name__ == '__main__':
    unittest.main()
