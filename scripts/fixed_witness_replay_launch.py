"""One reserved coordinator fork; no CLI, wait loop or capability probe.

The dedicated tracer's caller owns the original deadline, charged stdio sources
and whole-tree kill/reap. A positive fork result is retained before any fallible
parent initialization; even a failed launch can therefore require cleanup.
"""
import math
import os
import time

from fixed_witness_replay_dispatch import CreationSyscallDispatcher
from fixed_witness_replay_handshake import CoordinatorHandshake
from fixed_witness_replay_lifecycle import CreationLifecycle
from fixed_witness_replay_ptrace import PtraceAccess
from fixed_witness_replay_stops import StopReader
from fixed_witness_replay_stub import CoordinatorStub


class CoordinatorLaunchError(ValueError):
    """No retry; inspect cleanup_tid and kill/reap within the original deadline."""


class CoordinatorLaunch:
    """Connect the trusted child stub to parent accounting and first handshake.

    stdio contains three already-open, caller-owned descriptors >= 3: stdin and
    the two evidence-quota-controlled output sinks. They remain open in the
    parent. The child maps them to 0/1/2 then closes ALL higher descriptors before
    TRACEME. No descriptor swapping, shell, implicit inherited output or worker
    thread is used. This class does not establish output quota/source identity.
    Only the dedicated native tracer leader may launch/admit the initial stop;
    its single-thread /proc inventory is checked before fork and handshake.
    """

    def __init__(self, ledger, stub, access, stdio, deadline, *, system=os,
                 clock=time.monotonic, access_factory=PtraceAccess.for_host):
        if (type(stdio) is not tuple or len(stdio) != 3
                or any(type(fd) is not int or not 3 <= fd < 2**31 - 1 for fd in stdio)
                or type(deadline) not in (int, float) or not math.isfinite(deadline)):
            raise ValueError('fixed stdio descriptors and original deadline required')
        self.ledger, self.stub, self.access = ledger, stub, access
        self.stdio, self.deadline = stdio, deadline
        self.system, self.clock = system, clock
        self.access_factory = access_factory
        self._wire_claimed = False
        self.owner = stub.parent_pid
        self.reader = StopReader(ledger)
        self.ticket = self.cleanup_tid = self.dispatcher = self.handshake = None
        self.attempted = self.admitted = False
        self.stopped = None
        # Actual terminal waits may consume a PID before later authentication
        # fails. Retain that ambiguity independently of live-credit accounting.
        self.unsettled_terminal_waits = set()

    def retain_terminal_wait(self, tid, raw_status):
        """Record possible consumed terminals before any fallible validation.

        Only authenticated reap may clear a marker. Non-stop malformed words
        also retain ambiguity; this method grants no accounting/reap authority.
        """
        if (type(tid) is int and 0 < tid < 2**31 and type(raw_status) is int
                and raw_status & 0xff != 0x7f):
            self.unsettled_terminal_waits.add(tid)

    @classmethod
    def for_host(cls, ledger, command, cwd, environment, stdio, deadline):
        """Load both native wires and encode inputs BEFORE any task reservation."""
        stub = CoordinatorStub.for_host(command, cwd, environment, os.getpid())
        return cls(ledger, stub, PtraceAccess.for_host(), stdio, deadline)

    def _owner_and_deadline(self):
        if (self.system.getpid() != self.owner
                or self.system.listdir('/proc/self/task') != [str(self.owner)]):
            raise CoordinatorLaunchError('dedicated single-thread tracer required')
        self.ledger.require_tracer(self.owner)
        now = self.clock()
        if not math.isfinite(now) or now >= self.deadline:
            raise CoordinatorLaunchError('original deadline exhausted')

    def launch(self):
        """Fork once after debit; child never returns, parent returns actual PID.

        No wait is performed: the future bounded loop must supply its actual
        initial wait result to accept_initial_wait. Fork failure settles only
        live credit, never the attempt. A returned PID remains a cleanup duty
        even if dispatcher construction fails; no exception means it was reaped.
        """
        if self.attempted:
            raise CoordinatorLaunchError('coordinator launch already attempted')
        self.attempted = True
        try:
            self._owner_and_deadline()
            for fd in self.stdio:
                self.system.fstat(fd)
            self._owner_and_deadline()
            self.ticket = self.ledger.reserve(0, 'coordinator')
            try:
                pid = self.system.fork()
            except OSError:
                self.ledger.finish(self.ticket, None)
                raise
        except BaseException:
            self.stopped = 'coordinator launch failed before child binding'
            raise
        if pid == 0:
            # No logging, callback, exit hooks or inherited descriptors survive
            # into the trusted child stub. Sources >=3 cannot clobber each other.
            try:
                for target, source in enumerate(self.stdio):
                    self.system.dup2(source, target, inheritable=True)
                self.system.closerange(3, 2**31 - 1)
                self.stub.run()
            except BaseException:
                pass
            self.system._exit(125)
            return None  # Unreachable with the native _exit implementation.
        # Register the real returned identity before ALL fallible parent work.
        self.cleanup_tid = pid
        try:
            if type(pid) is not int or not 0 < pid < 2**31 or pid == self.owner:
                raise CoordinatorLaunchError('invalid coordinator fork result')
            self.ledger.created(self.ticket, pid)
            self.ledger.finish(self.ticket, pid)
            self.dispatcher = CreationSyscallDispatcher(
                CreationLifecycle(self.ledger), self._birth_wire, self.ticket, pid)
            self.handshake = CoordinatorHandshake(
                self.reader, self.dispatcher, self.access, self.ticket, pid,
                self.system.getuid(), check=self._owner_and_deadline)
            return pid
        except BaseException:
            self.stopped = 'coordinator parent binding failed; cleanup required'
            raise

    def _birth_wire(self):
        if not self._wire_claimed:
            self._wire_claimed = True
            return self.access
        return self.access_factory()

    def accept_initial_wait(self, tid, raw_status):
        """Admit one actual wait, using the original deadline and shared budget.

        No internal wait or retry is introduced. Rejecting an expired/foreign
        owner still charges an observed stop, without accessing any stopped task.
        A consumed terminal may already have released its kernel PID even when
        admission fails. Quarantine it before any gate; this neither authenticates
        a reap nor releases live credit. Cleanup must not wait that number again.
        Subsequent waits belong to the authenticated whole-tree loop.
        """
        self.retain_terminal_wait(tid, raw_status)
        if self.stopped is not None or self.handshake is None or self.admitted:
            raise CoordinatorLaunchError('coordinator initial admission unavailable')
        self.admitted = True
        try:
            try:
                self._owner_and_deadline()
            except BaseException:
                self.reader.read(tid, raw_status, _RejectAccess())
                raise
            self.handshake.accept(tid, raw_status)
        except BaseException:
            self.stopped = 'coordinator initial admission failed; cleanup required'
            raise


class _RejectAccess:
    def __getattr__(self, name):
        raise OSError('rejected owner/deadline: no task access')
