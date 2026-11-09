"""Synthetic initial waits and injected ptrace only; no native task/probe."""
import ctypes
import struct
import unittest
from unittest.mock import Mock

from fixed_witness_replay_dispatch import CreationSyscallDispatcher
from fixed_witness_replay_handshake import CoordinatorHandshake, CoordinatorHandshakeError
from fixed_witness_replay_lifecycle import CreationLifecycle
from fixed_witness_replay_ptrace import PtraceAccess, PtraceBoundaryError, SignalInfo, SyscallInfo
from fixed_witness_replay_stops import StopReader
from fixed_witness_replay_tasks import TaskLedger


class CoordinatorHandshakeTest(unittest.TestCase):
    def setUp(self, wait_stops=2_000_000):
        self.ledger = TaskLedger(99, wait_stops=wait_stops)
        self.ticket = self.ledger.reserve(0, 'coordinator')
        self.ledger.created(self.ticket, 11)
        self.ledger.finish(self.ticket, 11)
        self.access = Mock(spec=PtraceAccess)
        self.access.signal_info.return_value = SignalInfo(19, 0, -6, 11, 1000)
        self.dispatch = CreationSyscallDispatcher(
            CreationLifecycle(self.ledger), lambda: self.access, self.ticket, 11)
        self.reader = StopReader(self.ledger)
        self.handshake = self.make()

    def make(self, **kwargs):
        values = dict(reader=self.reader, dispatcher=self.dispatch, access=self.access,
                      ticket=self.ticket, tid=11, uid=1000)
        values.update(kwargs)
        return CoordinatorHandshake(**values)

    def accept(self):
        self.handshake.accept(11, 0x137f)

    def test_debit_siginfo_admission_options_resume_order(self):
        def read(tid, signal):
            self.assertEqual(1, self.ledger.totals.wait_stops)
            return SignalInfo(19, 0, -6, 11, 1000)
        def options(tid):
            self.assertIsNone(self.dispatch._sequence[self.ticket])
            self.assertFalse(self.dispatch._handshake_available)
        self.access.signal_info.side_effect = read
        self.access.set_options.side_effect = options
        self.accept()
        self.assertTrue(self.handshake.completed)
        self.assertEqual([('signal_info', (11, 19), {}), ('set_options', (11,), {}),
                          ('resume_syscall', (11, 0), {})], self.access.mock_calls)
        self.assertEqual((2, 2, 1), (self.ledger.totals.attempts,
                                   self.ledger.totals.live, self.ledger.totals.wait_stops))

    def test_self_kill_and_tgkill_are_admitted(self):
        for code in (0, -6):
            self.setUp()
            self.access.signal_info.return_value = SignalInfo(19, 0, code, 11, 1000)
            self.accept()
            self.access.resume_syscall.assert_called_once_with(11, 0)

    def test_foreign_or_missing_sender_and_wrong_header_fail_before_options(self):
        for info in (SignalInfo(19, 0, -6), SignalInfo(19, 0, -6, 12, 1000),
                     SignalInfo(19, 0, -6, 11, 1001), SignalInfo(19, 0, 128, 11, 1000),
                     SignalInfo(19, 1, 0, 11, 1000), SignalInfo(5, 0, 0, 11, 1000)):
            self.setUp()
            self.access.signal_info.return_value = info
            with self.assertRaises(CoordinatorHandshakeError):
                self.accept()
            self.access.set_options.assert_not_called()
            self.access.resume_syscall.assert_not_called()
            self.assertFalse(self.handshake.completed)

    def test_other_wait_kinds_never_resume(self):
        for word in (0x57f, 0x857f, 0x1057f, 0, 9):
            self.setUp()
            self.access.syscall_info.return_value = SyscallInfo(1, 0xc000003e, 10, 20, number=1)
            with self.assertRaises(CoordinatorHandshakeError):
                self.handshake.accept(11, word)
            self.access.set_options.assert_not_called()
            self.access.resume_syscall.assert_not_called()

    def test_wrong_wait_identity_is_charged_without_ptrace(self):
        for tid in (12, True, 0, 2**31):
            self.setUp()
            with self.assertRaises(CoordinatorHandshakeError):
                self.handshake.accept(tid, 0x137f)
            self.assertEqual(1, self.ledger.totals.wait_stops)
            self.assertEqual([], self.access.mock_calls)

    def test_malformed_wait_and_shared_cap_reject_before_ptrace(self):
        for word in (0x09057f, 0x10000137f):
            self.setUp()
            with self.assertRaises(CoordinatorHandshakeError):
                self.handshake.accept(11, word)
            self.assertEqual(1, self.ledger.totals.wait_stops)
            self.assertEqual([], self.access.mock_calls)
        self.setUp(wait_stops=1)
        with self.assertRaisesRegex(CoordinatorHandshakeError, 'wait-stop limit'):
            self.accept()
        self.assertEqual([], self.access.mock_calls)

    def test_siginfo_or_options_failure_never_resumes_and_cannot_retry(self):
        for method in ('signal_info', 'set_options'):
            self.setUp()
            getattr(self.access, method).side_effect = PtraceBoundaryError('injected failure')
            with self.assertRaises(CoordinatorHandshakeError):
                self.accept()
            self.access.resume_syscall.assert_not_called()
            calls = list(self.access.mock_calls)
            with self.assertRaisesRegex(CoordinatorHandshakeError, 'already attempted'):
                self.accept()
            self.assertEqual(calls, self.access.mock_calls)

    def test_uncertain_resume_failure_is_not_retried(self):
        self.access.resume_syscall.side_effect = OSError('injected failure')
        with self.assertRaises(CoordinatorHandshakeError):
            self.accept()
        with self.assertRaises(CoordinatorHandshakeError):
            self.accept()
        self.access.resume_syscall.assert_called_once_with(11, 0)
        self.assertFalse(self.handshake.completed)

    def test_duplicate_handshake_objects_cannot_resume_twice(self):
        self.accept()
        other = self.make()
        with self.assertRaises(CoordinatorHandshakeError):
            other.accept(11, 0x137f)
        self.access.set_options.assert_called_once_with(11)
        self.access.resume_syscall.assert_called_once_with(11, 0)
        self.assertEqual(2, self.ledger.totals.wait_stops)

    def test_wrong_birth_binding_fails_before_options(self):
        self.handshake = self.make(ticket=77)
        with self.assertRaises(CoordinatorHandshakeError):
            self.accept()
        self.access.set_options.assert_not_called()
        self.access.resume_syscall.assert_not_called()

    def test_different_ledger_and_invalid_uid_rejected_at_construction(self):
        with self.assertRaises(CoordinatorHandshakeError):
            self.make(reader=StopReader(TaskLedger(88)))
        for uid in (True, -1, 2**32, '1000'):
            with self.assertRaises(CoordinatorHandshakeError):
                self.make(uid=uid)
        self.assertEqual([], self.access.mock_calls)

    def test_real_wire_reads_sender_alignment_and_fixed_control_requests(self):
        requests = []
        def kernel(operation, tid, address, data):
            self.assertEqual(1, self.ledger.totals.wait_stops)
            requests.append((operation, tid, address, data if operation != 0x4202 else None))
            if operation == 0x4202:
                raw = bytearray(128)
                struct.pack_into('<iii', raw, 0, 19, 0, -6)
                struct.pack_into('<iI', raw, 16, 11, 0xffffffff)
                ctypes.memmove(data, bytes(raw), 128)
            return 0
        access = PtraceAccess(kernel)
        self.handshake = self.make(access=access, uid=0xffffffff)
        self.accept()
        self.assertEqual([(0x4202, 11, 0, None), (0x4200, 11, 0, 0x10001f),
                          (24, 11, 0, 0)], requests)

    def test_handshake_expects_first_entry_then_matching_exit(self):
        self.accept()
        self.access.syscall_info.return_value = SyscallInfo(1, 0xc000003e, 10, 20,
                                                         number=39, arguments=(0,) * 6)
        self.dispatch.syscall_stop(self.ticket, 11)
        self.access.syscall_info.return_value = SyscallInfo(2, 0xc000003e, 10, 20,
                                                         result=11, is_error=False)
        self.dispatch.syscall_stop(self.ticket, 11)
        self.assertIsNone(self.dispatch._sequence[self.ticket])
        from fixed_witness_replay_dispatch import CreationDispatchError
        with self.assertRaises(CreationDispatchError):
            self.dispatch.syscall_stop(self.ticket, 11)


if __name__ == '__main__':
    unittest.main()
