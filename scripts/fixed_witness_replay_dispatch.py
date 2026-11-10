"""Creation dispatch composition; no wait, resume or capability probe.

The caller owns wait-stop charging, signal/stop authentication, trusted
coordinator admission and raw EXEC stop provenance. Each birth owns a separate wire object
so a reaped clone3 intervention cannot leak into a reused numeric TID.
"""
from dataclasses import dataclass

from fixed_witness_replay_creation import SYS_CLONE, SYS_FORK, SYS_VFORK, SYS_CLONE3
from fixed_witness_replay_ptrace import PtraceBoundaryError, SyscallInfo
from fixed_witness_replay_lifecycle import CreationLifecycleError
from fixed_witness_replay_tasks import TaskAccountingError


class CreationDispatchError(ValueError):
    """Latch failure and clean up the whole session; do not resume."""


@dataclass(frozen=True)
class ExecEventFacts:
    """Matched entry/event facts only, never identity migration or resume proof."""

    birth: int
    old_tid: int
    new_tid: int
    entry: SyscallInfo


class CreationSyscallDispatcher:
    """Join authenticated syscall-info, lifecycle obligations and wire tokens.

    Inputs must already be bound to real stopped/reaped birth identities by the
    caller. Successful return completes only this component's checks, never
    grants resume permission. access_factory supplies a NEW PtraceAccess for
    each birth. After any error only authenticated reap remains usable.
    """

    def __init__(self, lifecycle, access_factory, coordinator_ticket, coordinator_tid):
        self.lifecycle = lifecycle
        self._factory = access_factory
        self._tasks = {}
        self._pending = {}
        self._sequence = {}
        self._exec_events = {}
        self._exec_exits = set()
        self._coordinator = coordinator_ticket
        self._handshake_available = True
        self.stopped = None
        self._bind(coordinator_ticket, coordinator_tid)

    def _fail(self, reason):
        if self.stopped is None:
            self.stopped = reason
        raise CreationDispatchError(reason)

    def _running(self):
        if self.stopped is not None:
            self._fail('creation dispatcher stopped')

    def _bind(self, ticket, tid, newborn=False):
        if (type(ticket) is not int or ticket <= 0 or ticket in self._tasks
                or type(tid) is not int or not 0 < tid < 2**31
                or any(item[0] == tid for item in self._tasks.values())):
            self._fail('invalid or duplicate birth binding')
        # The child already exists in the ledger. Preserve its authenticated
        # identity BEFORE fallible wire initialization so cleanup can reap it.
        self._tasks[ticket] = (tid, None)
        # None expects an entry; newborns require initial stop admission first.
        # Thereafter retain actual entry facts, never infer op by alternation.
        self._sequence[ticket] = 'newborn' if newborn else None
        try:
            access = self._factory()
        except (PtraceBoundaryError, OSError) as error:
            self._fail(str(error))
        if access is None or any(item[1] is access for item in self._tasks.values()):
            self._fail('wire access must be exclusive to one birth')
        self._tasks[ticket] = (tid, access)

    def _task(self, ticket, tid):
        if (type(ticket) is not int or type(tid) is not int
                or ticket not in self._tasks or self._tasks[ticket][0] != tid):
            self._fail('unknown or mismatched birth binding')
        return self._tasks[ticket][1]

    def coordinator_signal_stop(self, ticket, tid):
        """Admit the trusted initial SIGSTOP, then expect the first entry.

        The trusted self-kill stop is processed after syscall exit work. Setting
        SYSCALL tracing at this signal stop cannot replay that completed exit;
        the next syscall stop must be an entry. This method
        does not authenticate the signal, install options or permit resume.
        Never call it for an arbitrary delivery stop or after syscall handling.
        """
        self._running()
        self._task(ticket, tid)
        if ticket != self._coordinator or not self._handshake_available:
            self._fail('coordinator initial handshake unavailable')
        self._handshake_available = False
        self._sequence[ticket] = None

    def stop_binding(self, tid):
        """Resolve a live numeric wait identity to its birth-exclusive wire.

        The outer owner must charge the wait before calling this method and
        must not resume between lookup, fact collection and policy dispatch.
        At an EXEC stop this resolves the TGID's prior binding, not the executor;
        event matching and installation must replace it before any resume.
        """
        self._running()
        if type(tid) is int:
            for ticket, (bound_tid, access) in self._tasks.items():
                if tid == bound_tid and access is not None:
                    return ticket, access
        self._fail('unknown waited birth binding')

    def waiting_births(self):
        """Snapshot live (birth, TID) pairs for the sole targeted wait owner.

        Never include an unbound reservation or the tracer itself. No wait or
        ptrace permission is granted; the owner must retain the same bindings
        until it consumes a result; EXEC installation supplies the next snapshot.
        """
        self._running()
        if any(access is None for tid, access in self._tasks.values()):
            self._fail('incomplete birth wire binding')
        return tuple((ticket, tid) for ticket, (tid, access) in self._tasks.items())

    def newborn_signal_stop(self, ticket, tid):
        """After outer-owner initial SIGSTOP authentication, expect first entry.

        Linux clears newborn SYSCALL_TRACE before ret_from_fork. The initial
        signal stop is processed after syscall exit work, so resuming it does
        not yield a creation exit. This one-shot transition neither authenticates
        the signal nor installs options or authorizes resume.
        """
        self._running()
        self._task(ticket, tid)
        if ticket == self._coordinator or self._sequence[ticket] != 'newborn':
            self._fail('newborn initial stop admission unavailable')
        self._sequence[ticket] = None

    def syscall_stop(self, ticket, tid):
        """Read the actual kernel operation; reserve before clone3 intervention.

        Every operation comes from GET_SYSCALL_INFO. State validates that
        operation; it never guesses it. A newborn requires initial signal-stop
        admission before its first entry. Exec identity changes and
        authenticated stop/resume handling remain the outer caller's obligation.
        """
        self._running()
        access = self._task(ticket, tid)
        try:
            info = access.syscall_info(tid)
        except PtraceBoundaryError as error:
            self._fail(str(error))
        return self.syscall_info_stop(ticket, tid, info)

    def syscall_info_stop(self, ticket, tid, info):
        """Consume already-read immutable facts, without a second kernel read.

        The trusted outer owner must have charged the wait exactly once and
        authenticated the birth/TID/current stop before passing StopFacts.syscall
        from this birth's wire. This method does not certify that provenance or
        authorize resume. Standalone syscall_stop uses this same policy path.
        """
        self._running()
        access = self._task(ticket, tid)
        if ticket == self._coordinator:
            self._handshake_available = False
        try:
            if type(info) is not SyscallInfo:
                self._fail('decoded syscall-info required')
            if info.operation not in (1, 2):
                self._fail('unsupported syscall stop operation')
            info.require_native(info.operation)
            previous = self._sequence[ticket]
            if ticket in self._exec_events:
                self._fail('exec identity transition not installed')
            if ticket in self._exec_exits:
                if info.operation != 2 or info.is_error or info.result != 0:
                    self._fail('successful exec requires native zero exit')
                self._exec_exits.remove(ticket)
            if previous == 'newborn':
                self._fail('newborn initial stop admission required')
            if info.operation == 1:
                if previous is not None:
                    self._fail('new entry before prior syscall result')
                # A managed task may not change ptrace relationships. This is
                # rejection, not rewriting its errno or any network result.
                if info.number == 101:  # Linux native x86-64 __NR_ptrace
                    self._fail('managed ptrace syscall forbidden')
                self._sequence[ticket] = info
                if info.number not in (SYS_CLONE, SYS_FORK, SYS_VFORK, SYS_CLONE3):
                    return None
                obligation = self.lifecycle.entry(ticket, info.arch, info.number, info.arguments)
                self._pending[ticket] = None
                if obligation.suppress_enosys:
                    self._pending[ticket] = access.suppress_clone3_from_info(tid, info)
                return obligation
            if previous is None:
                self._fail('syscall exit without entry')
            if ticket in self._pending:
                token = self._pending[ticket]
                if token is not None:
                    access.finish_clone3_from_info(token, info)
                self.lifecycle.result(ticket, info.result, info.is_error)
                del self._pending[ticket]
            self._sequence[ticket] = None
            return None
        except (PtraceBoundaryError, CreationLifecycleError) as error:
            self._fail(str(error))

    def exec_event(self, old_tid, new_tid):
        """Match an externally authenticated EXEC event to its native entry.

        The sole owner must charge the wait and authenticate PTRACE_EVENT_EXEC
        and GETEVENTMSG(old_tid) at new_tid before calling. Same PID namespace
        as the tracer is required. A message alone is not authentication.
        No kernel reads, ledger mutation, wire rekey or resume happen here.
        After success this birth cannot consume another syscall until
        install_exec_identity completes. Between these methods the old binding
        is not a native cleanup identity. PostHandshakeDispatcher calls both
        without an intervening wait/resume under the original sole ownership.
        """
        self._running()
        if (type(old_tid) is not int or not 0 < old_tid < 2**31
                or type(new_tid) is not int or not 0 < new_tid < 2**31):
            self._fail('invalid exec event TIDs')
        ticket, _ = self.stop_binding(old_tid)
        previous = self._sequence[ticket]
        if (ticket in self._exec_events or ticket in self._exec_exits
                or type(previous) is not SyscallInfo
                or previous.operation != 1 or previous.number not in (59, 322)
                or ticket in self._pending):
            self._fail('exec event requires unmatched native exec entry')
        try:
            previous.require_native(1)
        except PtraceBoundaryError as error:
            self._fail(str(error))
        ledger = self.lifecycle.ledger
        if ledger.totals.stopped is not None:
            self._fail('task ledger stopped')
        identity = ledger.identity(ticket)
        if not identity.alive or identity.tid != old_tid or identity.tgid != new_tid:
            self._fail('exec event does not match live group identity')
        target, _ = self.stop_binding(new_tid)
        leader = ledger.identity(target)
        if not leader.alive or leader.group_birth != identity.group_birth:
            self._fail('exec event target is outside the birth group')
        facts = ExecEventFacts(ticket, old_tid, new_tid, previous)
        self._exec_events[ticket] = facts
        return facts

    def child_event(self, ticket, tid, event, child_tid):
        """Consume decoded event plus a caller-authenticated GETEVENTMSG TID."""
        self._running()
        self._task(ticket, tid)
        if ticket not in self._pending or event not in ('FORK', 'VFORK', 'CLONE'):
            self._fail('unexpected creation event')
        try:
            child = self.lifecycle.child_event(ticket, event.lower(), child_tid)
            self._bind(child, child_tid, newborn=True)
            return child
        except CreationLifecycleError as error:
            self._fail(str(error))

    def install_exec_identity(self, facts):
        """Install matched EXEC atomically under the sole stop owner, no resume.

        Keep the executor's exclusive wire and syscall entry, discard only the
        kernel-retired leader's wire (including its unfinished clone3 token).
        Ledger validation precedes binding mutation. On failure all old cleanup
        bindings remain, and the owner must stop the entire session.
        """
        self._running()
        if (type(facts) is not ExecEventFacts
                or self._exec_events.get(facts.birth) is not facts):
            self._fail('unmatched exec transition facts')
        access = self._task(facts.birth, facts.old_tid)
        leader, _ = self.stop_binding(facts.new_tid)
        try:
            self.lifecycle.ledger.exec_identity(facts.birth, facts.old_tid, facts.new_tid)
        except TaskAccountingError as error:
            self._fail(str(error))
        if leader != facts.birth:
            del self._tasks[leader]
            self._sequence.pop(leader, None)
            self._pending.pop(leader, None)
            self._exec_events.pop(leader, None)
            self._exec_exits.discard(leader)
        self._tasks[facts.birth] = (facts.new_tid, access)
        del self._exec_events[facts.birth]
        self._exec_exits.add(facts.birth)
        return facts.birth, access

    def awaiting_exec_migration(self, ticket, tid):
        """Permit bounded polling of the known TGID after old-TID ECHILD only.

        This is NOT an exec/reap receipt. Preserve all identities and credits;
        only a later actual EXEC event may install migration. The original
        deadline remains the bound if no such event arrives.
        """
        self._running()
        self._task(ticket, tid)
        entry = self._sequence[ticket]
        identity = self.lifecycle.ledger.identity(ticket)
        if (type(entry) is not SyscallInfo or entry.number not in (59, 322)
                or ticket in self._exec_events or not identity.alive
                or identity.tid != tid or identity.tgid == tid):
            return False
        for birth, (bound_tid, _) in self._tasks.items():
            leader = self.lifecycle.ledger.identity(birth)
            if (bound_tid == identity.tgid and leader.alive
                    and leader.group_birth == identity.group_birth):
                return True
        return False

    def reaped(self, ticket, tid):
        """Only after terminal wait; discard the birth's wire and pending token.

        Usable during cleanup. No unfinished token is completed against a later
        reused numeric TID. The caller must never resume after failure.
        """
        self._task(ticket, tid)
        try:
            self.lifecycle.reaped(ticket)
        except CreationLifecycleError as error:
            self._fail(str(error))
        self._pending.pop(ticket, None)
        self._sequence.pop(ticket, None)
        self._exec_events.pop(ticket, None)
        self._exec_exits.discard(ticket)
        del self._tasks[ticket]

    def require_drained(self):
        """Bookkeeping only; never a capability or COMPLETE certificate."""
        if (self._tasks or self._pending or self._sequence
                or self._exec_events or self._exec_exits):
            self._fail('unreaped dispatch bindings')
        self._running()
        try:
            self.lifecycle.require_drained()
        except CreationLifecycleError as error:
            self._fail(str(error))
