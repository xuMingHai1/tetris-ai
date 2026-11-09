"""Injected Linux wire replies only; never call libc, fork, wait or resume tasks."""
import ctypes
import struct
import unittest

import fixed_witness_replay_ptrace as p


class ControlKernel:
    def __init__(self):
        self.calls = []
        self.result = 0
        self.error = 1
        self.event = 123
        self.siginfo = (17, 0, -6)
        self.sender = (22, 1000)

    def __call__(self, request, tid, address, data):
        self.calls.append((request, tid, address, data))
        if self.result == -1:
            ctypes.set_errno(self.error)
            return -1
        if request == 0x4201:
            ctypes.memmove(data, struct.pack('<Q', self.event), 8)
        elif request == 0x4202:
            raw = bytearray(128)
            struct.pack_into('<iii', raw, 0, *self.siginfo)
            struct.pack_into('<iI', raw, 16, *self.sender)
            ctypes.memmove(data, bytes(raw), 128)
        return self.result


class PtraceControlTest(unittest.TestCase):
    def test_options_are_the_complete_frozen_linux_bitmask(self):
        kernel = ControlKernel()
        p.PtraceAccess(kernel).set_options(22)
        self.assertEqual([(0x4200, 22, 0, 0x10001f)], kernel.calls)

    def test_event_message_reads_full_native_word(self):
        kernel = ControlKernel()
        access = p.PtraceAccess(kernel)
        for tid in (1, 0x7fffffff):
            kernel.event = tid
            self.assertEqual(tid, access.event_tid(22))
        self.assertEqual((0x4201, 22, 0), kernel.calls[0][:3])

    def test_invalid_and_high_word_event_ids_fail(self):
        for tid in (0, 0x80000000, 0x100000001, 0xffffffffffffffff):
            kernel = ControlKernel()
            kernel.event = tid
            with self.subTest(tid=tid), self.assertRaises(p.PtraceBoundaryError):
                p.PtraceAccess(kernel).event_tid(22)

    def test_siginfo_preserves_signed_header_and_matches_wait_signal(self):
        kernel = ControlKernel()
        access = p.PtraceAccess(kernel)
        for code in (-6, 0, 1, 0x80):
            kernel.siginfo = (17, 0, code)
            expected = p.SignalInfo(17, 0, code, 22, 1000) if code in (-6, 0) else p.SignalInfo(17, 0, code)
            self.assertEqual(expected, access.signal_info(22, 17))
        self.assertEqual((0x4202, 22, 0), kernel.calls[0][:3])
        with self.assertRaisesRegex(p.PtraceBoundaryError, 'mismatch'):
            access.signal_info(22, 5)

    def test_group_stop_einval_never_becomes_delivery_permission(self):
        kernel = ControlKernel()
        kernel.result, kernel.error = -1, 22  # Linux EINVAL, independent of host.
        with self.assertRaisesRegex(p.PtraceBoundaryError, 'errno 22'):
            p.PtraceAccess(kernel).signal_info(22, 17)
        self.assertEqual(1, len(kernel.calls))

    def test_syscall_resume_carries_only_explicit_signal(self):
        kernel = ControlKernel()
        access = p.PtraceAccess(kernel)
        access.resume_syscall(22)
        access.resume_syscall(22, 17)
        self.assertEqual([(24, 22, 0, 0), (24, 22, 0, 17)], kernel.calls)

    def test_bad_signals_fail_before_transport(self):
        for signal in (True, -1, 65, '17', None, 1.0):
            for method in ('signal_info', 'resume_syscall'):
                kernel = ControlKernel()
                with self.subTest(signal=signal, method=method), self.assertRaises(p.PtraceBoundaryError):
                    getattr(p.PtraceAccess(kernel), method)(22, signal)
                self.assertEqual([], kernel.calls)
        kernel = ControlKernel()
        with self.assertRaises(p.PtraceBoundaryError):
            p.PtraceAccess(kernel).signal_info(22, 0)
        self.assertEqual([], kernel.calls)

    def test_bad_tids_fail_before_any_control_operation(self):
        for tid in (True, 0, -1, 2**31, '22'):
            for method, args in (('set_options', ()), ('event_tid', ()),
                                 ('signal_info', (17,)), ('resume_syscall', ())):
                kernel = ControlKernel()
                with self.subTest(tid=tid, method=method), self.assertRaises(p.PtraceBoundaryError):
                    getattr(p.PtraceAccess(kernel), method)(tid, *args)
                self.assertEqual([], kernel.calls)

    def test_control_errors_never_retry_or_fall_back_to_another_request(self):
        for method, args in (('set_options', ()), ('event_tid', ()),
                             ('signal_info', (17,)), ('resume_syscall', ())):
            for result in (-1, 1):
                kernel = ControlKernel()
                kernel.result = result
                with self.subTest(method=method, result=result), self.assertRaises(p.PtraceBoundaryError):
                    getattr(p.PtraceAccess(kernel), method)(22, *args)
                self.assertEqual(1, len(kernel.calls))


if __name__ == '__main__':
    unittest.main()
