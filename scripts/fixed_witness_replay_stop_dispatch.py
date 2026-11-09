"""Post-handshake stop composition; no wait loop, probes or historical input.

Birth-bound newborns require their one initial kernel SIGSTOP and complete
options before first resume. Other signals and EXEC still fail closed.
"""
from fixed_witness_replay_ptrace import SignalInfo


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
        self._newborn = set()

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
                self._newborn.discard(ticket)
                return facts
            if ticket in self._newborn:
                # Non-SEIZE ptrace_init_task queues a bare pending SIGSTOP;
                # collect_signal synthesizes SI_USER with zero sender/uid.
                # This signature alone is NOT identity: the actual parent's
                # creation event must already have bound this birth and wire.
                if (kind != 'signal-stop' or facts.status.signal != 19
                        or type(facts.signal_info) is not SignalInfo
                        or facts.signal_info != SignalInfo(19, 0, 0, 0, 0)):
                    raise StopDispatchError('expected birth-bound newborn initial SIGSTOP')
                wire.set_options(tid)
                self._newborn.remove(ticket)
            elif kind == 'syscall-stop':
                self.launch.dispatcher.syscall_info_stop(ticket, tid, facts.syscall)
            elif kind == 'ptrace-event' and facts.status.event in ('FORK', 'VFORK', 'CLONE'):
                child = self.launch.dispatcher.child_event(
                    ticket, tid, facts.status.event, facts.event_tid)
                self._newborn.add(child)
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
        # Before newborn admission only GETSIGINFO is useful. Reject a syscall
        # or event stop before accessing the kernel, retaining cleanup credit.
        if ticket in self.owner._newborn and name != 'signal_info':
            raise StopDispatchError('newborn initial signal stop required')
        return getattr(wire, name)
