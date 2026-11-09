"""Post-handshake stop composition; no wait loop, probes or historical input.

Only the trusted coordinator is admitted for resume in this increment. Child
events retain their birth bindings for cleanup, but newborn stops, signals and
EXEC fail closed until their independent identity policies are implemented.
"""


class StopDispatchError(ValueError):
    """No retry or further resume: whole-tree cleanup is required."""


class PostHandshakeDispatcher:
    """Consume one actual wait tuple under the launcher's original ownership.

    The single-thread owner must supply each wait result exactly once, without
    any intervening ptrace/resume, and retain cleanup identities on all errors.
    Kernel facts are read once through the birth's own wire, after shared stop
    debit and before creation arbitration and the sole SYSCALL resume. This
    component cannot authenticate a fabricated/replayed wait tuple or replace
    the missing bounded wait/kill/reap owner and frozen ABI capability probe.
    """

    def __init__(self, launch):
        if (launch.stopped is not None or not launch.admitted
                or launch.handshake is None or not launch.handshake.completed
                or launch.reader.ledger is not launch.dispatcher.lifecycle.ledger
                or getattr(launch, '_post_handshake_claimed', False)):
            raise StopDispatchError('completed trusted coordinator handshake required')
        launch._post_handshake_claimed = True
        self.launch = launch
        self.stopped = None

    def accept(self, tid, raw_status):
        """Account before identity/deadline checks; never retry uncertain resume.

        A terminal result releases only its bound birth via the creation
        dispatcher. Launcher cleanup_tid is intentionally retained: eventual
        whole-tree cleanup must use authenticated live birth state, never that
        stale number alone. After failure, this is not a cleanup reap API.
        """
        access = _StopAccess(self, tid)
        try:
            facts = self.launch.reader.read(tid, raw_status, access)
            ticket, wire = access.binding()
            kind = facts.status.kind
            if kind in ('exited', 'signaled'):
                self.launch.dispatcher.reaped(ticket, tid)
                return facts
            if kind == 'syscall-stop':
                self.launch.dispatcher.syscall_info_stop(ticket, tid, facts.syscall)
            elif kind == 'ptrace-event' and facts.status.event in ('FORK', 'VFORK', 'CLONE'):
                self.launch.dispatcher.child_event(ticket, tid, facts.status.event, facts.event_tid)
            else:
                raise StopDispatchError('signal/exec stop policy not implemented')
            # Recheck original owner/deadline after fallible kernel/policy work.
            self.launch._owner_and_deadline()
            wire.resume_syscall(tid, 0)
            return facts
        except BaseException:
            self.stopped = 'post-handshake dispatch failed; cleanup required'
            self.launch.stopped = self.stopped
            raise


class _StopAccess:
    """Lazy binding ensures even foreign/deadline-rejected stops debit first."""

    def __init__(self, owner, tid):
        self.owner, self.tid = owner, tid
        self.resolved = None

    def binding(self):
        if self.resolved is None:
            launch = self.owner.launch
            if self.owner.stopped is not None or launch.stopped is not None:
                raise StopDispatchError('post-handshake dispatcher stopped')
            launch._owner_and_deadline()
            self.resolved = launch.dispatcher.stop_binding(self.tid)
        return self.resolved

    def __getattr__(self, name):
        ticket, wire = self.binding()
        # No kernel read or resume on a newborn until its initial stop can be
        # authenticated. Terminal reaps use binding() directly and remain valid.
        if ticket != self.owner.launch.ticket:
            raise StopDispatchError('newborn stop admission not implemented')
        return getattr(wire, name)
