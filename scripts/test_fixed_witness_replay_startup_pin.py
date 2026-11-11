"""Fake-only launch/pidfd/kill/reap composition; never loads libc or forks."""
import ctypes
import unittest
from unittest.mock import Mock, call, patch

from fixed_witness_replay_startup_pin import CoordinatorPin, StartupPinError, _KernelSigaction
from fixed_witness_replay_settled_cleanup import SettledProcessCleanup
from fixed_witness_replay_cleanup_reap import CleanupReaper, CleanupReapError
from fixed_witness_replay_initial_wait import InitialWaitOwner
from fixed_witness_replay_tasks import TaskAccountingError
from fixed_witness_replay_wait_owner import WAIT_OPTIONS
import test_fixed_witness_replay_launch as fixture


class StartupPinTest(unittest.TestCase):
    def setUp(self):
        self.fixture = fixture.CoordinatorLaunchTest()
        self.fixture.setUp()
        self.transport = Mock()
        self.transport.action.return_value = (0, 0)
        self.transport.open.return_value = 70
        self.transport.send.return_value = None
        self.transport.wait.return_value = (11, 9)
        self.pin = CoordinatorPin(self.transport.action, self.transport.open, self.transport.close)
        self.launch = self.fixture.make(startup_pin=self.pin)
        self.ledger = self.fixture.ledger
        self.sleep = Mock()

    def cleanup(self):
        return SettledProcessCleanup(self.launch, open_pidfd=self.transport.reopen,
                    send_signal=self.transport.send, close=self.transport.other_close,
                    waitpid=self.transport.wait, sleep=self.sleep)

    def test_fork_check_capture_precede_parent_binding_and_initial_wait(self):
        events = Mock()
        events.attach_mock(self.transport, 'pin')
        events.attach_mock(self.fixture.system.fork, 'fork')
        events.attach_mock(self.fixture.system.getuid, 'uid')
        self.launch.launch()
        self.assertEqual([call.pin.action(), call.fork(), call.pin.open(11, 0), call.uid()],
                         events.mock_calls)
        self.assertEqual((11, 70), (self.pin.pid, self.pin.fd))
        self.assertEqual((2, 2, 0), (self.ledger.totals.attempts, self.ledger.totals.live,
                                    self.ledger.totals.pending))

    def test_ignore_custom_handler_nocldwait_or_malformed_query_prevents_fork(self):
        for action in ((1, 0), (1234, 0), (0, 2), (0, True), (False, 0), (0, -1),
                       [0, 0], (0,), None):
            with self.subTest(action=action):
                self.setUp()
                self.transport.action.return_value = action
                with self.assertRaises(StartupPinError):
                    self.launch.launch()
                self.fixture.system.fork.assert_not_called()
                self.transport.open.assert_not_called()
                self.assertEqual(1, self.ledger.totals.attempts)
                with self.assertRaises(ValueError):
                    self.launch.launch()

    def test_query_failure_and_expiry_never_start_a_child(self):
        for kind in ('query', 'deadline'):
            self.setUp()
            if kind == 'query':
                self.transport.action.side_effect = OSError('query unavailable')
            else:
                self.transport.action.side_effect = lambda: (
                    setattr(self.fixture.clock, 'return_value', 100.0) or (0, 0))
            with self.assertRaises((OSError, ValueError)):
                self.launch.launch()
            self.fixture.system.fork.assert_not_called()
            self.assertEqual(1, self.ledger.totals.attempts)

    def test_failed_fork_never_acquires_pin_or_refunds_attempt(self):
        self.fixture.system.fork.side_effect = OSError('failed')
        with self.assertRaises(OSError):
            self.launch.launch()
        self.transport.open.assert_not_called()
        self.assertEqual((2, 1), (self.ledger.totals.attempts, self.ledger.totals.live))

    def test_failed_open_retains_pid_and_live_duty_without_retry(self):
        for result in (OSError('failed'), InterruptedError('uncertain'), True, -1, None):
            self.setUp()
            if isinstance(result, BaseException):
                self.transport.open.side_effect = result
            else:
                self.transport.open.return_value = result
            with self.assertRaises((OSError, StartupPinError)):
                self.launch.launch()
            self.assertEqual(11, self.launch.cleanup_tid)
            self.assertEqual(11, self.pin.pid)
            self.assertEqual(2, self.ledger.totals.live)
            with self.assertRaises(ValueError):
                self.launch.launch()
            self.transport.open.assert_called_once_with(11, 0)
            self.transport.send.assert_not_called()

    def test_parent_setup_failure_uses_retained_pin_kill_and_real_reap(self):
        self.fixture.system.getuid.side_effect = OSError('parent setup')
        with self.assertRaises(OSError):
            self.launch.launch()
        original = self.launch.stopped
        result = self.cleanup().run()
        self.assertEqual([(1, 11)], [(birth, tid) for birth, tid, _ in result])
        self.assertEqual(original, self.launch.stopped)
        self.assertEqual((2, 1), (self.ledger.totals.attempts, self.ledger.totals.live))
        self.assertEqual([call.action(), call.open(11, 0), call.send(70, 9, None, 0),
                          call.wait(11, WAIT_OPTIONS), call.close(70)], self.transport.mock_calls)
        self.fixture.access.resume_syscall.assert_not_called()

    def test_bounded_initial_wait_failure_cleans_without_new_wait_owner_or_pid_lookup(self):
        self.launch.launch()
        initial = InitialWaitOwner(self.launch, waitpid=Mock(side_effect=OSError('wait failed')))
        with self.assertRaises(OSError):
            initial.run()
        result = self.cleanup().run()
        self.assertEqual('signaled', result[0][2].kind)
        self.transport.reopen.assert_not_called()
        self.assertIsNone(self.pin.fd)
        self.assertTrue(self.pin.released)
        with self.assertRaises(ValueError):
            self.cleanup()

    def test_failed_options_or_uncertain_resume_keeps_pin_for_cleanup(self):
        for operation in ('set_options', 'resume_syscall'):
            self.setUp()
            self.launch.launch()
            getattr(self.fixture.access, operation).side_effect = OSError('uncertain')
            with self.assertRaises(ValueError):
                self.launch.accept_initial_wait(11, 0x137f)
            self.assertEqual(70, self.pin.fd)
            self.cleanup().run()
            getattr(self.fixture.access, operation).assert_called_once()
            self.transport.reopen.assert_not_called()

    def test_successful_handshake_releases_startup_pin_once(self):
        self.launch.launch()
        self.launch.accept_initial_wait(11, 0x137f)
        self.assertTrue(self.launch.handshake.completed)
        self.assertIsNone(self.pin.fd)
        self.transport.close.assert_called_once_with(70)
        self.pin.release()
        self.transport.close.assert_called_once_with(70)

    def test_terminal_initial_wait_quarantines_pid_closes_pin_and_forbids_kill(self):
        for raw in (0, 9, 0x80 | 9):
            self.setUp()
            self.launch.launch()
            with self.assertRaises(ValueError):
                self.launch.accept_initial_wait(11, raw)
            self.assertEqual({11}, self.launch.unsettled_terminal_waits)
            self.transport.close.assert_called_once_with(70)
            with self.assertRaises(ValueError):
                self.cleanup()
            self.transport.send.assert_not_called()
            self.transport.wait.assert_not_called()
            self.assertEqual(2, self.ledger.totals.live)

    def test_pin_survives_ledger_binding_failure_without_false_live_release(self):
        with patch.object(self.ledger, 'created', side_effect=TaskAccountingError('binding')):
            with self.assertRaises(TaskAccountingError):
                self.launch.launch()
        self.assertEqual(70, self.pin.fd)
        with self.assertRaises(ValueError):
            self.cleanup()
        self.transport.send.assert_not_called()
        self.assertEqual(2, self.ledger.totals.live)
        self.pin.release()  # Release descriptor, not task credit or a reap claim.
        self.assertEqual(2, self.ledger.totals.live)

    def test_cleanup_expiry_after_transfer_closes_pin_without_reopening_or_wait(self):
        self.launch.launch()
        def send(*args):
            self.fixture.clock.return_value = 100.0
        self.transport.send.side_effect = send
        owner = self.cleanup()
        with self.assertRaisesRegex(ValueError, 'deadline'):
            owner.run()
        self.transport.close.assert_called_once_with(70)
        self.transport.reopen.assert_not_called()
        self.transport.wait.assert_not_called()
        self.assertEqual([1], owner.signalled)
        self.assertEqual(2, self.ledger.totals.live)

    def test_cleanup_drains_shared_stops_and_never_resumes_after_startup_failure(self):
        self.launch.launch()
        self.launch.stopped = 'original startup failure'
        self.transport.wait.side_effect = [(11, 0x137f), (11, 9)]
        owner = self.cleanup()
        owner.run()
        self.assertEqual((1, 1), (self.ledger.totals.live, self.ledger.totals.wait_stops))
        self.assertEqual(1, owner.reaper.drained_stops)
        self.fixture.access.resume_syscall.assert_not_called()
        self.assertEqual('original startup failure', self.launch.stopped)

    def test_standalone_terminal_reaper_releases_startup_pin(self):
        self.launch.launch()
        CleanupReaper(self.launch, waitpid=self.transport.wait, sleep=self.sleep).run()
        self.transport.close.assert_called_once_with(70)
        self.assertEqual(1, self.ledger.totals.live)

    def test_close_failure_is_one_shot_and_does_not_replace_initial_error(self):
        self.launch.launch()
        self.transport.close.side_effect = InterruptedError('uncertain close')
        with self.assertRaisesRegex(ValueError, 'self-SIGSTOP'):
            self.launch.accept_initial_wait(11, 9)
        self.assertIsInstance(self.launch.startup_pin_close_error, InterruptedError)
        self.pin.release()
        self.transport.close.assert_called_once_with(70)

    def test_kernel_query_is_readonly_exact_native_layout_and_never_sets_disposition(self):
        libc = Mock()
        def syscall(number, sig, new_action, old_action, mask_size):
            self.assertEqual((13, 17, None, 8),
                             (number.value, sig.value, new_action.value, mask_size.value))
            value = ctypes.cast(old_action, ctypes.POINTER(_KernelSigaction)).contents
            value.handler, value.flags = 0, 0
            return 0
        libc.syscall.side_effect = syscall
        with patch('fixed_witness_replay_startup_pin.sys.platform', 'linux'), \
                patch('fixed_witness_replay_startup_pin.platform.machine', return_value='x86_64'), \
                patch('fixed_witness_replay_startup_pin.ctypes.CDLL', return_value=libc), \
                patch('fixed_witness_replay_startup_pin.os.pidfd_open', create=True) as opened:
            pin = CoordinatorPin.for_host()
            pin.prepare()
            opened.assert_not_called()
        self.assertEqual(32, ctypes.sizeof(_KernelSigaction))
        libc.syscall.assert_called_once()


if __name__ == '__main__':
    unittest.main()
