"""Fake wait/kernel/files only; never execute a probe or read real proc."""
from dataclasses import replace
import unittest
from unittest.mock import Mock

from fixed_witness_replay_abi_observation import AbiObservationError
from fixed_witness_replay_exec_observation import OwnedExecObservations
from fixed_witness_replay_stop_dispatch import PostHandshakeDispatcher
from fixed_witness_replay_wait_owner import BoundedWaitOwner
from fixed_witness_replay_ptrace import decode_syscall_info
import test_fixed_witness_replay_stop_dispatch as dispatch_fixture
import test_fixed_witness_replay_abi_observation as file_fixture
from test_fixed_witness_replay_ptrace import entry, exit_info


class OwnedExecObservationTest(unittest.TestCase):
    def setup_session(self, *, thread=False, selected=True):
        self.fixture = dispatch_fixture.PostHandshakeDispatchTest()
        self.fixture.setUp()
        f = self.fixture
        self.launch, self.ledger, self.wire = f.launch, f.ledger, f.wire
        if thread:
            self.wire = f.exec_thread()
            birth = 2
        else:
            f.syscall(entry(59))
            f.wire.reset_mock()
            f.wire.event_tid.return_value = 11
            birth = 1
        files = file_fixture.AbiObservationTest()
        files.setUp()
        self.files, self.record = files.files, files.record
        self.files.owner, self.files.tasks = 10, ['10']
        # Same finite file bytes, with the migrated synthetic child's identity.
        for name in ('data', 'meta', 'links'):
            original = getattr(self.files, name)
            setattr(self.files, name, {key.replace('/proc/100/', '/proc/11/'): value
                                      for key, value in original.items()})
        self.files.data['/proc/11/stat'] = b'11 (probe) t 10 11 11 ' + b'0 ' * 15 + b'123\n'
        self.files.data['/proc/11/status'] = (b'State:\tt (tracing stop)\n'
                                             b'Pid:\t11\nTgid:\t11\nTracerPid:\t10\n')
        self.config = OwnedExecObservations(((birth if selected else 99, self.record),),
                                             files=self.files)
        # Reuse the fixture's completed handshake; replace only its unclaimed
        # post-handshake owner, preserving real composition state for this test.
        self.launch._post_handshake_claimed = False
        self.dispatch = PostHandshakeDispatcher(self.launch, exec_observations=self.config)
        self.wait = Mock(return_value=(11, 0x4057f))
        self.owner = BoundedWaitOwner(self.dispatch, waitpid=self.wait, sleep=Mock())
        self.birth = birth

    def setUp(self):
        self.setup_session()

    def step(self):
        return self.owner._round()

    def assert_failed(self):
        self.assertIsNotNone(self.launch.stopped)
        self.assertIsNotNone(self.config.stopped)
        self.wire.resume_syscall.assert_not_called()
        self.assertTrue(self.ledger.identity(self.birth).alive)
        self.assertEqual(11, self.ledger.identity(self.birth).tid)
        self.assertEqual((), self.config.observations)

    def test_actual_owned_exec_observes_before_single_normal_resume(self):
        before = self.ledger.totals
        self.files.hook = lambda *args: self.wire.resume_syscall.assert_not_called()
        self.assertEqual('event', self.step())
        observed, = self.config.observations
        self.assertEqual((1, 1, 11, 1), (observed.identity.birth,
                         observed.identity.group_birth, observed.identity.tid,
                         observed.identity.generation))
        self.assertEqual(before.wait_stops + 1, self.ledger.totals.wait_stops)
        self.assertEqual(before.attempts, self.ledger.totals.attempts)
        self.assertEqual(before.live, self.ledger.totals.live)
        self.wire.event_tid.assert_called_once_with(11)
        self.wire.resume_syscall.assert_called_once_with(11, 0)
        self.assertEqual({}, self.files.fds)

    def test_nonleader_exec_uses_executor_birth_and_wire_after_migration(self):
        self.setup_session(thread=True)
        self.step()
        observed, = self.config.observations
        self.assertEqual((2, 1, 11, 1), (observed.identity.birth,
                         observed.identity.group_birth, observed.identity.tid,
                         observed.identity.generation))
        self.assertEqual('exec-replaced', self.ledger.identity(1).terminal)
        self.fixture.wire.resume_syscall.assert_not_called()
        self.wire.resume_syscall.assert_called_once_with(11, 0)

    def test_direct_raw_accept_cannot_authenticate_observation(self):
        with self.assertRaises(AbiObservationError):
            self.dispatch.accept(11, 0x4057f)
        self.assert_failed()
        self.assertEqual([], self.files.opens)

    def test_unselected_exec_only_counts_epoch_without_file_access(self):
        self.setup_session(selected=False)
        self.step()
        self.assertEqual({1: 1}, self.dispatch._exec_generations)
        self.assertEqual([], self.files.opens)
        self.assertEqual((), self.config.observations)

    def test_selected_second_exec_is_not_a_new_observation_attempt(self):
        self.step()
        count = len(self.files.opens)
        for raw in (exit_info(0, 0), entry(59)):
            self.wire.syscall_info.return_value = decode_syscall_info(*raw)
            self.wait.return_value = (11, 0x857f)
            self.step()
        self.wire.reset_mock()
        self.wire.event_tid.return_value = 11
        self.wait.return_value = (11, 0x4057f)
        with self.assertRaisesRegex(AbiObservationError, 'again'):
            self.step()
        self.assertEqual(count, len(self.files.opens))
        self.assertEqual(2, self.dispatch._exec_generations[1])
        self.assertEqual(1, len(self.config.observations))
        self.wire.resume_syscall.assert_not_called()

    def test_proc_read_failure_retains_migrated_identity_and_no_retry(self):
        def fail(*args):
            raise OSError('fake read failed')
        self.files.hook = fail
        with self.assertRaises(OSError):
            self.step()
        self.assert_failed()
        with self.assertRaises(ValueError):
            self.step()
        self.wait.assert_called_once()

    def test_deadline_expiry_inside_read_closes_descriptor_and_blocks_resume(self):
        def expire(operation, path):
            if operation == 'read':
                self.fixture.clock.return_value = 100.0
        self.files.hook = expire
        with self.assertRaisesRegex(ValueError, 'deadline'):
            self.step()
        self.assert_failed()
        self.assertEqual({}, self.files.fds)
        self.assertEqual(1, len(self.files.closed))

    def test_owner_change_during_observation_fails_closed(self):
        self.files.hook = lambda *args: setattr(self.fixture.system.getpid, 'return_value', 99)
        with self.assertRaises(ValueError):
            self.step()
        self.assert_failed()
        self.assertEqual({}, self.files.fds)

    def test_shared_failure_latches_are_rechecked_each_io(self):
        for component in ('reader', 'dispatcher', 'lifecycle', 'policy'):
            self.setup_session()
            target = (self.launch.dispatcher.lifecycle.policy if component == 'policy'
                      else self.launch.dispatcher.lifecycle if component == 'lifecycle'
                      else getattr(self.launch, component))
            self.files.hook = lambda *args: setattr(target, 'stopped', 'fake shared failure')
            with self.assertRaises(AbiObservationError):
                self.step()
            self.assert_failed()

    def test_receipt_epoch_or_binding_drift_during_io_is_rejected(self):
        def drift_receipt():
            self.owner.last_wait = None
        def drift_epoch():
            self.dispatch._exec_generations[self.birth] += 1
        def drift_wire():
            self.launch.dispatcher._tasks[self.birth] = (11, Mock())
        for drift in (drift_receipt, drift_epoch, drift_wire):
            self.setup_session()
            self.files.hook = lambda *args: drift()
            with self.assertRaises(AbiObservationError):
                self.step()
            self.assert_failed()

    def test_proc_mismatch_does_not_publish_observation_or_release_credit(self):
        self.files.data['/proc/11/cmdline'] = b'wrong\0'
        before = self.ledger.totals.live
        with self.assertRaises(AbiObservationError):
            self.step()
        self.assert_failed()
        self.assertEqual(before, self.ledger.totals.live)

    def test_expired_wait_debits_without_observation_or_exec_migration(self):
        def wait(*args):
            self.fixture.clock.return_value = 100.0
            return (11, 0x4057f)
        self.wait.side_effect = wait
        before = self.ledger.totals.wait_stops
        with self.assertRaises(ValueError):
            self.step()
        self.assertEqual(before + 1, self.ledger.totals.wait_stops)
        self.assertEqual({}, self.dispatch._exec_generations)
        self.assertEqual([], self.files.opens)
        self.wire.resume_syscall.assert_not_called()

    def test_uncertain_resume_keeps_observation_but_outer_session_failed(self):
        self.wire.resume_syscall.side_effect = OSError('uncertain resume')
        with self.assertRaises(OSError):
            self.step()
        self.assertEqual(1, len(self.config.observations))
        self.assertIsNotNone(self.launch.stopped)
        with self.assertRaises(ValueError):
            self.step()
        self.wire.resume_syscall.assert_called_once()

    def test_configuration_rejects_duplicates_bad_birth_and_unfrozen_record(self):
        for records in ((), [], ((True, self.record),), ((0, self.record),),
                        ((1, self.record), (2, self.record)),
                        ((1, replace(self.record, source='relative')),)):
            with self.assertRaises(AbiObservationError):
                OwnedExecObservations(records, files=self.files)
        with self.assertRaises(AbiObservationError):
            self.config.claim(self.dispatch)

    def test_reused_config_does_not_consume_a_fresh_launch(self):
        fixture = dispatch_fixture.launch_fixture.CoordinatorLaunchTest()
        fixture.setUp()
        launch = fixture.launcher
        launch.launch()
        launch.accept_initial_wait(11, 0x137f)
        with self.assertRaises(AbiObservationError):
            PostHandshakeDispatcher(launch, exec_observations=self.config)
        self.assertFalse(getattr(launch, '_post_handshake_claimed', False))
        self.assertFalse(hasattr(launch, '_normal_wait_dispatcher'))
        fresh = OwnedExecObservations(((1, self.record),), files=self.files)
        dispatcher = PostHandshakeDispatcher(launch, exec_observations=fresh)
        waiter = BoundedWaitOwner(dispatcher, waitpid=Mock(return_value=(11, 0)), sleep=Mock())
        self.assertEqual('event', waiter._round())
        self.assertEqual('drained', waiter._round())
        self.assertEqual(1, launch.ledger.totals.live)


if __name__ == '__main__':
    unittest.main()
