"""Initial failure provenance; injected waits only, no native launch."""
import unittest
from unittest.mock import Mock

from fixed_witness_replay_initial_wait import InitialWaitOwner
import test_fixed_witness_replay_launch as fixture


class InitialFailureTest(unittest.TestCase):
    def setUp(self):
        self.fixture = fixture.CoordinatorLaunchTest()
        self.fixture.setUp()
        self.launch = self.fixture.launcher
        self.launch.launch()
        self.wait = Mock(return_value=(11, 0x137f))
        self.owner = InitialWaitOwner(self.launch, waitpid=self.wait)

    def assert_stopped(self, reason):
        self.assertEqual(reason, self.launch.stopped)
        self.assertEqual('initial bounded wait failed; cleanup required', self.owner.stopped)
        self.assertEqual(11, self.launch.cleanup_tid)
        self.assertEqual((2, 2), (self.fixture.ledger.totals.attempts,
                                 self.fixture.ledger.totals.live))
        self.fixture.access.resume_syscall.assert_not_called()
        with self.assertRaisesRegex(ValueError, 'already attempted'):
            self.owner.run()
        self.assertEqual(reason, self.launch.stopped)

    def test_existing_failure_prevents_wait_and_keeps_original_reason(self):
        self.launch.stopped = 'original evidence quota failure'
        with self.assertRaisesRegex(ValueError, 'session stopped'):
            self.owner.run()
        self.assert_stopped('original evidence quota failure')
        self.wait.assert_not_called()

    def test_failure_during_wait_retains_first_reason_without_admission(self):
        def waited(*args):
            self.launch.stopped = 'original cancellation during wait'
            return (11, 0x137f)
        self.wait.side_effect = waited
        with self.assertRaisesRegex(ValueError, 'admission unavailable'):
            self.owner.run()
        self.assert_stopped('original cancellation during wait')
        self.assertFalse(self.launch.admitted)
        self.assertEqual(1, self.wait.call_count)
        self.assertEqual([], self.fixture.access.mock_calls)

    def test_admission_failure_keeps_launch_reason_and_shared_debit(self):
        self.wait.return_value = (12, 0x137f)
        with self.assertRaises(ValueError):
            self.owner.run()
        self.assert_stopped('coordinator initial admission failed; cleanup required')
        self.assertEqual(1, self.fixture.ledger.totals.wait_stops)
        self.assertEqual([], self.fixture.access.mock_calls)

    def test_wait_error_without_prior_failure_still_latches_session(self):
        error = ChildProcessError('lost child')
        self.wait.side_effect = error
        with self.assertRaises(ChildProcessError) as raised:
            self.owner.run()
        self.assertIs(error, raised.exception)
        self.assert_stopped('initial bounded wait failed; cleanup required')
        self.assertEqual(1, self.wait.call_count)


if __name__ == '__main__':
    unittest.main()
