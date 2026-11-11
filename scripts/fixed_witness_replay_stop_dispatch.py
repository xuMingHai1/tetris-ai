"""Post-handshake stop composition; no wait loop, probes or historical input.

Birth-bound newborns require their one initial kernel SIGSTOP and complete
options before first resume. EXEC migrates the exact executing birth; ordinary
delivery stops preserve the kernel signal. Job-control stops still fail closed.
"""
from fixed_witness_replay_ptrace import SignalInfo
from fixed_witness_replay_exec_observation import OwnedExecObservations


class StopDispatchError(ValueError):
    """No retry or further resume: whole-tree cleanup is required."""


class PostHandshakeDispatcher:
    """Consume one actual wait tuple under the launcher's original ownership.

    The single-thread owner must supply each wait result exactly once, without
    any intervening ptrace/resume, and retain cleanup identities on all errors.
    Kernel facts are read once after shared stop debit. At EXEC the TGID's old
    wire reads only GETEVENTMSG; the matched executor's wire owns all subsequent
    reads/resume, never the retired leader's clone3 state. This
    component cannot authenticate a fabricated/replayed wait tuple or replace
    the missing bounded wait/kill/reap owner and frozen ABI capability probe.
    """

    def __init__(self, launch, *, exec_observations=None):
        if exec_observations is not None and type(exec_observations) is not OwnedExecObservations:
            raise StopDispatchError('fixed exec observation configuration required')
        if (launch.stopped is not None or not launch.admitted
                or launch.handshake is None or not launch.handshake.completed
                or launch.reader.ledger is not launch.dispatcher.lifecycle.ledger
                or getattr(launch, '_post_handshake_claimed', False)):
            raise StopDispatchError('completed trusted coordinator handshake required')
        self.launch = launch
        self.stopped = None
        self._newborn = set()
        self._active_wait = None
        self._exec_generations = {}
        self._exec_observations = exec_observations
        if exec_observations is not None:
            exec_observations.claim(self)
        # A reused observation configuration must not strand a fresh launch.
        # Publish the launch claim only after the fallible config claim.
        launch._post_handshake_claimed = True
        launch._normal_wait_dispatcher = self

    def _accept_owned_wait(self, owner, receipt):
        """Only the registered wait loop may retain cleanup-readable facts."""
        if (getattr(self, '_wait_owner', None) is not owner
                or owner.last_wait is not receipt or self._active_wait is not None):
            raise StopDispatchError('exclusive actual wait record required')
        self._active_wait = receipt
        try:
            return self.accept(receipt.tid, receipt.raw)
        finally:
            self._active_wait = None

    def accept(self, tid, raw_status):
        """Account before identity/deadline checks; never retry uncertain resume.

        A terminal result releases only its bound birth via the creation
        dispatcher. Launcher cleanup_tid is intentionally retained: eventual
        whole-tree cleanup must use authenticated live birth state, never that
        stale number alone. After failure, this is not a cleanup reap API.
        """
        self.launch.retain_terminal_wait(tid, raw_status)
        access = _StopAccess(self, tid)
        try:
            facts = self.launch.reader.read(tid, raw_status, access)
            ticket, wire = access.binding()
            kind = facts.status.kind
            resume_signal = 0
            if kind in ('exited', 'signaled'):
                self.launch.dispatcher.reaped(ticket, tid)
                self.launch.unsettled_terminal_waits.discard(tid)
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
                self.launch.dispatcher.newborn_signal_stop(ticket, tid)
                wire.set_options(tid)
                self._newborn.remove(ticket)
            elif kind == 'syscall-stop':
                self.launch.dispatcher.syscall_info_stop(ticket, tid, facts.syscall)
            elif kind == 'ptrace-event' and facts.status.event in ('FORK', 'VFORK', 'CLONE'):
                child = self.launch.dispatcher.child_event(
                    ticket, tid, facts.status.event, facts.event_tid)
                self._newborn.add(child)
            elif kind == 'ptrace-event' and facts.status.event == 'EXEC':
                matched = self.launch.dispatcher.exec_event(facts.event_tid, tid)
                ticket, wire = self.launch.dispatcher.install_exec_identity(matched)
                # Count only installed successful EXECs; preserve the migrated
                # cleanup binding even if generation/observation later fails.
                generation = self._exec_generations.get(ticket, 0) + 1
                if generation >= 2**64:
                    raise StopDispatchError('exec generation exhausted')
                self._exec_generations[ticket] = generation
                if self._exec_observations is not None:
                    self._exec_observations.observe(self, matched, generation)
            elif kind == 'signal-stop':
                # GETSIGINFO must have succeeded on this exact birth. Preserve
                # the original signal AND kernel siginfo (no SETSIGINFO), in
                # particular seccomp SIGSYS; zero would silently suppress it.
                info = facts.signal_info
                if (type(info) is not SignalInfo
                        or type(info.signal) is not int or info.signal != facts.status.signal
                        or type(info.code) is not int or type(info.error) is not int
                        or info.signal in (9, 19, 20, 21, 22)):
                    # SIGKILL has no delivery stop. Non-SEIZE group-stop
                    # preservation needs a separate policy; never wake it by
                    # suppressing a stopping signal or infer delivery on EINVAL.
                    raise StopDispatchError('unsupported or invalid signal-delivery stop')
                resume_signal = info.signal
            else:
                raise StopDispatchError('unsupported stop kind')
            # Recheck original owner/deadline after fallible kernel/policy work.
            self.launch._owner_and_deadline()
            wire.resume_syscall(tid, resume_signal)
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
        if name == 'event_tid' and self.owner._active_wait is not None:
            receipt = self.owner._active_wait
            def read_event(tid):
                if receipt.event_attempted:
                    raise StopDispatchError('event message already attempted')
                receipt.event_attempted = True
                result = wire.event_tid(tid)
                receipt.event_tid = result
                receipt.event_read = True
                return result
            return read_event
        return getattr(wire, name)
