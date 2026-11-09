"""Linux x86-64 ptrace wire boundary, not a running task supervisor.

No attach, fork, wait loop or capability probe is exposed here. The
future single-owner tracer must debit TaskLedger BEFORE suppress_clone3, bind
every stop to a birth ticket, and kill/reap on any error. Synthetic tests inject
ptrace replies; importing this module never loads libc or touches a task.
"""
import ctypes
from dataclasses import dataclass
import platform
import struct
import sys


AUDIT_ARCH_X86_64 = 0xc000003e
X32_BIT = 0x40000000
CLONE3 = 435
LINUX_ENOSYS = 38  # Wire ABI, independent of the synthetic test host's errno.
GET_SYSCALL_INFO = 0x420e
GETREGSET = 0x4204
SETREGSET = 0x4205
SETOPTIONS = 0x4200
GETEVENTMSG = 0x4201
GETSIGINFO = 0x4202
SYSCALL = 24
# TRACESYSGOOD, TRACEFORK/VFORK/CLONE/EXEC and EXITKILL; no optional weakening.
TRACE_OPTIONS = 0x0010001f
NT_PRSTATUS = 1
U64_MASK = (1 << 64) - 1
INFO_SIZE = 88  # linux ptrace_syscall_info, including union alignment
REGISTER_NAMES = ('r15', 'r14', 'r13', 'r12', 'rbp', 'rbx', 'r11', 'r10',
                  'r9', 'r8', 'rax', 'rcx', 'rdx', 'rsi', 'rdi', 'orig_rax',
                  'rip', 'cs', 'eflags', 'rsp', 'ss', 'fs_base', 'gs_base',
                  'ds', 'es', 'fs', 'gs')
REG_SIZE = 8 * len(REGISTER_NAMES)


class PtraceBoundaryError(ValueError):
    """Unsupported/truncated kernel data or failed intervention: do not resume."""


def require(condition, message):
    if not condition:
        raise PtraceBoundaryError(message)


@dataclass(frozen=True)
class SyscallInfo:
    operation: int
    arch: int
    instruction_pointer: int
    stack_pointer: int
    number: int | None = None
    arguments: tuple = ()
    result: int | None = None
    is_error: bool | None = None

    def require_native(self, operation):
        """No compat/x32 exception is authorized by this low-level boundary."""
        require(self.operation == operation and self.arch == AUDIT_ARCH_X86_64,
                'CAPABILITY_UNAVAILABLE: native syscall stop required')
        if operation == 1:
            require(type(self.number) is int and 0 <= self.number < X32_BIT,
                    'CAPABILITY_UNAVAILABLE: x32/invalid syscall number')


def decode_syscall_info(raw, available):
    """Use kernel op/returned size, never entry/exit alternation or zero-fill.

    GET_SYSCALL_INFO returns the available size even if the supplied buffer was
    shorter. Reject unknown layouts rather than interpreting truncated fields.
    Compat ABI values are preserved for a future authenticated probe binding;
    preserving them here does not authorize their execution.
    """
    require(type(raw) is bytes and type(available) is int
            and 24 <= available <= len(raw) <= INFO_SIZE, 'truncated syscall-info header')
    operation, arch, ip, sp = struct.unpack_from('<B3xIQQ', raw)
    if operation == 1:
        require(available >= 80, 'truncated syscall-info entry')
        values = struct.unpack_from('<7Q', raw, 24)
        return SyscallInfo(operation, arch, ip, sp, values[0], values[1:])
    if operation == 2:
        require(available >= 33, 'truncated syscall-info exit')
        result, error = struct.unpack_from('<qB', raw, 24)
        require(error in (0, 1) and (not error or -4095 <= result < 0),
                'invalid syscall-info errno')
        return SyscallInfo(operation, arch, ip, sp, result=result, is_error=bool(error))
    raise PtraceBoundaryError('CAPABILITY_UNAVAILABLE: unsupported syscall-info operation')


