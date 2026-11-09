"""Creation dispatch composition; no wait, resume or capability probe.

The caller owns wait-stop charging, signal/stop authentication, trusted
coordinator admission and exec identity. Each birth owns a separate wire object
so a reaped clone3 intervention cannot leak into a reused numeric TID.
"""
from fixed_witness_replay_creation import SYS_CLONE, SYS_FORK, SYS_VFORK, SYS_CLONE3
from fixed_witness_replay_ptrace import PtraceBoundaryError, SyscallInfo
from fixed_witness_replay_lifecycle import CreationLifecycleError


class CreationDispatchError(ValueError):
    """Latch failure and clean up the whole session; do not resume."""


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
        """Arm one initial exit ONLY after the caller authenticates SIGSTOP.

        TRACEME's trusted coordinator may stop in kill/tgkill before syscall
        tracing starts. Its entry was never observed; SYSCALL resume can first
        report that signal-sending syscall's successful zero exit. This method
        does not authenticate the signal, install options or permit resume.
        Never call it for an arbitrary delivery stop or after syscall handling.
        """
        self._running()
        self._task(ticket, tid)
        if ticket != self._coordinator or not self._handshake_available:
            self._fail('coordinator initial handshake unavailable')
        self._handshake_available = False
        self._sequence[ticket] = 'coordinator-handshake'

    def stop_binding(self, tid):
        """Resolve a live numeric wait identity to its birth-exclusive wire.

        The outer owner must charge the wait before calling this method and
        must not resume between lookup, fact collection and policy dispatch.
        This lookup supplies no newborn admission or exec migration policy.
        """
        self._running()
        if type(tid) is int:
            for ticket, (bound_tid, access) in self._tasks.items():
                if tid == bound_tid and access is not None:
                    return ticket, access
        self._fail('unknown waited birth binding')

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
            if previous == 'coordinator-handshake':
                if info.is_error or info.result != 0:
                    self._fail('initial syscall return must be successful zero')
                self._sequence[ticket] = None
                return None
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
        del self._tasks[ticket]

    def require_drained(self):
        """Bookkeeping only; never a capability or COMPLETE certificate."""
        if self._tasks or self._pending or self._sequence:
            self._fail('unreaped dispatch bindings')
        self._running()
        try:
            self.lifecycle.require_drained()
        except CreationLifecycleError as error:
            self._fail(str(error))
