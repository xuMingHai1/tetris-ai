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
    each birth. After error only authenticated cleanup binding/reap is usable;
    no failure latch is cleared and no normal work may resume.
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
        return self._stop_binding(tid)

    def _stop_binding(self, tid):
        """Identity lookup shared with authenticated failed-owner cleanup."""
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
        if self.lifecycle.ledger.totals.stopped is not None:
            self._fail('task ledger stopped')
        return self._match_exec_event(old_tid, new_tid)

    def _match_exec_event(self, old_tid, new_tid):
        """Shared matching only; cleanup must authenticate its retained wait first."""
        if (type(old_tid) is not int or not 0 < old_tid < 2**31
                or type(new_tid) is not int or not 0 < new_tid < 2**31):
            self._fail('invalid exec event TIDs')
        ticket, _ = self._stop_binding(old_tid)
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
        identity = ledger.identity(ticket)
        if not identity.alive or identity.tid != old_tid or identity.tgid != new_tid:
            self._fail('exec event does not match live group identity')
        target, _ = self._stop_binding(new_tid)
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

    def cleanup_creation_binding(self, pending):
        """Validate an existing unbound creation after failure, no new work.

        Only the exclusive cleanup wait owner may use the returned wire to
        read GETEVENTMSG at an actual matching creation stop. Neither this
        method nor cleanup_child_event permits resume or authenticates a wait.
        """
        ledger = self.lifecycle.ledger
        if pending not in ledger.cleanup_pending() or pending.child is not None:
            self._fail('unmatched unbound cleanup reservation')
        parent = ledger.identity(pending.parent)
        access = self._task(parent.birth, parent.tid)
        entry = self._sequence.get(parent.birth)
        number = {'fork': SYS_FORK, 'vfork': SYS_VFORK, 'clone': SYS_CLONE}.get(pending.kind)
        if (number is None or not parent.alive
                or parent.tid != parent.tgid or access is None
                or parent.birth not in self._pending or self._pending[parent.birth] is not None
                or parent.birth in self._exec_events or parent.birth in self._exec_exits
                or type(entry) is not SyscallInfo or entry.operation != 1
                or entry.number != number):
            self._fail('unsupported in-flight cleanup creation')
        try:
            entry.require_native(1)
            obligation = self.lifecycle._obligation(parent.birth)
            if (obligation.reservation != pending.reservation
                    or obligation.suppress_enosys
                    or (pending.thread and (pending.kind != 'clone'
                                            or obligation.expected_event != 'clone'))):
                self._fail('cleanup creation obligation mismatch')
        except (PtraceBoundaryError, CreationLifecycleError) as error:
            self._fail(str(error))
        return parent, access, obligation.expected_event.upper()

    def cleanup_child_event(self, pending, event, child_tid):
        """Bind only an already reserved, externally authenticated child event.

        Retain the child's ledger and dispatcher birth before fallible wire
        setup, exactly as normal dispatch does. All failure latches remain.
        """
        _, _, expected = self.cleanup_creation_binding(pending)
        if type(event) is not str or event != expected:
            self._fail('unexpected cleanup creation event')
        if type(child_tid) is not int or not 0 < child_tid < 2**31:
            self._fail('invalid cleanup child event TID')
        try:
            child = self.lifecycle.child_event(pending.parent, event.lower(), child_tid)
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
        return self._install_exec_identity(facts, cleanup=False)

    def _install_exec_identity(self, facts, *, cleanup):
        """Share atomic validation/mutation; never clear the original failure."""
        if (type(facts) is not ExecEventFacts
                or self._exec_events.get(facts.birth) is not facts):
            self._fail('unmatched exec transition facts')
        access = self._task(facts.birth, facts.old_tid)
        leader, _ = self._stop_binding(facts.new_tid)
        try:
            transition = (self.lifecycle.ledger.cleanup_exec_identity if cleanup
                          else self.lifecycle.ledger.exec_identity)
            transition(facts.birth, facts.old_tid, facts.new_tid)
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

    def cleanup_inflight_exec_binding(self, executor, entry):
        """Validate a resumed original nonleader ENTRY before TGID-only wait.

        The caller additionally authenticates its completed owned wait receipt.
        No unsettled creation, other live sibling, matched event or missing wire
        can be turned into a pin allowance. No ledger credit changes here.
        """
        ledger = self.lifecycle.ledger
        access = self._task(executor.birth, executor.tid)
        if (ledger.identity(executor.birth) != executor or not executor.alive
                or executor.tid == executor.tgid or access is None
                or self._sequence.get(executor.birth) is not entry
                or type(entry) is not SyscallInfo or entry.number not in (59, 322)):
            self._fail('original in-flight nonleader exec entry required')
        try:
            entry.require_native(1)
        except PtraceBoundaryError as error:
            self._fail(str(error))
        leader_birth, leader_access = self._stop_binding(executor.tgid)
        leader = ledger.identity(leader_birth)
        members = tuple(item for item in ledger.cleanup_identities()
                        if item.group_birth == executor.group_birth)
        if (not leader.alive or leader.group_birth != executor.group_birth
                or leader.tid != leader.tgid or leader_access is None
                or set(members) != {leader, executor}
                or any(ledger.identity(item.parent).group_birth == executor.group_birth
                       for item in ledger.cleanup_pending())
                or any(item.birth in self._exec_events or item.birth in self._exec_exits
                       for item in members)):
            self._fail('in-flight exec requires settled original leader/executor group')
        # A second running exec could retire the selected birth before its
        # event. Only the selected executor may have an unmatched exec ENTRY.
        self.cleanup_stable_group((leader,))
        return leader, leader_access

    def cleanup_failed_exec_exit(self, executor, info):
        """Settle an original owner's consumed failed native exec EXIT only.

        The cleanup owner authenticates the retained wait and absence of any
        resume attempt. Keep every failure latch; this clears only the original
        unmatched ENTRY so the unchanged birth group can be pinned and killed.
        No EXEC identity, normal observation, task credit or resume is produced.
        """
        entry = self._sequence.get(executor.birth)
        self.cleanup_inflight_exec_binding(executor, entry)
        if (type(info) is not SyscallInfo or info.operation != 2
                or info.is_error is not True or type(info.result) is not int
                or not -4095 <= info.result < 0):
            self._fail('failed native exec EXIT required for cleanup')
        try:
            info.require_native(2)
        except PtraceBoundaryError as error:
            self._fail(str(error))
        self._sequence[executor.birth] = None

    def cleanup_exec_event(self, old_tid, new_tid):
        """Install an original cleanup owner's authenticated EXEC without resume.

        No new event read, failure reset, syscall exit or observation is emitted.
        The caller must own the exact consumed wait and successful GETEVENTMSG.
        A previously matched event may be reused only with its original entry.
        """
        ticket, _ = self._stop_binding(old_tid)
        facts = self._exec_events.get(ticket)
        if facts is None:
            facts = self._match_exec_event(old_tid, new_tid)
        elif (facts.old_tid != old_tid or facts.new_tid != new_tid
              or facts.entry is not self._sequence.get(ticket)):
            self._fail('retained cleanup exec transition mismatch')
        return self._install_exec_identity(facts, cleanup=True)

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

    def cleanup_stable_group(self, identities, *, unbound_creations=()):
        """Validate a known group before TGID pinning, without clearing failure.

        An unmatched exec entry may already be running de_thread and silently
        retire a sibling. Reject it before opening any numeric PID. With every
        birth bound and no unbound reservation, other tasks cannot enter exec
        without another syscall stop; cleanup never resumes them. This is
        accounting under the original sole tracer, not kernel authentication.
        A bound legacy thread creation may await its parent's result: original
        native obligation and child/group birth must still match, and only the
        actual parent reap may close that pending obligation.
        During creation binding, explicitly selected unbound legacy thread
        reservations are checked against their original native obligations;
        every existing sibling must still be bound and have no unmatched EXEC.
        This exception never permits pinning until the child itself is bound.
        """
        if not identities or len({item.group_birth for item in identities}) != 1:
            self._fail('one original cleanup thread group required')
        ledger = self.lifecycle.ledger
        if not any(item.tid == item.tgid for item in identities):
            self._fail('thread cleanup requires live leader and settled creations')
        for pending in ledger.cleanup_pending():
            parent = ledger.identity(pending.parent)
            if parent.group_birth != identities[0].group_birth:
                continue
            if (pending in unbound_creations and pending.child is None
                    and pending.thread and parent in identities):
                self.cleanup_creation_binding(pending)
                continue
            # A bound thread has its own original birth, even while the parent
            # is stopped before clone's result. Kill closes neither obligation;
            # only actual parent reap settles it. Never admit an unknown child.
            if (not pending.thread or pending.kind != 'clone'
                    or pending.child != pending.reservation
                    or parent not in identities):
                self._fail('thread cleanup requires bound original thread creation')
            child = ledger.identity(pending.child)
            if (child.group_birth != parent.group_birth
                    or (child.alive and child not in identities)
                    or (not child.alive and child.terminal != 'reaped')):
                self._fail('thread cleanup child birth mismatch')
            entry = self._sequence.get(parent.birth)
            obligation = self.lifecycle._obligation(parent.birth)
            if (type(entry) is not SyscallInfo or entry.operation != 1
                    or entry.number != SYS_CLONE
                    or parent.birth not in self._pending
                    or self._pending[parent.birth] is not None
                    or obligation.reservation != pending.reservation
                    or obligation.expected_event != 'clone' or obligation.suppress_enosys):
                self._fail('thread cleanup requires original native clone obligation')
        for item in identities:
            access = self._task(item.birth, item.tid)
            entry = self._sequence.get(item.birth)
            if (ledger.identity(item.birth) != item or not item.alive or access is None
                    or item.birth not in self._sequence
                    or item.birth in self._exec_events
                    or (entry is not None and entry != 'newborn'
                        and type(entry) is not SyscallInfo)):
                self._fail('unmatched cleanup thread binding')
            if type(entry) is SyscallInfo:
                try:
                    entry.require_native(entry.operation)
                except PtraceBoundaryError as error:
                    self._fail(str(error))
                if entry.operation == 1 and entry.number in (59, 322):
                    self._fail('thread/exec identity requires integrated tree cleanup')

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
