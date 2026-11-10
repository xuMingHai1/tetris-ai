"""Synthetic waits only: never creates tasks or calls the native kernel wire."""
import unittest
from unittest.mock import Mock, call

from fixed_witness_replay_initial_wait import InitialWaitOwner, InitialWaitError, WAIT_OPTIONS
from fixed_witness_replay_stop_dispatch import PostHandshakeDispatcher
import test_fixed_witness_replay_launch as fixture


class InitialWaitOwnerTest(unittest.TestCase):
    def setUp(self):
        self.fixture = fixture.CoordinatorLaunchTest()
        self.fixture.setUp()
        self.launch = self.fixture.launcher
        self.launch.launch()
        self.wait = Mock(return_value=(11, 0x137f))
        self.sleep = Mock()
        self.owner = InitialWaitOwner(self.launch, waitpid=self.wait, sleep=self.sleep)

    def test_real_tuple_routes_to_handshake_and_transfers_once(self):
        dispatch = self.owner.run()
        self.assertIs(type(dispatch), PostHandshakeDispatcher)
        self.assertIs(dispatch.launch, self.launch)
        self.wait.assert_called_once_with(11, WAIT_OPTIONS)
        self.assertEqual([call.signal_info(11, 19), call.set_options(11),
                          call.resume_syscall(11, 0)], self.fixture.access.mock_calls)
        self.assertEqual(1, self.fixture.ledger.totals.wait_stops)
        self.sleep.assert_not_called()
        with self.assertRaises(InitialWaitError):
            self.owner.run()
        with self.assertRaises(ValueError):
            PostHandshakeDispatcher(self.launch)

    def test_exclusive_claim_before_admission(self):
        with self.assertRaises(InitialWaitError):
            InitialWaitOwner(self.launch)
        self.wait.assert_not_called()

    def test_idle_and_eintr_do_not_charge_or_extend_deadline(self):
        self.wait.side_effect = [(0, 0), InterruptedError(), (11, 0x137f)]
        self.owner.run()
        self.assertEqual(3, self.wait.call_count)
        self.assertEqual([call(0.01), call(0.01)], self.sleep.call_args_list)
        self.assertEqual(1, self.fixture.ledger.totals.wait_stops)

    def test_sleep_is_capped_by_remaining_original_time(self):
        self.wait.return_value = (0, 0)
        self.fixture.clock.return_value = 99.997
        self.sleep.side_effect = lambda seconds: setattr(self.fixture.clock, 'return_value', 100.0)
        with self.assertRaisesRegex(ValueError, 'deadline'):
            self.owner.run()
        self.assertAlmostEqual(0.003, self.sleep.call_args.args[0])
        self.assertEqual(0, self.fixture.ledger.totals.wait_stops)
        self.fixture.access.resume_syscall.assert_not_called()

    def test_observed_stop_crossing_deadline_debits_without_ptrace(self):
        def waited(*args):
            self.fixture.clock.return_value = 100.0
            return (11, 0x137f)
        self.wait.side_effect = waited
        with self.assertRaises(ValueError):
            self.owner.run()
        self.assertEqual(1, self.fixture.ledger.totals.wait_stops)
        self.assertEqual([], self.fixture.access.mock_calls)
        self.assertEqual(11, self.launch.cleanup_tid)

    def test_foreign_wait_stop_debits_without_access_or_credit_release(self):
        self.wait.return_value = (12, 0x137f)
        with self.assertRaises(ValueError):
            self.owner.run()
        self.assertEqual(1, self.fixture.ledger.totals.wait_stops)
        self.assertEqual(2, self.fixture.ledger.totals.live)
        self.assertEqual([], self.fixture.access.mock_calls)

    def test_terminal_before_handshake_fails_without_resume(self):
        self.wait.return_value = (11, 0)
        with self.assertRaises(ValueError):
            self.owner.run()
        self.fixture.access.resume_syscall.assert_not_called()
        self.assertEqual(11, self.launch.cleanup_tid)

    def test_wait_error_latches_and_retains_cleanup(self):
        self.wait.side_effect = ChildProcessError('lost child')
        with self.assertRaises(ChildProcessError):
            self.owner.run()
        with self.assertRaises(InitialWaitError):
            self.owner.run()
        self.assertIsNotNone(self.launch.stopped)
        self.assertEqual(11, self.launch.cleanup_tid)
        self.assertEqual(2, self.fixture.ledger.totals.live)

    def test_malformed_results_never_resume(self):
        for result in (None, (11,), [11, 0x137f], (True, 0x137f), (0, 0x137f)):
            self.setUp()
            self.wait.return_value = result
            with self.assertRaises((ValueError, OSError)):
                self.owner.run()
            self.fixture.access.resume_syscall.assert_not_called()
            self.assertIsNotNone(self.launch.stopped)

    def test_siginfo_crossing_deadline_blocks_options_and_resume(self):
        def info(*args):
            self.fixture.clock.return_value = 100.0
            return fixture.SignalInfo(19, 0, -6, 11, 1000)
        self.fixture.access.signal_info.side_effect = info
        with self.assertRaisesRegex(ValueError, 'deadline'):
            self.owner.run()
        self.fixture.access.set_options.assert_not_called()
        self.fixture.access.resume_syscall.assert_not_called()
        self.assertEqual(1, self.fixture.ledger.totals.wait_stops)

    def test_options_crossing_deadline_blocks_resume(self):
        self.fixture.access.set_options.side_effect = lambda tid: setattr(
            self.fixture.clock, 'return_value', 100.0)
        with self.assertRaisesRegex(ValueError, 'deadline'):
            self.owner.run()
        self.fixture.access.set_options.assert_called_once_with(11)
        self.fixture.access.resume_syscall.assert_not_called()

    def test_owner_changes_during_options_blocks_resume(self):
        self.fixture.access.set_options.side_effect = lambda tid: setattr(
            self.fixture.system.getpid, 'return_value', 99)
        with self.assertRaisesRegex(ValueError, 'single-thread'):
            self.owner.run()
        self.fixture.access.resume_syscall.assert_not_called()

    def test_uncertain_resume_never_retries_or_transfers(self):
        self.fixture.access.resume_syscall.side_effect = OSError('uncertain')
        with self.assertRaises(ValueError):
            self.owner.run()
        self.fixture.access.resume_syscall.assert_called_once_with(11, 0)
        self.assertFalse(self.launch.handshake.completed)
        with self.assertRaises(InitialWaitError):
            self.owner.run()


if __name__ == '__main__':
    unittest.main()
