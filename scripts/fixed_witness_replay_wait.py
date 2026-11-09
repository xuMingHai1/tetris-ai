"""Strict Linux waitpid status decoding for the future fixed-witness tracer.

Pure wire decoding, with no wait/resume, task authentication or ledger mutation.
Only events enabled by the accepted TRACEME option set are recognized.
"""
from dataclasses import dataclass


class WaitStatusError(ValueError):
    """Unknown status: stop supervision rather than infer a resumable stop."""


@dataclass(frozen=True)
class WaitStatus:
    """Decoded bits, not permission to resume or proof of a task's identity.

    signal-stop includes group stops and plain SIGTRAP; GETSIGINFO and the
    dispatcher's handshake state must distinguish them before any reinjection.
    syscall-stop still needs GET_SYSCALL_INFO to identify entry versus exit.
    Terminal statuses describe an actual reap only when supplied by waitpid.
    """

    kind: str
    signal: int | None = None
    exit_code: int | None = None
    event: str | None = None
    core_dumped: bool = False


_EVENTS = {1: 'FORK', 2: 'VFORK', 3: 'CLONE', 4: 'EXEC'}
_SIGTRAP = 5
_SYSCALL_TRAP = _SIGTRAP | 0x80


def decode_wait_status(status):
    """Decode a canonical Linux status; reject unused bits and option drift.

    The caller must count every wait-stop against the shared event budget,
    including a status rejected here, before attempting further work. No
    per-task entry/exit parity, signal-delivery inference or event-message
    authentication is possible from these bits alone.
    """
    if type(status) is not int or not 0 <= status <= 0xffffffff:
        raise WaitStatusError('wait status must be an unsigned 32-bit integer')
    low = status & 0xff
    if low == 0x7f:
        stop_signal = (status >> 8) & 0xff
        event = status >> 16
        if event:
            if stop_signal != _SIGTRAP or event not in _EVENTS:
                raise WaitStatusError('unknown or inconsistent ptrace event')
            return WaitStatus('ptrace-event', signal=_SIGTRAP, event=_EVENTS[event])
        if stop_signal == _SYSCALL_TRAP:
            return WaitStatus('syscall-stop', signal=stop_signal)
        if not 1 <= stop_signal <= 64:
            raise WaitStatusError('invalid stop signal')
        return WaitStatus('signal-stop', signal=stop_signal)
    if low == 0:
        if status >> 16:
            raise WaitStatusError('unexpected bits in exit status')
        return WaitStatus('exited', exit_code=status >> 8)
    # WCONTINUED is not requested by the dedicated TRACEME tracer. In
    # particular, 0xffff must not be interpreted as termination by signal 127.
    terminal_signal = low & 0x7f
    if status >> 8 or not 1 <= terminal_signal <= 64:
        raise WaitStatusError('unknown or inconsistent terminal status')
    return WaitStatus('signaled', signal=terminal_signal, core_dumped=bool(low & 0x80))
