"""Kernel-wire fixtures and fake ptrace only; no native calls or child tasks."""
import ctypes
import errno
import struct
import unittest
from unittest.mock import patch

import fixed_witness_replay_ptrace as p


def entry(number=435, arch=0xc000003e):
    return struct.pack('<B3xIQQ7Q', 1, arch, 0x401002, 0x7fff0000,
                       number, 1, 2, 3, 4, 5, 6).ljust(88, b'\0'), 80


def exit_info(result=-38, error=1, arch=0xc000003e):
    return struct.pack('<B3xIQQqB', 2, arch, 0x401002, 0x7fff0000,
                       result, error).ljust(88, b'\0'), 33


class KernelFixture:
    def __init__(self):
        self.info, self.available = entry()
        self.registers = [0x100 + i for i in range(27)]
        self.registers[15] = 435  # orig_rax, from Linux user_regs_struct order
        self.registers[16] = 0x401002
        self.registers[17] = 0x33
        self.registers[19] = 0x7fff0000
        self.read_size = 216
        self.writes = []
        self.requests = []
        self.failure = None

    def __call__(self, request, tid, address, data):
        self.requests.append((request, tid, address))
        if request == self.failure:
            ctypes.set_errno(errno.EPERM)
            return -1
        if request == 0x420e:
            assert address == 88
            ctypes.memmove(data, self.info, 88)
            return self.available
        assert address == 1
        vector = ctypes.cast(data, ctypes.POINTER(p._Iovec)).contents
        assert vector.length == 216
        if request == 0x4204:
            ctypes.memmove(vector.base, struct.pack('<27Q', *self.registers), 216)
            vector.length = self.read_size
        elif request == 0x4205:
            self.registers = list(struct.unpack('<27Q', ctypes.string_at(vector.base, 216)))
            self.writes.append(tuple(self.registers))
        else:
            raise AssertionError('unexpected ptrace operation')
        return 0

    def suppressed_exit(self):
        self.info, self.available = exit_info()
        self.registers[10] = (1 << 64) - 38


