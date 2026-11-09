"""Synthetic wait/ptrace inputs only; no task creation or capability probe."""
import ctypes
import unittest
from unittest.mock import Mock

from fixed_witness_replay_ptrace import GETEVENTMSG, PtraceAccess, PtraceBoundaryError, SignalInfo, SyscallInfo
from fixed_witness_replay_stops import StopReadError, StopReader
from fixed_witness_replay_tasks import TaskLedger


class StopReaderTest(unittest.TestCase):
    def setUp(self):
        self.ledger = TaskLedger(99)
        self.reader = StopReader(self.ledger)
        self.access = Mock(spec=PtraceAccess)

    def read(self, word, tid=11):
        return self.reader.read(tid, word, self.access)

    def test_entry_read_after_debit(self):
        info = SyscallInfo(1, 0xc000003e, 10, 20, number=57)
        def reply(tid):
            self.assertEqual(1, self.ledger.totals.wait_stops)
            return info
        self.access.syscall_info.side_effect = reply
        self.assertIs(info, self.read(0x857f).syscall)
        self.assertEqual([('syscall_info', (11,), {})], self.access.mock_calls)

    def test_exit_is_kernel_operation_not_parity(self):
        info = SyscallInfo(2, 0xc000003e, 10, 20, result=-38, is_error=True)
        self.access.syscall_info.return_value = info
        self.assertIs(info, self.read(0x857f).syscall)
        self.assertIs(info, self.read(0x857f).syscall)
        self.assertEqual(2, self.ledger.totals.wait_stops)

    def test_abi_policy_remains_callers_responsibility(self):
        info = SyscallInfo(1, 0x40000003, 10, 20, number=20)
        self.access.syscall_info.return_value = info
        self.assertIs(info, self.read(0x857f).syscall)

    def test_event_messages_are_not_new_task_reservations(self):
        for event, name in ((1, 'FORK'), (2, 'VFORK'), (3, 'CLONE'), (4, 'EXEC')):
            self.access.event_tid.return_value = 12
            facts = self.read(event << 16 | 0x057f)
            self.assertEqual((name, 12), (facts.status.event, facts.event_tid))
        self.assertEqual((1, 1, 4), (self.ledger.totals.attempts,
                                   self.ledger.totals.live, self.ledger.totals.wait_stops))

    def test_signal_info_does_not_authorize_reinjection(self):
        info = SignalInfo(19, 0, -6)
        self.access.signal_info.return_value = info
        self.assertIs(info, self.read(0x137f).signal_info)
        self.access.signal_info.assert_called_once_with(11, 19)
        self.access.resume_syscall.assert_not_called()

    def test_event_uses_real_ptrace_wire(self):
        calls = []
        def request(operation, tid, address, data):
            self.assertEqual(1, self.ledger.totals.wait_stops)
            calls.append((operation, tid, address))
            ctypes.cast(data, ctypes.POINTER(ctypes.c_uint64))[0] = 12
            return 0
        access = PtraceAccess(request)
        facts = self.reader.read(11, 0x1057f, access)
        self.assertEqual(12, facts.event_tid)
        self.assertEqual([(GETEVENTMSG, 11, 0)], calls)

    def test_terminal_results_do_not_release_live_credit(self):
        reservation = self.ledger.reserve(0, 'coordinator')
        self.ledger.created(reservation, 11)
        self.ledger.finish(reservation, 11)
        for word, kind in ((0x0300, 'exited'), (9, 'signaled')):
            self.assertEqual(kind, self.read(word).status.kind)
        self.assertEqual((2, 0), (self.ledger.totals.live, self.ledger.totals.wait_stops))
        self.assertEqual([], self.access.mock_calls)

    def test_limit_stops_before_kernel_request(self):
        self.reader = StopReader(TaskLedger(99, wait_stops=1))
        with self.assertRaisesRegex(StopReadError, 'wait-stop limit'):
            self.read(0x857f)
        self.assertEqual([], self.access.mock_calls)

    def test_malformed_stop_is_charged_before_decode(self):
        for word in (0x00007f, 0x09057f, 0x1000000857f, -129):
            self.setUp()
            with self.assertRaises(StopReadError):
                self.read(word)
            self.assertEqual(1, self.ledger.totals.wait_stops)
            self.assertEqual([], self.access.mock_calls)

    def test_invalid_tid_still_charges_observed_stop(self):
        for tid in (0, True, 2**31):
            self.setUp()
            with self.assertRaisesRegex(StopReadError, 'TID'):
                self.read(0x857f, tid)
            self.assertEqual(1, self.ledger.totals.wait_stops)
            self.assertEqual([], self.access.mock_calls)

    def test_missing_operation_fails_closed(self):
        self.access.syscall_info.return_value = SyscallInfo(0, 0, 0, 0)
        with self.assertRaisesRegex(StopReadError, 'entry/exit'):
            self.read(0x857f)

    def test_transport_failure_latches_without_retry(self):
        for error in (PtraceBoundaryError('group stop'), OSError('ESRCH')):
            self.setUp()
            self.access.signal_info.side_effect = error
            with self.assertRaises(StopReadError):
                self.read(0x137f)
            with self.assertRaisesRegex(StopReadError, 'reader stopped'):
                self.read(0x857f)
            self.assertEqual(1, self.ledger.totals.wait_stops)
            self.assertEqual(1, len(self.access.mock_calls))

    def test_external_ledger_failure_rejects_terminal_receipt(self):
        try:
            self.ledger.reserve(0, 'fork')
        except ValueError:
            pass
        with self.assertRaisesRegex(StopReadError, 'ledger stopped'):
            self.read(0)
        self.assertEqual([], self.access.mock_calls)


if __name__ == '__main__':
    unittest.main()
