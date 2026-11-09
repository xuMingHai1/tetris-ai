"""Creation dispatch composition; no wait, resume or capability probe.

The caller owns wait-stop charging, signal/stop authentication, trusted
coordinator admission and exec identity. Each birth owns a separate wire object
so a reaped clone3 intervention cannot leak into a reused numeric TID.
"""
from fixed_witness_replay_creation import SYS_CLONE, SYS_FORK, SYS_VFORK, SYS_CLONE3
from fixed_witness_replay_ptrace import PtraceBoundaryError
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
        self.stopped = None
        self._bind(coordinator_ticket, coordinator_tid)

    def _fail(self, reason):
        if self.stopped is None:
            self.stopped = reason
        raise CreationDispatchError(reason)

    def _running(self):
        if self.stopped is not None:
            self._fail('creation dispatcher stopped')

    def _bind(self, ticket, tid):
        if (type(ticket) is not int or ticket <= 0 or ticket in self._tasks
                or type(tid) is not int or not 0 < tid < 2**31
                or any(item[0] == tid for item in self._tasks.values())):
            self._fail('invalid or duplicate birth binding')
        access = self._factory()
        if any(item[1] is access for item in self._tasks.values()):
            self._fail('wire access must be exclusive to one birth')
        self._tasks[ticket] = (tid, access)

    def _task(self, ticket, tid):
        if (type(ticket) is not int or type(tid) is not int
                or ticket not in self._tasks or self._tasks[ticket][0] != tid):
            self._fail('unknown or mismatched birth binding')
        return self._tasks[ticket][1]

    def syscall_stop(self, ticket, tid):
        """Read the actual kernel operation; reserve before clone3 intervention.

        Noncreation exits need no inferred entry/exit parity here: a child's
        first syscall exit can arrive without an entry. Complete native syscall
        sequencing and exec handling remain the outer dispatcher's obligation.
        """
        self._running()
        access = self._task(ticket, tid)
        try:
            info = access.syscall_info(tid)
            if info.operation not in (1, 2):
                self._fail('unsupported syscall stop operation')
            info.require_native(info.operation)
            if info.operation == 1:
                if ticket in self._pending:
                    self._fail('new entry before creation result')
                if info.number not in (SYS_CLONE, SYS_FORK, SYS_VFORK, SYS_CLONE3):
                    return None
                obligation = self.lifecycle.entry(ticket, info.arch, info.number, info.arguments)
                self._pending[ticket] = None
                if obligation.suppress_enosys:
                    self._pending[ticket] = access.suppress_clone3(tid)
                return obligation
            if ticket in self._pending:
                token = self._pending[ticket]
                if token is not None:
                    access.finish_clone3(token)
                self.lifecycle.result(ticket, info.result, info.is_error)
                del self._pending[ticket]
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
            self._bind(child, child_tid)
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
        del self._tasks[ticket]

    def require_drained(self):
        """Bookkeeping only; never a capability or COMPLETE certificate."""
        if self._tasks or self._pending:
            self._fail('unreaped dispatch bindings')
        self._running()
        try:
            self.lifecycle.require_drained()
        except CreationLifecycleError as error:
            self._fail(str(error))