class PtraceBoundaryTest(unittest.TestCase):
    def test_entry_uses_kernel_operation_and_unsigned_arguments(self):
        raw, size = entry()
        info = p.decode_syscall_info(raw, size)
        self.assertEqual(435, info.number)
        self.assertEqual((1, 2, 3, 4, 5, 6), info.arguments)
        self.assertIsNone(info.result)
        info.require_native(1)

    def test_exit_signed_result_and_real_errno_bit(self):
        for result, error in ((-38, 1), (-4095, 1), (1234, 0), (0, 0)):
            info = p.decode_syscall_info(*exit_info(result, error))
            self.assertEqual((result, bool(error)), (info.result, info.is_error))
            self.assertIsNone(info.number)
            info.require_native(2)

    def test_zero_filled_buffer_never_hides_truncated_kernel_reply(self):
        for raw, size in ((entry()[0], 79), (exit_info()[0], 32),
                          (entry()[0], 23), (entry()[0], 89),
                          (entry()[0][:79], 80), (entry()[0], True)):
            with self.subTest(size=size), self.assertRaises(p.PtraceBoundaryError):
                p.decode_syscall_info(raw, size)

    def test_none_seccomp_and_unknown_operations_are_not_entry_exit(self):
        for operation in (0, 3, 255):
            raw = bytes([operation]) + entry()[0][1:]
            with self.subTest(operation=operation), self.assertRaises(p.PtraceBoundaryError):
                p.decode_syscall_info(raw, 88)

    def test_invalid_errno_encoding_is_rejected(self):
        for result, error in ((0, 1), (12, 1), (-4096, 1), (-38, 2)):
            with self.subTest(result=result, error=error), self.assertRaises(p.PtraceBoundaryError):
                p.decode_syscall_info(*exit_info(result, error))

    def test_compat_and_x32_are_preserved_but_not_authorized(self):
        for raw, size in (entry(120, 0x40000003), entry(0x400001b3),
                          entry((1 << 64) - 1), exit_info(arch=0x40000003)):
            info = p.decode_syscall_info(raw, size)
            with self.assertRaisesRegex(p.PtraceBoundaryError, 'CAPABILITY_UNAVAILABLE'):
                info.require_native(info.operation)

    def test_clone3_changes_only_orig_rax_then_fixed_enosys(self):
        kernel = KernelFixture()
        transport = p.PtraceAccess(kernel)
        original = kernel.registers.copy()
        token = transport.suppress_clone3(101)
        expected = original.copy()
        expected[15] = (1 << 64) - 1
        self.assertEqual(expected, kernel.registers)
        kernel.suppressed_exit()
        before_exit = kernel.registers.copy()
        transport.finish_clone3(token)
        self.assertEqual(before_exit, kernel.registers)
        self.assertEqual(2, len(kernel.writes))
        self.assertEqual({0x420e, 0x4204, 0x4205}, {x[0] for x in kernel.requests})
        with self.assertRaisesRegex(p.PtraceBoundaryError, 'unknown clone3'):
            transport.finish_clone3(token)

    def test_duplicate_entry_and_foreign_token_cannot_write(self):
        kernel = KernelFixture()
        transport = p.PtraceAccess(kernel)
        token = transport.suppress_clone3(101)
        with self.assertRaisesRegex(p.PtraceBoundaryError, 'already pending'):
            transport.suppress_clone3(101)
        forged = p._Clone3Stop(token.tid, token.ip, token.sp)
        with self.assertRaisesRegex(p.PtraceBoundaryError, 'unknown clone3'):
            transport.finish_clone3(forged)
        self.assertEqual(1, len(kernel.writes))

    def test_non_clone3_or_wrong_stop_cannot_modify_registers(self):
        for wire in (entry(41), entry(56), entry(0x400001b3),
                     entry(435, 0x40000003), exit_info()):
            kernel = KernelFixture()
            kernel.info, kernel.available = wire
            with self.subTest(wire=wire), self.assertRaises(p.PtraceBoundaryError):
                p.PtraceAccess(kernel).suppress_clone3(101)
            self.assertFalse(kernel.writes)

    def test_entry_register_mismatch_or_partial_regset_never_writes(self):
        for index, value in ((15, 56), (17, 0x23), (16, 0), (19, 0), (None, 215)):
            kernel = KernelFixture()
            if index is None:
                kernel.read_size = value
            else:
                kernel.registers[index] = value
            with self.subTest(index=index), self.assertRaises(p.PtraceBoundaryError):
                p.PtraceAccess(kernel).suppress_clone3(101)
            self.assertFalse(kernel.writes)

    def test_unexpected_exit_does_not_forge_success_or_network_errno(self):
        for wire in (exit_info(-1), exit_info(0, 0), entry(), exit_info(arch=0x40000003)):
            kernel = KernelFixture()
            transport = p.PtraceAccess(kernel)
            token = transport.suppress_clone3(101)
            kernel.suppressed_exit()
            kernel.info, kernel.available = wire
            with self.subTest(wire=wire), self.assertRaises(p.PtraceBoundaryError):
                transport.finish_clone3(token)
            self.assertEqual(1, len(kernel.writes))

    def test_changed_exit_registers_or_instruction_are_not_repaired(self):
        for index in (10, 15, 16, 17, 19):
            kernel = KernelFixture()
            transport = p.PtraceAccess(kernel)
            token = transport.suppress_clone3(101)
            kernel.suppressed_exit()
            kernel.registers[index] = 0
            with self.subTest(index=index), self.assertRaises(p.PtraceBoundaryError):
                transport.finish_clone3(token)
            self.assertEqual(1, len(kernel.writes))

    def test_ptrace_denial_is_not_a_valid_reply_and_stale_errno_is_cleared(self):
        for request in (0x420e, 0x4204, 0x4205):
            kernel = KernelFixture()
            kernel.failure = request
            with self.subTest(request=request), self.assertRaisesRegex(
                    p.PtraceBoundaryError, 'ptrace errno 1'):
                p.PtraceAccess(kernel).suppress_clone3(101)
            self.assertFalse(kernel.writes)
        ctypes.set_errno(errno.EPERM)
        info = p.PtraceAccess(KernelFixture()).syscall_info(101)
        self.assertEqual(435, info.number)
        self.assertEqual(0, ctypes.get_errno())

    def test_native_loader_rejects_unsupported_host_without_loading_libc(self):
        with patch.object(p.sys, 'platform', 'win32'), patch.object(p.ctypes, 'CDLL') as load:
            with self.assertRaisesRegex(p.PtraceBoundaryError, 'Linux native x86-64'):
                p.PtraceAccess.for_host()
            load.assert_not_called()


if __name__ == '__main__':
    unittest.main()
