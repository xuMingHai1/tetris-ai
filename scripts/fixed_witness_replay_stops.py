"""Read stopped-task facts after shared accounting; never wait or resume.

The single-owner caller supplies each actual waitpid result exactly once and
binds its TID to a birth ticket. This layer does not authenticate that binding,
apply ABI policy, recognize handshakes or authorize signal reinjection.
"""
from dataclasses import dataclass

from fixed_witness_replay_ptrace import PtraceBoundaryError
from fixed_witness_replay_tasks import TaskAccountingError
from fixed_witness_replay_wait import WaitStatusError, decode_wait_status


class StopReadError(ValueError):
    """Whole-tree cleanup required; subsequent reads are forbidden."""


@dataclass(frozen=True)
class StopFacts:
    tid: int
    status: object
    syscall: object = None
    signal_info: object = None
    event_tid: int | None = None


class StopReader:
    """Charge raw stops before decoding or any ptrace request, failing closed.

    Terminal results perform no ptrace request and do not release ledger credit:
    the caller must authenticate the reap and settle the creation dispatcher.
    Even malformed/unknown stop words are charged. No receipt permits resume.
    A stopped reader accepts no further input; cleanup uses the caller's known
    task registry and reap path, not this reader.
    """

    def __init__(self, ledger):
        self.ledger = ledger
        self.stopped = None

    def _fail(self, reason):
        if self.stopped is None:
            self.stopped = reason
        raise StopReadError(reason)

    def read(self, tid, raw_status, access):
        if self.stopped is not None:
            self._fail('stop reader stopped')
        try:
            # Test the raw stop marker before validating the rest of the word.
            if type(raw_status) is int and raw_status & 0xff == 0x7f:
                self.ledger.wait_stop()
            if self.ledger.totals.stopped is not None:
                self._fail('task ledger stopped')
            if type(tid) is not int or not 0 < tid < 2**31:
                self._fail('invalid waited TID')
            status = decode_wait_status(raw_status)
            if status.kind == 'syscall-stop':
                info = access.syscall_info(tid)
                if info.operation not in (1, 2):
                    self._fail('syscall stop lacks entry/exit information')
                return StopFacts(tid, status, syscall=info)
            if status.kind == 'ptrace-event':
                return StopFacts(tid, status, event_tid=access.event_message(tid))
            if status.kind == 'signal-stop':
                return StopFacts(tid, status,
                                 signal_info=access.signal_info(tid, status.signal))
            return StopFacts(tid, status)
        except (TaskAccountingError, WaitStatusError, PtraceBoundaryError, OSError) as error:
            self._fail(str(error))