class _Iovec(ctypes.Structure):
    _fields_ = [('base', ctypes.c_void_p), ('length', ctypes.c_size_t)]


@dataclass(frozen=True)
class SignalInfo:
    """Header plus sender for SI_USER/SI_TKILL; never a resume decision."""

    signal: int
    error: int
    code: int
    sender_pid: int | None = None
    sender_uid: int | None = None


@dataclass(frozen=True)
class _Clone3Stop:
    tid: int
    ip: int
    sp: int


class PtraceAccess:
    """Typed ptrace transport with an injectable call for wire-format tests.

    It owns clone3 intervention tokens, but not task ownership, budgets, event
    accounting, cleanup or stop authentication. No caller may infer those from
    a successful register read/write. Only for_host loads the native library.
    """

    def __init__(self, call):
        self._call = call
        self._clone3 = {}

    @classmethod
    def for_host(cls):
        require(sys.platform == 'linux' and platform.machine() == 'x86_64'
                and sys.byteorder == 'little' and ctypes.sizeof(ctypes.c_void_p) == 8,
                'CAPABILITY_UNAVAILABLE: Linux native x86-64 required')
        call = ctypes.CDLL(None, use_errno=True).ptrace
        call.restype = ctypes.c_long
        call.argtypes = [ctypes.c_uint, ctypes.c_int, ctypes.c_void_p, ctypes.c_void_p]
        return cls(call)

    def _request(self, request, tid, address, data):
        require(type(tid) is int and 0 < tid <= 0x7fffffff, 'invalid ptrace TID')
        ctypes.set_errno(0)
        result = self._call(request, tid, address, data)
        if result == -1:
            # No exception object, trace data or environment enters this message.
            raise PtraceBoundaryError('CAPABILITY_UNAVAILABLE: ptrace errno '
                                      + str(ctypes.get_errno()))
        require(type(result) is int and result >= 0, 'invalid ptrace response')
        return result

    def syscall_info(self, tid):
        buffer = ctypes.create_string_buffer(INFO_SIZE)
        available = self._request(GET_SYSCALL_INFO, tid, INFO_SIZE, ctypes.byref(buffer))
        return decode_syscall_info(buffer.raw, available)

    def set_options(self, tid):
        """Install the complete frozen option set on an authenticated stop.

        The dispatcher must do this before first resume, including newborn
        handshakes. Success is only a wire acknowledgement, not EXITKILL proof.
        """
        require(self._request(SETOPTIONS, tid, 0, TRACE_OPTIONS) == 0,
                'unexpected set-options response')

    def event_tid(self, tid):
        """Read GETEVENTMSG only after a decoded creation or EXEC event.

        The message is a child TID or former exec TID. It is not independently
        authenticated: caller must match the event and birth-ticket ledger.
        Use a fixed 64-bit cell for this native x86-64 wire even in host tests.
        """
        value = ctypes.c_uint64()
        require(self._request(GETEVENTMSG, tid, 0, ctypes.byref(value)) == 0,
                'unexpected event-message response')
        require(0 < value.value <= 0x7fffffff, 'invalid event TID')
        return value.value

    def signal_info(self, tid, expected_signal):
        """Read the real 128-byte Linux siginfo and match the wait signal.

        EINVAL (including a group stop) fails closed; callers must not infer a
        delivery stop from it. si_code stays signed for dispatcher policy.
        Only SI_USER/SI_TKILL expose the native sender union at offset 16.
        Other codes have different unions and must not be read as sender IDs.
        """
        require(type(expected_signal) is int and 1 <= expected_signal <= 64,
                'invalid expected signal')
        buffer = ctypes.create_string_buffer(128)
        require(self._request(GETSIGINFO, tid, 0, ctypes.byref(buffer)) == 0,
                'unexpected siginfo response')
        signal, error, code = struct.unpack_from('<iii', buffer.raw)
        require(signal == expected_signal, 'wait/siginfo signal mismatch')
        if code in (0, -6):  # SI_USER / SI_TKILL, Linux ABI constants.
            sender_pid, sender_uid = struct.unpack_from('<iI', buffer.raw, 16)
            return SignalInfo(signal, error, code, sender_pid, sender_uid)
        return SignalInfo(signal, error, code)

    def resume_syscall(self, tid, signal=0):
        """Transport only: caller MUST authenticate/arbitrate before calling.

        This deliberately has no CONT/DETACH or configurable option shortcut.
        It cannot decide whether signal reinjection is correct or a reservation
        exists. Any failure requires whole-tree cleanup, never a retry/resume.
        """
        require(type(signal) is int and 0 <= signal <= 64, 'invalid resume signal')
        require(self._request(SYSCALL, tid, 0, signal) == 0,
                'unexpected syscall-resume response')

    def _registers(self, tid):
        buffer = ctypes.create_string_buffer(REG_SIZE)
        vector = _Iovec(ctypes.cast(buffer, ctypes.c_void_p), REG_SIZE)
        result = self._request(GETREGSET, tid, NT_PRSTATUS, ctypes.byref(vector))
        require(result == 0 and vector.length == REG_SIZE,
                'CAPABILITY_UNAVAILABLE: incomplete x86-64 registers')
        return dict(zip(REGISTER_NAMES, struct.unpack('<27Q', buffer.raw)))

    def _write_registers(self, tid, registers):
        buffer = ctypes.create_string_buffer(struct.pack(
            '<27Q', *(registers[name] for name in REGISTER_NAMES)), REG_SIZE)
        vector = _Iovec(ctypes.cast(buffer, ctypes.c_void_p), REG_SIZE)
        result = self._request(SETREGSET, tid, NT_PRSTATUS, ctypes.byref(vector))
        require(result == 0 and vector.length == REG_SIZE, 'incomplete register write')

    def suppress_clone3(self, tid):
        """After ledger debit, replace native clone3 with invalid syscall -1.

        No user-memory clone_args are read: another thread could mutate them.
        Caller retains the stopped task and must not resume if any check fails.
        """
        require(tid not in self._clone3, 'clone3 intervention already pending')
        info = self.syscall_info(tid)
        info.require_native(1)
        require(info.number == CLONE3, 'clone3 entry required')
        registers = self._registers(tid)
        require(registers['orig_rax'] == CLONE3 and registers['cs'] == 0x33
                and registers['rip'] == info.instruction_pointer
                and registers['rsp'] == info.stack_pointer,
                'syscall-info/register mismatch')
        registers['orig_rax'] = U64_MASK
        self._write_registers(tid, registers)
        token = _Clone3Stop(tid, info.instruction_pointer, info.stack_pointer)
        self._clone3[tid] = token
        return token

    def finish_clone3(self, token):
        """Check the real suppressed exit and write only the fixed ENOSYS result.

        Tokens are single-use and tied to the exact stopped syscall. They do not
        replace birth-ticket binding by the future supervisor. No network result
        or arbitrary errno rewriting is available through this interface.
        """
        require(type(token) is _Clone3Stop and self._clone3.get(token.tid) is token,
                'unknown clone3 intervention')
        info = self.syscall_info(token.tid)
        info.require_native(2)
        require((info.instruction_pointer, info.stack_pointer) == (token.ip, token.sp)
                and info.is_error and info.result == -LINUX_ENOSYS,
                'suppressed clone3 did not return ENOSYS')
        registers = self._registers(token.tid)
        require(registers['orig_rax'] == U64_MASK and registers['cs'] == 0x33
                and registers['rip'] == token.ip and registers['rsp'] == token.sp
                and registers['rax'] == ((-LINUX_ENOSYS) & U64_MASK),
                'suppressed clone3 register mismatch')
        registers['rax'] = (-LINUX_ENOSYS) & U64_MASK
        self._write_registers(token.tid, registers)
        del self._clone3[token.tid]
