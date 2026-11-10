"""Composed synthetic launch/wait/exec sessions; never creates native tasks."""
import struct
import unittest
from unittest.mock import Mock, call

from fixed_witness_replay_creation import CLONE_VM, CLONE_VFORK, SIGCHLD
from fixed_witness_replay_initial_wait import InitialWaitOwner
from fixed_witness_replay_wait_owner import BoundedWaitOwner, WAIT_OPTIONS
from fixed_witness_replay_ptrace import SignalInfo, decode_syscall_info
import test_fixed_witness_replay_launch as launch_fixture
from test_fixed_witness_replay_ptrace import entry, exit_info


def native_entry(number, flags=0):
    raw, size = entry(number)
    raw = bytearray(raw)
    struct.pack_into('<Q', raw, 32, flags)
    return decode_syscall_info(bytes(raw), size)


class VforkSessionTest(unittest.TestCase):
    def setUp(self):
        self.fixture = launch_fixture.CoordinatorLaunchTest()
        self.fixture.setUp()
        self.launch, self.ledger = self.fixture.launcher, self.fixture.ledger
        self.parent, self.child = self.fixture.access, self.fixture.factory.return_value
        self.launch.launch()
        self.initial_wait = Mock(return_value=(11, 0x137f))
        dispatch = InitialWaitOwner(self.launch, waitpid=self.initial_wait).run()
        self.parent.reset_mock()
        self.child.signal_info.return_value = SignalInfo(19, 0, 0, 0, 0)
        self.parent.event_tid.return_value = 12
        self.child.event_tid.return_value = 12
        self.events = []
        self.consumed = []
        self.wait = Mock(side_effect=self.waitpid)
        self.sleep = Mock(side_effect=self.idle)
        self.owner = BoundedWaitOwner(dispatch, waitpid=self.wait, sleep=self.sleep)

    def idle(self, seconds):
        # A broken schedule must terminate under the ORIGINAL fake deadline.
        self.fixture.clock.return_value += seconds

    def waitpid(self, tid, options):
        self.assertEqual(WAIT_OPTIONS, options)
        self.assertIn(tid, (11, 12))
        # Ordered delivery is intentional: an unavailable parent's WNOHANG
        # cannot consume a child's stop or block servicing that child.
        if not self.events or self.events[0][0] != tid:
            return (0, 0)
        target, status, info, before = self.events.pop(0)
        if before is not None:
            before()
        if info is not None:
            (self.parent if target == 11 else self.child).syscall_info.return_value = info
        self.consumed.append((tid, status))
        return (tid, status)

    def stop(self, tid, status, info=None, before=None):
        self.events.append((tid, status, info, before))

    def start_vfork(self, syscall=58):
        flags = CLONE_VM | CLONE_VFORK | SIGCHLD if syscall == 56 else 0
        self.stop(11, 0x857f, native_entry(syscall, flags))
        self.stop(11, 0x2057f)
        self.stop(12, 0x137f)

    def exec_child(self, syscall=59, before=None):
        self.stop(12, 0x857f, native_entry(syscall))
        self.stop(12, 0x4057f, before=before)
        self.stop(12, 0x857f, decode_syscall_info(*exit_info(0, 0)))

    def parent_result(self, result=12):
        self.stop(11, 0x857f, decode_syscall_info(*exit_info(result, 0)))

    def drained(self):
        totals = self.owner.run()
        self.assertEqual([], self.events)
        self.assertEqual((3, 1, 0), (totals.attempts, totals.live, totals.pending))
        self.assertEqual(1 + sum(status & 0xff == 0x7f for _, status in self.consumed),
                         totals.wait_stops)
        self.assertEqual('reaped', self.ledger.identity(1).terminal)
        self.assertEqual('reaped', self.ledger.identity(2).terminal)
        self.fixture.system.fork.assert_called_once_with()
        self.initial_wait.assert_called_once_with(11, WAIT_OPTIONS)
        self.assertEqual(100.0, self.launch.deadline)
        self.assertIs(self.owner.launch.reader.ledger, self.ledger)
        return totals

    def test_vfork_exec_child_progresses_while_parent_result_unavailable(self):
        for creation in (58, 56):
            for execution in (59, 322):
                with self.subTest(creation=creation, execution=execution):
                    self.setUp()
                    self.start_vfork(creation)
                    def pending():
                        self.assertEqual((3, 3, 1), (self.ledger.totals.attempts,
                                                   self.ledger.totals.live,
                                                   self.ledger.totals.pending))
                        self.assertEqual(2, self.ledger.identity(2).group_birth)
                    self.exec_child(execution, pending)
                    self.stop(12, 0)
                    self.parent_result()
                    self.stop(11, 0)
                    self.drained()
                    self.assertEqual([call(12, 0)] * 4, self.child.resume_syscall.call_args_list)
                    self.child.set_options.assert_called_once_with(12)
                    self.child.event_tid.assert_called_once_with(12)
                    self.sleep.assert_not_called()

    def test_parent_result_can_precede_observed_child_exec_event(self):
        self.start_vfork()
        self.stop(12, 0x857f, native_entry(59))
        self.parent_result()
        self.stop(12, 0x4057f)
        self.stop(12, 0x857f, decode_syscall_info(*exit_info(0, 0)))
        self.stop(11, 0)
        self.stop(12, 0)
        self.drained()

    def test_child_exit_or_fatal_signal_precedes_parent_result(self):
        for creation in (58, 56):
            for terminal in (7 << 8, 9):
                with self.subTest(creation=creation, terminal=terminal):
                    self.setUp()
                    self.start_vfork(creation)
                    self.stop(12, terminal)
                    def reaped_child():
                        self.assertEqual((2, 1), (self.ledger.totals.live, self.ledger.totals.pending))
                        self.assertFalse(self.ledger.identity(2).alive)
                    self.stop(11, 0x857f, decode_syscall_info(*exit_info(12, 0)), reaped_child)
                    self.stop(11, 0)
                    self.drained()
                    self.child.event_tid.assert_not_called()
                    self.child.resume_syscall.assert_called_once_with(12, 0)

    def test_failed_exec_can_retry_before_releasing_parent(self):
        self.start_vfork()
        self.stop(12, 0x857f, native_entry(59))
        self.stop(12, 0x857f, decode_syscall_info(*exit_info(-2, 1)))
        self.exec_child(322)
        self.parent_result()
        self.stop(12, 0)
        self.stop(11, 0)
        self.drained()
        self.assertEqual(1, self.child.event_tid.call_count)

    def test_pending_vfork_cannot_extend_original_deadline(self):
        self.start_vfork()
        self.fixture.clock.return_value = 99.997
        with self.assertRaisesRegex(ValueError, 'deadline'):
            self.owner.run()
        self.assertEqual((3, 3, 1, 4), (self.ledger.totals.attempts, self.ledger.totals.live,
                                       self.ledger.totals.pending, self.ledger.totals.wait_stops))
        self.assertAlmostEqual(0.003, self.sleep.call_args.args[0])
        self.assertEqual(11, self.launch.cleanup_tid)
        self.assertIsNotNone(self.owner.stopped)

    def test_child_exec_stop_crossing_deadline_charges_without_access(self):
        self.start_vfork()
        def expired():
            self.fixture.clock.return_value = 100.0
        self.exec_child(before=expired)
        with self.assertRaisesRegex(ValueError, 'deadline'):
            self.owner.run()
        self.assertEqual((3, 3, 1, 6), (self.ledger.totals.attempts, self.ledger.totals.live,
                                       self.ledger.totals.pending, self.ledger.totals.wait_stops))
        self.child.event_tid.assert_not_called()
        self.assertEqual(2, self.child.resume_syscall.call_count)
        self.assertEqual(1, len(self.events))

    def test_shared_stop_cap_stops_child_before_exec_fact_read(self):
        self.ledger._event_limit = 6
        self.start_vfork()
        self.exec_child()
        with self.assertRaisesRegex(ValueError, 'wait-stop limit'):
            self.owner.run()
        self.child.event_tid.assert_not_called()
        self.assertEqual((3, 3, 1, 6), (self.ledger.totals.attempts, self.ledger.totals.live,
                                       self.ledger.totals.pending, self.ledger.totals.wait_stops))
        self.assertEqual(2, self.child.resume_syscall.call_count)

    def test_wrong_parent_result_after_child_reap_preserves_credit_and_latches(self):
        self.start_vfork()
        self.stop(12, 0)
        self.parent_result(99)
        self.stop(11, 0)
        with self.assertRaisesRegex(ValueError, 'matching child event'):
            self.owner.run()
        self.assertEqual((3, 2, 1), (self.ledger.totals.attempts, self.ledger.totals.live,
                                    self.ledger.totals.pending))
        self.assertEqual(2, self.parent.resume_syscall.call_count)
        self.assertIsNotNone(self.launch.stopped)
        calls = self.wait.call_count
        with self.assertRaises(ValueError):
            self.owner.run()
        self.assertEqual(calls, self.wait.call_count)
        self.assertEqual(1, len(self.events))

    def test_sigchld_during_pending_parent_preserves_vfork_result_obligation(self):
        self.start_vfork()
        self.stop(12, 0)
        self.parent.signal_info.return_value = SignalInfo(17, 0, 1, 12, 1000)
        self.stop(11, 0x117f)
        self.parent_result()
        self.stop(11, 0)
        self.drained()
        self.assertEqual([call(11, 0), call(11, 0), call(11, 17), call(11, 0)],
                         self.parent.resume_syscall.call_args_list)

    def test_vfork_reservation_exhaustion_never_releases_creation_entry(self):
        self.ledger._attempt_limit = 2
        self.start_vfork()
        with self.assertRaises(ValueError):
            self.owner.run()
        self.parent.resume_syscall.assert_not_called()
        self.parent.event_tid.assert_not_called()
        self.assertEqual([], self.child.mock_calls)
        self.assertEqual((2, 2, 0), (self.ledger.totals.attempts, self.ledger.totals.live,
                                    self.ledger.totals.pending))
        self.assertEqual(2, len(self.events))


if __name__ == '__main__':
    unittest.main()
