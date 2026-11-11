"""Finite native-tracer wire fixtures only; no libc, probes or task execution."""
from dataclasses import FrozenInstanceError
import ctypes
import struct
import unittest

import fixed_witness_replay_ptrace as p


def wire(signal=31, code=1, error=0, address=0xfedcba9876543210,
         syscall=0x40000029, arch=0xc000003e):
    return struct.pack('<iii4xQiI', signal, error, code, address, syscall, arch).ljust(128, b'\0')


class SiginfoWireTest(unittest.TestCase):
    def test_fixed_entries_keep_full_native_pointer_and_exact_abi_values(self):
        for arch, syscall in ((0xc000003e, 0x40000029), (0x40000003, 102)):
            info = p.decode_signal_info(wire(arch=arch, syscall=syscall), 31)
            self.assertEqual((31, 0, 1), (info.signal, info.error, info.code))
            self.assertEqual((0xfedcba9876543210, syscall, arch),
                             (info.call_address, info.syscall, info.arch))
            self.assertIsNone(info.sender_pid)
            self.assertIsNone(info.sender_uid)

    def test_signed_syscall_unsigned_arch_and_pointer_widths(self):
        for address in (0, 1 << 63, (1 << 64) - 1):
            for syscall in (-(1 << 31), -1, 0, (1 << 31) - 1):
                info = p.decode_signal_info(wire(address=address, syscall=syscall,
                                                  arch=0xffffffff), 31)
                self.assertEqual((address, syscall, 0xffffffff),
                                 (info.call_address, info.syscall, info.arch))

    def test_wrong_arch_and_syscall_are_preserved_without_masking_or_certifying(self):
        for arch, syscall in ((0, 102), (0xc000003e, 41), (0x40000003, 120),
                              (0x40000003, 0x40000029), (0xc000003e, -1)):
            info = p.decode_signal_info(wire(arch=arch, syscall=syscall), 31)
            self.assertEqual((arch, syscall), (info.arch, info.syscall))
            self.assertFalse(hasattr(info, 'authenticated'))

    def test_user_and_tkill_sigsys_only_decode_sender_union(self):
        for code in (0, -6):
            raw = bytearray(wire(code=code))
            struct.pack_into('<iI', raw, 16, 123, 0xffffffff)
            info = p.decode_signal_info(bytes(raw), 31)
            self.assertEqual((123, 0xffffffff), (info.sender_pid, info.sender_uid))
            self.assertEqual((None, None, None),
                             (info.call_address, info.syscall, info.arch))

    def test_unknown_and_other_kernel_sigsys_codes_never_guess_union(self):
        for code in (-1, -7, 2, 0x80, (1 << 31) - 1):
            self.assertEqual(p.SignalInfo(31, 0, code),
                             p.decode_signal_info(wire(code=code), 31))

    def test_sys_seccomp_number_on_another_signal_is_not_sigsys_union(self):
        for signal in (4, 5, 11, 17, 64):
            self.assertEqual(p.SignalInfo(signal, 0, 1),
                             p.decode_signal_info(wire(signal=signal), signal))

    def test_signed_header_and_nonzero_errno_are_not_rewritten(self):
        info = p.decode_signal_info(wire(error=-17), 31)
        self.assertEqual(-17, info.error)
        self.assertEqual(p.SignalInfo(31, -17, -6, 0x76543210, 0xfedcba98),
                         p.decode_signal_info(wire(code=-6, error=-17), 31))

    def test_all_truncated_and_oversized_buffers_reject_without_padding(self):
        raw = wire()
        for size in range(128):
            with self.subTest(size=size), self.assertRaises(p.PtraceBoundaryError):
                p.decode_signal_info(raw[:size], 31)
        with self.assertRaises(p.PtraceBoundaryError):
            p.decode_signal_info(raw + b'\0', 31)

    def test_mutable_buffers_and_wrong_expected_signal_types_reject(self):
        for raw in (bytearray(wire()), memoryview(wire()), None, 'x' * 128):
            with self.assertRaises(p.PtraceBoundaryError):
                p.decode_signal_info(raw, 31)
        for expected in (True, 0, 65, '31', 31.0, None, 30):
            with self.assertRaises(p.PtraceBoundaryError):
                p.decode_signal_info(wire(), expected)

    def test_decoded_facts_are_immutable(self):
        info = p.decode_signal_info(wire(), 31)
        with self.assertRaises(FrozenInstanceError):
            info.arch = 0x40000003

    def test_transport_only_reads_once_and_retains_kernel_union(self):
        calls = []
        raw = wire(arch=0x40000003, syscall=102)
        def kernel(request, tid, address, data):
            calls.append((request, tid, address))
            ctypes.memmove(data, raw, len(raw))
            return 0
        info = p.PtraceAccess(kernel).signal_info(123, 31)
        self.assertEqual(p.decode_signal_info(raw, 31), info)
        self.assertEqual([(0x4202, 123, 0)], calls)

    def test_transport_failure_or_mismatch_does_not_retry_or_resume(self):
        for result, raw in ((-1, wire()), (1, wire()), (0, wire(signal=30))):
            calls = []
            def kernel(request, tid, address, data):
                calls.append(request)
                ctypes.memmove(data, raw, len(raw))
                ctypes.set_errno(22)
                return result
            with self.assertRaises(p.PtraceBoundaryError):
                p.PtraceAccess(kernel).signal_info(123, 31)
            self.assertEqual([0x4202], calls)


if __name__ == '__main__':
    unittest.main()
