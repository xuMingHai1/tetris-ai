"""Inherited host pipe -> exclusive tracer writers, never an execution permit.

The trusted Java host permanently retains only its 32KiB writer and sends this
fixed grant once to its direct child. No ByteQuota crosses a process boundary.
Source authentication, the original hard host deadline and the complete native
guard remain the caller's prerequisites; a grant does not establish them.
"""
import os
from pathlib import Path
import stat
import time

from fixed_witness_replay_quota import ByteQuota, OUTPUT_BYTES, write_exclusive
from fixed_witness_replay_writers import WriterReservations, REPORT_BYTES, COORDINATOR_BYTES


MAX_GRANT = 128


class HostWriterHandoff:
    """One inherited pipe, one receiver PID, one permanent on-disk claim.

    Reading is nonblocking on the dedicated tracer thread. EOF is mandatory;
    timeout/overflow/malformed input is never retried. The receiver constructs
    only the tracer/coordinator allowances: the host partition has no local
    writer. The claim itself is precharged to the tracer report allowance.
    """

    def __init__(self):
        raise ValueError('use the original inherited host pipe')

    @classmethod
    def receive(cls, fd, directory, deadline, *, clock=time.monotonic):
        if (type(fd) is not int or fd < 0 or type(deadline) not in (int, float)
                or not callable(clock) or not clock() < deadline <= clock() + 900):
            raise ValueError('original bounded host handoff required')
        path = Path(directory)
        if (not path.is_absolute() or path != path.resolve(strict=True)
                or not path.is_dir() or not stat.S_ISFIFO(os.fstat(fd).st_mode)):
            raise ValueError('canonical session directory and inherited pipe required')
        os.set_blocking(fd, False)
        raw = bytearray()
        while True:
            remaining = deadline - clock()
            if remaining <= 0:
                raise ValueError('original host handoff deadline exhausted')
            try:
                chunk = os.read(fd, MAX_GRANT + 1 - len(raw))
            except (BlockingIOError, InterruptedError):
                time.sleep(min(.01, remaining))
                continue
            if not chunk:
                break
            raw.extend(chunk)
            if len(raw) > MAX_GRANT:
                raise ValueError('oversized host writer grant')
        expected = ('FIXED_WRITERS_V1 %d %d %d %d %d %d\n' % (
            os.getppid(), os.getpid(), OUTPUT_BYTES, REPORT_BYTES, REPORT_BYTES,
            COORDINATOR_BYTES)).encode('ascii')
        if raw != expected:
            raise ValueError('original parent/receiver and frozen writer grant required')
        # No host ByteQuota is reconstructed. Cross-process exclusivity relies
        # on the original trusted host and direct inherited pipe, not this text.
        writers = WriterReservations(ByteQuota(REPORT_BYTES), None, ByteQuota(COORDINATOR_BYTES))
        write_exclusive(path / 'tracer-writer-claim.txt', expected, writers.tracer)
        result = object.__new__(cls)
        result._writers = writers
        result.directory, result.deadline = path, deadline
        result.pid, result.parent = os.getpid(), os.getppid()
        result.clock = clock
        result.claimed = False
        return result

    def prepare(self, ledger, command, cwd, environment, stdin, *, prepared_probes):
        """Consume the grant directly into the existing enforced lifecycle.

        Failure during source/native/output preparation consumes the grant too.
        No API returns the underlying writer reservations to another caller.
        """
        from fixed_witness_replay_supervisor import TracerSupervisor
        if self.claimed:
            raise ValueError('host writer handoff already consumed')
        self.claimed = True
        if os.getpid() != self.pid or os.getppid() != self.parent:
            raise ValueError('original direct tracer required')
        if self.clock() >= self.deadline:
            raise ValueError('original host handoff deadline exhausted')
        ledger.require_tracer(self.pid)
        if prepared_probes is None:
            raise ValueError('original prepared probe sources required')
        return TracerSupervisor.for_host(
            ledger, command, cwd, environment, stdin, self.directory,
            self.deadline, self._writers, prepared_probes=prepared_probes)
