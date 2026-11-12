"""Coordinator pipes drained by the sole tracer, not a second reader/thread.

This is cooperative retained-output enforcement, not a cross-process quota
certificate. The original host must still transfer exclusive writer ownership
and reserve every coordinator artifact from the SAME coordinator allowance.
No fork, ptrace, signal, probe or historical input is executed here.
"""
import os
from pathlib import Path

from fixed_witness_replay_process import OutputBudget
from fixed_witness_replay_quota import ByteQuota
from fixed_witness_replay_writers import WriterReservations, COORDINATOR_BYTES


CHUNK_BYTES = 16 * 1024


class CoordinatorOutputError(ValueError):
    """Permanent output failure; normal dispatch must stop, cleanup may drain."""


class CoordinatorOutput:
    """One permanent coordinator writer partition and two bounded pipe sinks.

    The tracer retains all descriptors until launch finishes its fork attempt,
    then closes its write ends even if parent binding failed. The child only
    inherits the write ends through CoordinatorLaunch's fixed stdio mapping.
    Each poll reads at most one chunk per stream so an output flood cannot
    monopolize the original wait/deadline loop. Failed I/O/overflow is latched;
    cleanup discards further bytes without retrying writes or borrowing credit.
    """

    def __init__(self, writers, directory):
        if (type(writers) is not WriterReservations
                or type(writers.coordinator) is not ByteQuota
                or writers.coordinator.limit != COORDINATOR_BYTES):
            raise CoordinatorOutputError('original unclaimed coordinator partition required')
        # Claim before any fallible open: partial construction is never retried
        # with this partition. Existing artifact reservations remain charged.
        with writers.coordinator._lock:
            if getattr(writers.coordinator, '_stdio_claimed', False):
                raise CoordinatorOutputError('original unclaimed coordinator partition required')
            writers.coordinator._stdio_claimed = True
        self.quota = writers.coordinator
        self.budget = OutputBudget(limit=COORDINATOR_BYTES, quota=self.quota)
        self.readers = []
        self.writers = []
        self.sinks = []
        self.eof = set()
        self.failure = None
        self.close_errors = []
        self.claimed = False
        self.parent_released = False
        self.closed = False
        try:
            for name in ('stdout', 'stderr'):
                # No buffered bytes may survive a failed write/flush and be
                # retried implicitly when failure cleanup closes the sink.
                self.sinks.append((Path(directory) / ('coordinator-' + name + '.log')).open(
                    'xb', buffering=0))
                read, write = os.pipe()
                self.readers.append(read)
                self.writers.append(write)
                os.set_inheritable(read, False)
                os.set_inheritable(write, False)
                os.set_blocking(read, False)
        except BaseException as error:
            self.failure = error
            self.close()
            raise

    def claim(self, launch):
        """Bind the original launch's exact two write ends, once before fork."""
        if (self.closed or self.failure is not None or self.claimed
                or self.parent_released or tuple(self.writers) != launch.stdio[1:]
                or launch.stdio[0] in self.readers + self.writers
                or launch.attempted):
            raise CoordinatorOutputError('exclusive original launch output pipes required')
        self.claimed = True

    def release_parent_writers(self):
        """After the only fork attempt; closing uncertain FDs is never retried."""
        if self.parent_released:
            return
        self.parent_released = True
        for fd in self.writers:
            try:
                os.close(fd)
            except BaseException as error:
                self.close_errors.append(error)
                if self.failure is None:
                    self.failure = error

    def require_launchable(self):
        """Never fork with a failed, closed or already exhausted output owner."""
        if (self.closed or self.failure is not None or not self.claimed
                or self.parent_released or self.budget.remaining <= 0):
            raise CoordinatorOutputError('coordinator output unavailable before launch')

    @property
    def drained(self):
        return len(self.eof) == 2

    def poll(self, *, cleanup=False):
        """Read a bounded quantum; charge retained prefixes before any write.

        Cleanup preserves the first error and never writes after failure, but
        can keep pipes moving while original kill/reap work finishes. Reading
        EOF is not evidence of task termination; the existing ledger owns that.
        """
        if self.closed or not self.claimed or not self.parent_released:
            raise CoordinatorOutputError('claimed post-fork output required')
        if self.failure is not None and not cleanup:
            raise CoordinatorOutputError('coordinator output previously failed') from self.failure
        for index, (fd, sink) in enumerate(zip(self.readers, self.sinks)):
            if index in self.eof:
                continue
            try:
                try:
                    data = os.read(fd, CHUNK_BYTES)
                except (BlockingIOError, InterruptedError):
                    continue
                if not data:
                    self.eof.add(index)
                elif self.failure is None and self.budget.consume(data, sink):
                    raise CoordinatorOutputError('coordinator retained-output allowance exhausted')
            except BaseException as error:
                if self.failure is None:
                    self.failure = error
                if not cleanup:
                    raise CoordinatorOutputError('coordinator output failed') from self.failure

    def close(self):
        """Release each descriptor once; retain close failures without refund."""
        if self.closed:
            return
        self.closed = True
        self.release_parent_writers()
        for resource in self.readers + self.sinks:
            try:
                if type(resource) is int:
                    os.close(resource)
                else:
                    resource.close()
            except BaseException as error:
                self.close_errors.append(error)
                if self.failure is None:
                    self.failure = error
