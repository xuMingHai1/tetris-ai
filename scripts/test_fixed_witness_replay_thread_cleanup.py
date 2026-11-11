"""Original dispatch with injected kernel/wait/pidfd only; no native tasks."""
import struct
import unittest
from unittest.mock import Mock, call

from fixed_witness_replay_cleanup_reap import CleanupReapError, CleanupReaper
from fixed_witness_replay_ptrace import SignalInfo, decode_syscall_info
from fixed_witness_replay_settled_cleanup import SettledProcessCleanup
from fixed_witness_replay_tasks import TaskAccountingError
from fixed_witness_replay_wait_owner import WAIT_OPTIONS
from test_fixed_witness_replay_ptrace import entry, exit_info
import test_fixed_witness_replay_stop_dispatch as dispatch_fixture


class ThreadCleanupTest(unittest.TestCase):
    def setUp(self):
        self.f = dispatch_fixture.PostHandshakeDispatchTest()
        self.f.setUp()
        self.launch, self.ledger = self.f.launch, self.f.ledger
        raw, size = entry(56)
        raw = bytearray(raw)
        struct.pack_into('<Q', raw, 32, 0x10000 | 0x800 | 0x100)
        self.f.syscall((bytes(raw), size))
        self.f.wire.event_tid.return_value = 12
        self.f.owner.accept(11, 0x3057f)
        self.f.syscall(exit_info(12, 0))
        self.child = self.launch.dispatcher.stop_binding(12)[1]
        self.child.signal_info.return_value = SignalInfo(19, 0, 0, 0, 0)
        self.f.owner.accept(12, 0x137f)
        self.f.wire.reset_mock()
        self.child.reset_mock()
        self.transport = Mock()
        self.transport.open.side_effect = [70, 71]
        self.transport.send.return_value = None
        self.transport.wait.side_effect = [(11, 9), (12, 9)]

    def stop(self):
        self.launch.stopped = 'original failure'
        self.launch.dispatcher.stopped = 'original dispatch failure'
        self.ledger._stopped = 'original ledger failure'

    def owner(self):
        return SettledProcessCleanup(
            self.launch, open_pidfd=self.transport.open, send_signal=self.transport.send,
            close=self.transport.close, waitpid=self.transport.wait, sleep=Mock())

    def no_acquisition(self):
        self.transport.open.assert_not_called()
        self.transport.send.assert_not_called()
        self.transport.wait.assert_not_called()

    def test_one_group_pin_kill_then_each_birth_real_reap(self):
        self.stop()
        before = self.ledger.totals
        owner = self.owner()
        result = owner.run()
        self.assertEqual([(1, 11), (2, 12)], [(b, tid) for b, tid, _ in result])
        self.assertEqual([call.open(11, 0), call.send(70, 9, None, 0),
                          call.wait(11, WAIT_OPTIONS), call.wait(12, WAIT_OPTIONS),
                          call.close(70)], self.transport.mock_calls)
        self.assertEqual([1], owner.signalled)  # Group representative, not live credit.
        self.assertEqual((before.attempts, 1, before.wait_stops, before.stopped),
                         (self.ledger.totals.attempts, self.ledger.totals.live,
                          self.ledger.totals.wait_stops, self.ledger.totals.stopped))
        self.assertEqual('original failure', self.launch.stopped)
        self.assertEqual([], self.f.wire.mock_calls + self.child.mock_calls)

    def test_child_reaps_before_leader_with_idle_poll(self):
        self.stop()
        self.transport.wait.side_effect = [(0, 0), (12, 9), (11, 9)]
        result = self.owner().run()
        self.assertEqual([(2, 12), (1, 11)], [(b, tid) for b, tid, _ in result])
        self.transport.open.assert_called_once_with(11, 0)

    def test_multiple_groups_all_pin_before_signal_and_each_birth_reap(self):
        self.launch.dispatcher._factory = Mock(return_value=Mock())
        self.f.syscall(entry(57))
        self.f.wire.event_tid.return_value = 13
        self.f.owner.accept(11, 0x1057f)
        self.f.syscall(exit_info(13, 0))
        self.stop()
        self.transport.wait.side_effect = [(11, 9), (12, 9), (13, 9)]
        self.owner().run()
        self.assertEqual([call.open(11, 0), call.open(13, 0),
                          call.send(70, 9, None, 0), call.send(71, 9, None, 0)],
                         self.transport.mock_calls[:4])
        self.assertEqual(1, self.ledger.totals.live)

    def test_each_member_unmatched_exec_rejected_before_pin(self):
        for birth, number in ((1, 59), (2, 59), (1, 322), (2, 322)):
            with self.subTest(birth=birth, number=number):
                self.setUp()
                wire, tid = (self.f.wire, 11) if birth == 1 else (self.child, 12)
                wire.syscall_info.return_value = decode_syscall_info(*entry(number))
                self.f.owner.accept(tid, 0x857f)
                self.stop()
                with self.assertRaisesRegex(ValueError, 'thread/exec'):
                    self.owner().run()
                self.no_acquisition()
                self.assertEqual(3, self.ledger.totals.live)

    def test_completed_failed_exec_exit_is_stable(self):
        self.child.syscall_info.return_value = decode_syscall_info(*entry(59))
        self.f.owner.accept(12, 0x857f)
        self.child.syscall_info.return_value = decode_syscall_info(*exit_info(-2, 1))
        self.f.owner.accept(12, 0x857f)
        self.stop()
        self.owner().run()
        self.assertEqual(1, self.ledger.totals.live)

    def test_reaped_leader_before_pin_is_not_reopened(self):
        self.f.owner.accept(11, 0)
        self.stop()
        with self.assertRaisesRegex(ValueError, 'live leader'):
            self.owner().run()
        self.no_acquisition()

    def test_consumed_unsettled_terminal_member_blocks_whole_group(self):
        self.launch.retain_terminal_wait(12, 9)
        self.stop()
        with self.assertRaisesRegex(CleanupReapError, 'unresolved birth'):
            self.owner().run()
        self.no_acquisition()

    def test_unbound_member_wire_blocks_group_pin(self):
        self.launch.dispatcher._tasks[2] = (12, None)
        self.stop()
        with self.assertRaisesRegex(ValueError, 'thread binding'):
            self.owner().run()
        self.no_acquisition()

    def test_group_change_during_pin_closes_without_signal(self):
        def acquire(*args):
            self.ledger._tasks[2].tid = 99
            return 70
        self.transport.open.side_effect = acquire
        self.stop()
        with self.assertRaises(ValueError):
            self.owner().run()
        self.transport.close.assert_called_once_with(70)
        self.transport.send.assert_not_called()
        self.transport.wait.assert_not_called()

    def test_echild_after_group_kill_never_releases_member_or_retries(self):
        self.transport.wait.side_effect = [(11, 9), ChildProcessError('missing thread')]
        self.stop()
        owner = self.owner()
        with self.assertRaises(ChildProcessError):
            owner.run()
        with self.assertRaises(CleanupReapError):
            owner.run()
        self.assertEqual(2, self.ledger.totals.live)
        self.assertTrue(self.ledger.identity(2).alive)
        self.transport.open.assert_called_once_with(11, 0)
        self.transport.close.assert_called_once_with(70)

    def test_killed_group_stop_budget_stays_shared(self):
        self.ledger._event_limit = self.ledger.totals.wait_stops + 1
        self.transport.wait.side_effect = [(12, 0x137f)]
        self.stop()
        with self.assertRaises(TaskAccountingError):
            self.owner().run()
        self.assertEqual(3, self.ledger.totals.live)
        self.assertEqual('original ledger failure', self.ledger.totals.stopped)

    def test_standalone_reaper_does_not_claim_group_kill(self):
        self.stop()
        owner = CleanupReaper(self.launch, waitpid=self.transport.wait, sleep=Mock())
        with self.assertRaisesRegex(CleanupReapError, 'pinned group SIGKILL'):
            owner.run()
        self.no_acquisition()

    def test_thread_exec_event_after_kill_is_not_inferred_or_drained(self):
        self.transport.wait.side_effect = [(11, 0x4057f)]
        self.stop()
        with self.assertRaisesRegex(CleanupReapError, 'termination remains'):
            self.owner().run()
        self.assertEqual(3, self.ledger.totals.live)
        self.assertEqual([], self.f.wire.mock_calls + self.child.mock_calls)


if __name__ == '__main__':
    unittest.main()
