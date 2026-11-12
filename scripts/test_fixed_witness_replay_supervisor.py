"""Actual composed owners with injected fork/wait/ptrace/pidfd only."""
import struct
import unittest
from unittest.mock import Mock, call, patch

from fixed_witness_replay_dispatch import CreationSyscallDispatcher
from fixed_witness_replay_exec_observation import OwnedExecObservations
from fixed_witness_replay_initial_wait import InitialWaitOwner, InitialWaitError
from fixed_witness_replay_ptrace import SignalInfo, PtraceAccess, decode_syscall_info
from fixed_witness_replay_network import encoded
from fixed_witness_replay_probe_launch import ProbeLaunch
from fixed_witness_replay_supervisor import TracerSupervisor, SupervisorError
from fixed_witness_replay_wait_owner import WAIT_OPTIONS
import test_fixed_witness_replay_launch as launch_fixture
import test_fixed_witness_replay_filter_exec_chain as chain_fixture
from test_fixed_witness_replay_ptrace import entry, exit_info


class SupervisorTest(unittest.TestCase):
    def setUp(self):
        self.f = launch_fixture.CoordinatorLaunchTest()
        self.f.setUp()
        self.launch, self.ledger = self.f.launcher, self.f.ledger
        self.io = Mock()
        self.io.wait.side_effect = [(11, 0x137f), (11, 0)]
        self.io.open.return_value = 70
        self.io.send.return_value = None

    def owner(self, **kwargs):
        return TracerSupervisor(
            self.launch, waitpid=self.io.wait, sleep=self.io.sleep,
            open_pidfd=self.io.open, send_signal=self.io.send,
            close=self.io.close, **kwargs)

    def test_launch_handshake_targeted_wait_and_drained_accounting(self):
        owner = self.owner()
        result = owner.run()
        self.assertTrue(owner.drained)
        self.assertEqual((2, 1, 0, 1),
                         (result.attempts, result.live, result.pending, result.wait_stops))
        self.f.system.fork.assert_called_once_with()
        self.assertEqual([call(11, WAIT_OPTIONS), call(11, WAIT_OPTIONS)],
                         self.io.wait.call_args_list)
        self.io.open.assert_not_called()
        self.assertIs(owner.waiter.dispatcher, owner.dispatcher)
        self.assertIs(owner.initial.launch, self.launch)
        self.assertIsNone(owner.failure)
        self.assertIsNone(owner.cleanup_error)

    def test_launch_is_exclusive_and_run_never_retries(self):
        owner = self.owner()
        with self.assertRaises(SupervisorError):
            self.owner()
        owner.run()
        with self.assertRaises(SupervisorError):
            owner.run()
        self.f.system.fork.assert_called_once_with()

    def test_invalid_or_claimed_config_rejects_before_claim_or_launch(self):
        with self.assertRaises(SupervisorError):
            self.owner(exec_observations=object())
        owner = self.owner()
        self.assertFalse(owner.started)
        self.f.system.fork.assert_not_called()

    def test_fork_failure_does_not_pin_or_wait_and_retains_exact_error(self):
        failure = OSError('fork failed')
        self.f.system.fork.side_effect = failure
        owner = self.owner()
        with self.assertRaises(OSError) as raised:
            owner.run()
        self.assertIs(raised.exception, failure)
        self.assertIs(owner.failure, failure)
        self.assertEqual((2, 1, 0), (self.ledger.totals.attempts,
                                   self.ledger.totals.live, self.ledger.totals.pending))
        self.assertIsNone(owner.cleanup)
        self.assertEqual([], self.io.mock_calls)

    def test_prefork_deadline_failure_never_creates_or_cleans(self):
        self.f.clock.return_value = 100.0
        owner = self.owner()
        with self.assertRaisesRegex(ValueError, 'deadline'):
            owner.run()
        self.f.system.fork.assert_not_called()
        self.assertEqual([], self.io.mock_calls)
        self.assertEqual(1, self.ledger.totals.attempts)

    def test_post_handshake_failure_uses_original_kill_reap_and_rethrows(self):
        failure = OSError('wait failed')
        self.io.wait.side_effect = [(11, 0x137f), failure, (11, 9)]
        owner = self.owner()
        with self.assertRaises(OSError) as raised:
            owner.run()
        self.assertIs(raised.exception, failure)
        self.assertIsNone(owner.cleanup_error)
        self.assertEqual([call.wait(11, WAIT_OPTIONS), call.wait(11, WAIT_OPTIONS),
                          call.open(11, 0), call.send(70, 9, None, 0),
                          call.wait(11, WAIT_OPTIONS), call.close(70)], self.io.mock_calls)
        self.assertEqual((1, 0), (self.ledger.totals.live, self.ledger.totals.pending))
        self.assertFalse(owner.drained)
        self.assertEqual(100.0, self.launch.deadline)
        with self.assertRaises(SupervisorError):
            owner.run()

    def test_cleanup_failure_keeps_original_exception_and_live_identity(self):
        original = OSError('normal wait failed')
        cleanup = OSError('pidfd failed')
        self.io.wait.side_effect = [(11, 0x137f), original]
        self.io.open.side_effect = cleanup
        owner = self.owner()
        with self.assertRaises(OSError) as raised:
            owner.run()
        self.assertIs(raised.exception, original)
        self.assertIs(owner.cleanup_error, cleanup)
        self.assertEqual(11, self.ledger.identity(1).tid)
        self.assertTrue(self.ledger.identity(1).alive)
        self.io.send.assert_not_called()
        self.assertEqual(2, self.io.wait.call_count)

    def test_expired_failure_has_no_new_cleanup_deadline(self):
        def wait(tid, options):
            self.f.clock.return_value = 100.0
            return (11, 0x137f)
        self.io.wait.side_effect = wait
        owner = self.owner()
        with self.assertRaisesRegex(ValueError, 'deadline'):
            owner.run()
        self.assertIsNotNone(owner.cleanup_error)
        self.io.open.assert_not_called()
        self.assertEqual(1, self.io.wait.call_count)
        self.assertEqual(1, self.ledger.totals.wait_stops)
        self.assertEqual(2, self.ledger.totals.live)

    def test_failed_handshake_retains_unsupported_cleanup_without_retry(self):
        self.f.access.signal_info.side_effect = OSError('siginfo failed')
        owner = self.owner()
        with self.assertRaisesRegex(ValueError, 'siginfo failed'):
            owner.run()
        self.assertIsNotNone(owner.cleanup_error)  # No synthetic pre-handshake pin.
        self.io.open.assert_not_called()
        self.assertEqual(1, self.io.wait.call_count)
        self.assertTrue(self.ledger.identity(1).alive)
        self.assertFalse(owner.drained)

    def test_base_exception_still_cleans_and_is_rethrown(self):
        failure = KeyboardInterrupt()
        self.io.wait.side_effect = [(11, 0x137f), failure, (11, 9)]
        owner = self.owner()
        with self.assertRaises(KeyboardInterrupt) as raised:
            owner.run()
        self.assertIs(raised.exception, failure)
        self.assertEqual(1, self.ledger.totals.live)
        self.assertIsNone(owner.cleanup_error)

    def test_claimed_observation_config_rejects_before_fork(self):
        f = chain_fixture.FilterExecChainTest()
        f.setUp()
        with self.assertRaises(SupervisorError):
            self.owner(exec_observations=f.config)
        self.f.system.fork.assert_not_called()
        self.assertFalse(getattr(self.launch, '_supervisor_claimed', False))

    def test_cleanup_close_error_is_separate_from_original_failure(self):
        original = OSError('normal wait failed')
        self.io.wait.side_effect = [(11, 0x137f), original, (11, 9)]
        self.io.close.side_effect = OSError('close failed')
        owner = self.owner()
        with self.assertRaises(OSError) as raised:
            owner.run()
        self.assertIs(raised.exception, original)
        self.assertIsNotNone(owner.cleanup_error)
        self.assertEqual(1, len(owner.cleanup.close_errors))
        self.assertEqual(1, self.ledger.totals.live)
        self.io.close.assert_called_once_with(70)

    def test_uncertain_creation_event_is_not_retried_by_automatic_cleanup(self):
        self.f.access.syscall_info.return_value = decode_syscall_info(*entry(57))
        self.f.access.event_tid.side_effect = OSError('uncertain event read')
        self.io.wait.side_effect = [(11, 0x137f), (11, 0x857f), (11, 0x1057f)]
        owner = self.owner()
        with self.assertRaisesRegex(ValueError, 'uncertain event read'):
            owner.run()
        self.assertIsNotNone(owner.cleanup_error)
        self.f.access.event_tid.assert_called_once_with(11)
        self.io.open.assert_not_called()
        self.io.send.assert_not_called()
        self.assertEqual((3, 3, 1), (self.ledger.totals.attempts,
                                   self.ledger.totals.live, self.ledger.totals.pending))
        self.assertEqual(3, self.io.wait.call_count)

    def test_thread_event_failure_before_binding_reuses_owned_stop_then_group_cleanup(self):
        raw, size = entry(56)
        raw = bytearray(raw)
        struct.pack_into('<Q', raw, 32, 0x10000 | 0x800 | 0x100)
        self.f.access.syscall_info.return_value = decode_syscall_info(bytes(raw), size)
        self.f.access.event_tid.return_value = 12
        original = OSError('event dispatch failed before read')
        owner = self.owner()
        self.io.wait.side_effect = [(11, 0x137f), (11, 0x857f), (11, 0x3057f),
                                   (11, 9), (12, 9)]
        # Retain the normal owner's charged stop and successful event read.
        # Only normal child binding fails; cleanup uses its own existing path.
        with patch.object(CreationSyscallDispatcher, 'child_event', side_effect=original):
            with self.assertRaises(OSError) as raised:
                owner.run()
        self.assertIs(raised.exception, original)
        self.assertIsNone(owner.cleanup_error)
        self.assertEqual((3, 1, 0), (self.ledger.totals.attempts,
                                   self.ledger.totals.live, self.ledger.totals.pending))
        self.f.access.event_tid.assert_called_once_with(11)
        self.io.open.assert_called_once_with(11, 0)
        self.io.send.assert_called_once_with(70, 9, None, 0)
        self.assertEqual(5, self.io.wait.call_count)

    def test_direct_initial_owner_rejects_claimed_or_stopped_before_wait_claim(self):
        f = chain_fixture.FilterExecChainTest()
        f.setUp()
        self.launch.launch()
        for config in (f.config, OwnedExecObservations(((1, f.record),), files=f.files)):
            if config._dispatcher is None:
                config.fail('prior failure')
            with self.subTest(config=config), self.assertRaisesRegex(InitialWaitError, 'fresh'):
                InitialWaitOwner(self.launch, waitpid=self.io.wait, exec_observations=config)
            self.assertFalse(getattr(self.launch, '_initial_wait_owner_claimed', False))
        self.io.wait.assert_not_called()
        self.f.access.resume_syscall.assert_not_called()
        self.assertEqual(0, self.ledger.totals.wait_stops)

    def test_config_claim_or_failure_after_initial_construction_prevents_first_wait(self):
        for changed in ('claimed', 'stopped'):
            with self.subTest(changed=changed):
                self.setUp()
                f = chain_fixture.FilterExecChainTest()
                f.setUp()
                config = OwnedExecObservations(((1, f.record),), files=f.files)
                self.launch.launch()
                initial = InitialWaitOwner(self.launch, waitpid=self.io.wait, exec_observations=config)
                if changed == 'claimed':
                    config.claim(object())
                else:
                    config.fail('prior failure')
                with self.assertRaisesRegex(InitialWaitError, 'fresh'):
                    initial.run()
                self.io.wait.assert_not_called()
                self.f.access.resume_syscall.assert_not_called()
                self.assertEqual(0, self.ledger.totals.wait_stops)
                self.assertIsNotNone(self.launch.stopped)

    def test_initial_config_is_installed_in_original_dispatcher(self):
        f = chain_fixture.FilterExecChainTest()
        f.setUp()
        config = OwnedExecObservations(((1, f.record),), files=f.files)
        self.launch.launch()
        initial = InitialWaitOwner(self.launch, waitpid=Mock(return_value=(11, 0x137f)),
                                   exec_observations=config)
        dispatch = initial.run()
        self.assertIs(config._dispatcher, dispatch)
        self.assertIs(dispatch._exec_observations, config)
        with self.assertRaises(InitialWaitError):
            initial.run()

    def test_frozen_two_exec_entry_reap_path_through_single_run(self):
        f = chain_fixture.FilterExecChainTest()
        f.setUp()
        config = OwnedExecObservations(
            ((1, f.record), (99, f.other)), files=f.files, bind_abi=True,
            network_launches=((1, f.plan),
                (99, ProbeLaunch(f.other, encoded(dict(f.net, command=list(f.other.argv)))))))
        self.f.access.event_tid.return_value = 11
        typed_wire = PtraceAccess(chain_fixture.wire_kernel(self.f.access))
        self.launch.access = typed_wire
        def waited(tid, options):
            index = self.io.wait.call_count
            if index == 1:
                self.f.access.signal_info.return_value = SignalInfo(19, 0, -6, 11, 1000)
                return (11, 0x137f)
            if index == 2:
                self.f.access.syscall_info.return_value = decode_syscall_info(*entry(59))
                return (11, 0x857f)
            index -= 1
            if index in (2, 5):
                if index == 5:
                    f.files.data['/proc/11/cmdline'] = b'\0'.join(
                        arg.encode() for arg in f.record.argv) + b'\0'
                    f.status(1, 2)
                return (11, 0x4057f)
            if index in (3, 6):
                self.f.access.syscall_info.return_value = decode_syscall_info(*exit_info(0, 0))
                return (11, 0x857f)
            if index in (4, 7):
                number = 59 if index == 4 else 0x40000029
                self.f.access.syscall_info.return_value = decode_syscall_info(*entry(number))
                return (11, 0x857f)
            if index == 8:
                return (11, 31)
            self.fail('unexpected extra wait')
        self.io.wait.side_effect = waited
        owner = self.owner(exec_observations=config)
        result = owner.run()
        self.assertEqual('TERMINAL_FACT_MATCHED', config.abi_state('x32'))
        self.assertFalse(typed_wire._unscoped_mutation)
        self.assertFalse(typed_wire._wire_failed)
        self.assertEqual(1, result.live)
        self.assertEqual(1, len(owner.waiter.settled_reaps))
        self.assertIs(config._dispatcher, owner.dispatcher)
        self.assertEqual(9, self.io.wait.call_count)
        self.assertTrue(owner.drained)
        self.io.open.assert_not_called()


if __name__ == '__main__':
    unittest.main()
