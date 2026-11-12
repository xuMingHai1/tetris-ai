"""Owned event binding and terminal cleanup; no kill, resume or capability proof.

This supports process births and pinned stable thread groups with no unknown
children, including event-bound process creations awaiting parent results.
Unbound legacy thread events may join existing bound non-EXEC sibling groups
through their original leader and native obligation before pinning. A completed original
nonleader exec ENTRY may bind its pending TGID EXEC or failed native EXIT
before any pin; an original
failed wait owner's consumed EXEC can also be adopted.
A retained native failed nonleader exec EXIT may settle its original ENTRY
without resume before the unchanged group is pinned and killed.
Stops are never resumed. Only known births successfully pidfd-SIGKILLed by
the composing cleanup owner may drain queued signal/syscall stops.
"""
import errno
import math
import os
import time

from fixed_witness_replay_launch import CoordinatorLaunch
from fixed_witness_replay_wait import decode_wait_status
from fixed_witness_replay_wait_owner import WAIT_OPTIONS, BoundedWaitOwner, _OwnedWait
from fixed_witness_replay_stops import StopFacts


class CleanupReapError(ValueError):
    """Cleanup incomplete; preserve identities, original failure and evidence."""


class CleanupReaper:
    """Take over the sole wait owner once, using the launch's original deadline.

    No lookup/signalling by cleanup_tid, no wait(-1), no inferred ECHILD reap.
    Groups require a live original TGID and no unmatched exec entry before pin;
    multi-birth groups require pidfd SIGKILL before terminal/draining waits;
    exact original-owner event binding may precede pin acquisition.
    Successful return describes terminal accounting only, never suite success.
    """

    def __init__(self, launch, *, waitpid=os.waitpid, sleep=time.sleep):
        if (type(launch) is not CoordinatorLaunch or not launch.attempted
                or getattr(launch, '_cleanup_reap_claimed', False)):
            raise CleanupReapError('exclusive launched cleanup owner required')
        self.launch, self.ledger = launch, launch.ledger
        self.waitpid, self.sleep = waitpid, sleep
        self.started = False
        self.stopped = None
        self.reaped = []
        self.last_wait = None
        self._killed_births = ()
        self._unselected_births = ()
        self.drained_stops = 0
        self.last_drained_stop = None
        self.creation_binding_started = False
        self.bound_creations = []
        self.last_creation_event = None
        self.adopted_wait = None
        self.exec_binding_started = False
        self.adopted_exec_wait = None
        self.bound_exec = None
        self.inflight_exec_started = False
        self.last_exec_event = None
        self.last_inflight_exit = None
        self.failed_exec_exit_started = False
        self.adopted_failed_exec_exit = None
        self._check()
        launch._cleanup_reap_claimed = True
        # Permanently revoke every normal wait/resume route. Keep an existing
        # reason intact; cleanup neither resets failure nor starts another run.
        if launch.stopped is None:
            launch.stopped = 'terminal cleanup owns waits; normal work forbidden'

    def _check(self):
        launch = self.launch
        if (launch.system.getpid() != launch.owner
                or launch.system.listdir('/proc/self/task') != [str(launch.owner)]
                or self.ledger.identity(0).tid != launch.owner):
            raise CleanupReapError('original dedicated tracer owner required')
        now = launch.clock()
        if not math.isfinite(now) or now >= launch.deadline:
            raise CleanupReapError('original cleanup deadline exhausted')

    def _inventory(self):
        if self.stopped is not None:
            raise CleanupReapError('cleanup already failed')
        identities = self.ledger.cleanup_identities()
        if any(item.tid in self.launch.unsettled_terminal_waits for item in identities):
            raise CleanupReapError('consumed terminal wait has unresolved birth identity')
        for pending in self.ledger.cleanup_pending():
            # A real creation event already identifies this child's immutable
            # birth. Parent result is not required to kill/reap both tasks;
            # actual parent reap closes the obligation, never synthetic finish.
            if pending.child is None:
                raise CleanupReapError('unresolved creation requires integrated tree cleanup')
            if (pending.thread and (self.launch.dispatcher is None
                    or pending.parent not in self.launch.dispatcher.lifecycle._pending)):
                raise CleanupReapError('unresolved creation requires original native obligation')
            parent = self.ledger.identity(pending.parent)
            child = self.ledger.identity(pending.child)
            if (not parent.alive or pending.child != pending.reservation
                    or pending.kind not in ('fork', 'vfork', 'clone')
                    or (pending.thread and (pending.kind != 'clone'
                                            or child.group_birth != parent.group_birth))
                    or (not pending.thread and (child.group_birth != child.birth
                                                or child.tid != child.tgid))
                    or (not child.alive and child.terminal != 'reaped')):
                raise CleanupReapError('ambiguous pending creation birth')
        dispatcher = self.launch.dispatcher
        # Failure before the dispatcher was assigned is supported: the ledger
        # preserves the returned coordinator birth before fallible wire setup.
        if dispatcher is not None:
            for item in identities:
                dispatcher._task(item.birth, item.tid)
        elif identities and (len(identities) != 1
                              or identities[0].birth != self.launch.ticket):
            raise CleanupReapError('unbound cleanup inventory')
        groups = {}
        for item in identities:
            groups.setdefault(item.group_birth, []).append(item)
        if self._killed_births:
            # A killed group may outlive its leader's real reap. Preserve the
            # complete original set, including already reaped members; no new
            # birth, EXEC retirement or numeric rekey is an eligible target.
            if any(item not in self._killed_births + self._unselected_births for item in identities):
                raise CleanupReapError('killed birth identity changed')
            for original in self._unselected_births:
                if self.ledger.identity(original.birth) != original:
                    raise CleanupReapError('unselected cleanup birth changed')
            for original in self._killed_births:
                current = self.ledger.identity(original.birth)
                if current.alive:
                    if current != original:
                        raise CleanupReapError('killed birth identity changed')
                elif (current.terminal != 'reaped'
                      or (current.birth, current.tid, current.group_birth, current.tgid)
                      != (original.birth, original.tid, original.group_birth, original.tgid)):
                    raise CleanupReapError('killed birth lacks original terminal reap')
        for members in groups.values():
            if len(members) == 1 and members[0].tid == members[0].tgid:
                continue
            if self._killed_births:
                continue
            elif dispatcher is None:
                raise CleanupReapError('thread cleanup dispatcher required')
            else:
                dispatcher.cleanup_stable_group(tuple(members))
        return tuple(item for item in identities if item not in self._unselected_births)

    def bind_retained_exec(self):
        """Adopt only the failed normal wait owner's already consumed EXEC.

        No new wait, debit or resume. A successful original GETEVENTMSG is
        reused; a not-yet-attempted read is made once on the old TGID binding.
        Unknown/uncertain events stay incomplete, never guessed via ECHILD.
        """
        if self.exec_binding_started or self.started or self._killed_births:
            raise CleanupReapError('cleanup exec binding already attempted or too late')
        self.exec_binding_started = True
        try:
            self._check()
            post = getattr(self.launch, '_normal_wait_dispatcher', None)
            owner = getattr(post, '_wait_owner', None)
            if type(owner) is not BoundedWaitOwner or owner.last_wait is None:
                return
            receipt = owner.last_wait
            if type(receipt) is not _OwnedWait:
                raise CleanupReapError('original retained exec wait required')
            if receipt.completed:
                return
            status = decode_wait_status(receipt.raw)
            if status.kind != 'ptrace-event' or status.event != 'EXEC':
                return
            if (owner.dispatcher is not post or post.launch is not self.launch
                    or not owner.started or owner.stopped is None or post.stopped is None
                    or receipt.adopted):
                raise CleanupReapError('retained exec owner mismatch')
            if receipt.exec_installed:
                # Normal dispatch already installed the birth before a later
                # observation/resume failure. Do not migrate or read twice.
                return
            parent = receipt.parent
            dispatcher = self.launch.dispatcher
            if (dispatcher is None or receipt.tid != parent.tid
                    or self.ledger.identity(parent.birth) != parent
                    or not parent.alive or parent.tid != parent.tgid
                    or receipt.pending or receipt.resume_attempted
                    or receipt.tid in self.launch.unsettled_terminal_waits):
                raise CleanupReapError('retained exec birth mismatch')
            wire = dispatcher._task(parent.birth, parent.tid)
            if wire is None:
                raise CleanupReapError('retained exec TGID wire required')
            receipt.adopted = True
            self.adopted_exec_wait = receipt
            if receipt.event_attempted and not receipt.event_read:
                raise CleanupReapError('retained exec event read was uncertain')
            if not receipt.event_attempted:
                receipt.event_attempted = True
                receipt.event_tid = wire.event_tid(receipt.tid)
                receipt.event_read = True
            self._check()
            # Match the native entry and original live group before mutation.
            # Ledger checks still reject unresolved creations/unreaped siblings.
            self.bound_exec = dispatcher.cleanup_exec_event(receipt.event_tid, receipt.tid)
            receipt.exec_installed = True
            self._check()
        except BaseException:
            self.stopped = 'cleanup exec binding incomplete; preserve identities'
            raise

    def bind_failed_exec_exit(self):
        """Use a failed normal owner's already-read native failed exec EXIT.

        No wait, stop debit, ptrace read, normal dispatch or resume. A partial
        read, completed receipt, uncertain resume or changed birth stays closed.
        """
        if self.failed_exec_exit_started or self.started or self._killed_births:
            raise CleanupReapError('failed exec EXIT cleanup already attempted or too late')
        self.failed_exec_exit_started = True
        try:
            self._check()
            post = getattr(self.launch, '_normal_wait_dispatcher', None)
            owner = getattr(post, '_wait_owner', None)
            if type(owner) is not BoundedWaitOwner or owner.last_wait is None:
                return
            receipt = owner.last_wait
            if type(receipt) is not _OwnedWait:
                raise CleanupReapError('original failed exec EXIT wait required')
            facts, executor = receipt.facts, receipt.parent
            dispatcher = self.launch.dispatcher
            entry = dispatcher._sequence.get(executor.birth) if dispatcher else None
            if (receipt.completed or type(facts) is not StopFacts
                    or facts.syscall is None or facts.syscall.operation != 2
                    or entry is None or entry.number not in (59, 322)
                    or executor.tid == executor.tgid):
                return
            if (owner.dispatcher is not post or post.launch is not self.launch
                    or not owner.started or owner.stopped is None or post.stopped is None
                    or receipt.adopted or receipt.resume_attempted or receipt.pending
                    or receipt.event_attempted or receipt.exec_installed
                    or receipt.tid != executor.tid or facts.tid != executor.tid
                    or decode_wait_status(receipt.raw).kind != 'syscall-stop'
                    or facts.status != decode_wait_status(receipt.raw)
                    or any(item.tid in self.launch.unsettled_terminal_waits
                           for item in self.ledger.cleanup_identities())):
                raise CleanupReapError('original failed exec EXIT owner or birth mismatch')
            receipt.adopted = True
            self.adopted_failed_exec_exit = receipt
            dispatcher.cleanup_failed_exec_exit(executor, facts.syscall)
            self._check()
        except BaseException:
            self.stopped = 'failed exec EXIT cleanup incomplete; preserve identities'
            raise

    def bind_inflight_exec(self):
        """Finish the original owner's last fully resumed nonleader exec ENTRY.

        Poll the original TGID first for EXEC(old TID), then the executor for
        a failed native EXIT. ECHILD on the old executor proves nothing; keep
        polling the TGID within the original deadline. Never resume, retry a
        read, reset failure/deadline or mint an EXEC observation/generation.
        Other unmatched entries remain unsupported by the existing inventory.
        """
        if self.inflight_exec_started or self.started or self._killed_births:
            raise CleanupReapError('in-flight cleanup exec already attempted or too late')
        self.inflight_exec_started = True
        try:
            self._check()
            post = getattr(self.launch, '_normal_wait_dispatcher', None)
            owner = getattr(post, '_wait_owner', None)
            if type(owner) is not BoundedWaitOwner or owner.last_wait is None:
                return
            receipt = owner.last_wait
            if type(receipt) is not _OwnedWait:
                raise CleanupReapError('original in-flight exec wait required')
            if not receipt.completed:
                return
            facts = receipt.facts
            entry = getattr(facts, 'syscall', None)
            executor = receipt.parent
            if (entry is None or entry.number not in (59, 322)
                    or entry.operation != 1 or executor.tid == executor.tgid):
                return
            if (owner.dispatcher is not post or post.launch is not self.launch
                    or not owner.started or owner.stopped is None or post.stopped is None
                    or receipt.adopted
                    or receipt.pending or receipt.tid != executor.tid
                    or receipt.event_attempted or receipt.exec_installed
                    or decode_wait_status(receipt.raw).kind != 'syscall-stop'
                    or facts.tid != executor.tid):
                raise CleanupReapError('in-flight exec owner or completed entry mismatch')
            dispatcher = self.launch.dispatcher
            leader, access = dispatcher.cleanup_inflight_exec_binding(executor, entry)
            receipt.adopted = True
            target = leader
            while True:
                self._check()
                if dispatcher.cleanup_inflight_exec_binding(executor, entry) != (leader, access):
                    raise CleanupReapError('in-flight exec original binding changed')
                if any(item.tid in self.launch.unsettled_terminal_waits
                       for item in (leader, executor)):
                    raise CleanupReapError('in-flight exec terminal identity quarantined')
                try:
                    result = self.waitpid(target.tid, WAIT_OPTIONS)
                except InterruptedError:
                    continue
                except ChildProcessError as error:
                    if target != executor or error.errno != errno.ECHILD:
                        raise
                    # The old executor may disappear during de_thread. This
                    # is not migration or reap; idle before retrying the TGID.
                    target = leader
                    self._inflight_idle()
                    continue
                self.last_wait = (target.birth, result)
                if type(result) is not tuple or len(result) != 2:
                    raise CleanupReapError('malformed in-flight exec wait')
                tid, raw = result
                self.launch.retain_terminal_wait(tid, raw)
                if type(raw) is int and raw & 0xff == 0x7f:
                    self.ledger.cleanup_wait_stop()
                self._check()
                if type(tid) is not int or type(raw) is not int:
                    raise CleanupReapError('invalid in-flight exec wait tuple')
                if tid == 0 and raw == 0:
                    if target == leader:
                        target = executor
                    else:
                        target = leader
                        self._inflight_idle()
                    continue
                if (tid != target.tid or self.ledger.identity(leader.birth) != leader
                        or self.ledger.identity(executor.birth) != executor):
                    raise CleanupReapError('in-flight exec wait birth mismatch')
                status = decode_wait_status(raw)
                if target == executor:
                    if status.kind != 'syscall-stop':
                        raise CleanupReapError('matching in-flight failed exec EXIT required')
                    # Retain before the sole fallible read; no normal reader,
                    # extra debit or resume, even if validation later fails.
                    self.last_inflight_exit = (executor, raw, None)
                    executor_access = dispatcher._task(executor.birth, executor.tid)
                    info = executor_access.syscall_info(tid)
                    self.last_inflight_exit = (executor, raw, info)
                    self._check()
                    dispatcher.cleanup_inflight_exec_binding(executor, entry)
                    dispatcher.cleanup_failed_exec_exit(executor, info)
                    self._check()
                    return
                if status.kind != 'ptrace-event' or status.event != 'EXEC':
                    raise CleanupReapError('matching in-flight EXEC event required')
                # Keep consumed raw/event facts even if read or subsequent
                # validation fails. The whole phase is permanently one-shot.
                self.last_exec_event = (leader, executor, raw, None)
                old_tid = access.event_tid(tid)
                self.last_exec_event = (leader, executor, raw, old_tid)
                self._check()
                if type(old_tid) is not int or old_tid != executor.tid:
                    raise CleanupReapError('in-flight EXEC old TID mismatch')
                dispatcher.cleanup_inflight_exec_binding(executor, entry)
                self.bound_exec = dispatcher.cleanup_exec_event(old_tid, tid)
                self._check()
                return
        except BaseException:
            self.stopped = 'in-flight cleanup exec incomplete; preserve identities'
            raise

    def _inflight_idle(self):
        """Bound an idle original leader/executor round by the same deadline."""
        self._check()
        remaining = self.launch.deadline - self.launch.clock()
        if not math.isfinite(remaining) or remaining <= 0:
            raise CleanupReapError('original cleanup deadline exhausted')
        self.sleep(min(0.01, remaining))

    def bind_creation_events(self):
        """Consume in-flight creation events before pidfd inventory acquisition.

        Explicit, one-shot cleanup phase: never normal StopReader/dispatch,
        resume, syscall-result settlement or a new reservation. Only positive
        known original leader TIDs are polled, including legacy CLONE_THREAD;
        no child stop or unknown task is guessed. Existing multi-birth groups
        require a live leader, exact bound siblings with no unmatched EXEC,
        and original native legacy thread obligations before any event wait.
        Other stops, ECHILD and terminal results fail closed (terminal PID is
        quarantined before checks). The original deadline bounds idle polling.
        """
        if self.creation_binding_started or self.started or self._killed_births:
            raise CleanupReapError('cleanup creation binding already attempted or too late')
        self.creation_binding_started = True
        try:
            while True:
                self._check()
                identities = self.ledger.cleanup_identities()
                pending = tuple(item for item in self.ledger.cleanup_pending()
                                if item.child is None)
                if not pending:
                    self._inventory()
                    return tuple(self.bound_creations)
                if any(item.tid in self.launch.unsettled_terminal_waits for item in identities):
                    raise CleanupReapError('ambiguous cleanup creation inventory')
                dispatcher = self.launch.dispatcher
                if dispatcher is None:
                    raise CleanupReapError('cleanup creation dispatcher required')
                groups = {}
                for identity in identities:
                    dispatcher._task(identity.birth, identity.tid)
                    groups.setdefault(identity.group_birth, []).append(identity)
                for members in groups.values():
                    if len(members) != 1 or members[0].tid != members[0].tgid:
                        dispatcher.cleanup_stable_group(
                            tuple(members), unbound_creations=pending)
                # Validate the whole selected batch BEFORE the first wait.
                bindings = tuple((item, dispatcher.cleanup_creation_binding(item))
                                 for item in pending)
                progress = False
                for item, (parent, access, expected_event) in bindings:
                    self._check()
                    receipt = self._retained_wait(item, parent, expected_event)
                    if receipt is not None:
                        # This stop was already debited by normal StopReader.
                        # A successful GETEVENTMSG is reused; an uncertain read
                        # is never retried, and no second wait consumes the stop.
                        if receipt.event_attempted and not receipt.event_read:
                            raise CleanupReapError('retained event read was uncertain')
                        if not receipt.event_attempted:
                            receipt.event_attempted = True
                            receipt.event_tid = access.event_tid(parent.tid)
                            receipt.event_read = True
                        child_tid = receipt.event_tid
                        status = decode_wait_status(receipt.raw)
                        self.last_creation_event = (item, parent, status, child_tid)
                        self._check()
                        child = dispatcher.cleanup_child_event(item, status.event, child_tid)
                        self.bound_creations.append((parent.birth, child, child_tid))
                        self._check()
                        progress = True
                        continue
                    try:
                        result = self.waitpid(parent.tid, WAIT_OPTIONS)
                    except InterruptedError:
                        continue
                    self.last_wait = (parent.birth, result)
                    if type(result) is not tuple or len(result) != 2:
                        raise CleanupReapError('malformed cleanup creation wait')
                    tid, raw = result
                    self.launch.retain_terminal_wait(tid, raw)
                    if type(raw) is int and raw & 0xff == 0x7f:
                        self.ledger.cleanup_wait_stop()
                    self._check()
                    if type(tid) is not int or type(raw) is not int:
                        raise CleanupReapError('invalid cleanup creation wait tuple')
                    if tid == 0 and raw == 0:
                        continue
                    if tid != parent.tid or self.ledger.identity(parent.birth) != parent:
                        raise CleanupReapError('cleanup creation parent mismatch')
                    status = decode_wait_status(raw)
                    if status.kind != 'ptrace-event' or status.event != expected_event:
                        raise CleanupReapError('matching in-flight creation event required')
                    child_tid = access.event_tid(tid)
                    self.last_creation_event = (item, parent, status, child_tid)
                    self._check()
                    child = dispatcher.cleanup_child_event(item, status.event, child_tid)
                    # Binding is retained even if the post-read deadline fails.
                    self.bound_creations.append((parent.birth, child, child_tid))
                    self._check()
                    progress = True
                if not progress:
                    self._check()
                    remaining = self.launch.deadline - self.launch.clock()
                    if not math.isfinite(remaining) or remaining <= 0:
                        raise CleanupReapError('original cleanup deadline exhausted')
                    self.sleep(min(0.01, remaining))
        except BaseException:
            self.stopped = 'cleanup creation binding incomplete; preserve identities'
            raise

    def _retained_wait(self, pending, parent, expected_event):
        """Adopt a failed normal owner's exact record once, never raw tuples.

        Only the registered BoundedWaitOwner issues records before dispatch.
        Direct accept(), decoded StopFacts and caller-created tuples are not
        provenance. Birth and reservation snapshots must still match exactly.
        """
        post = getattr(self.launch, '_normal_wait_dispatcher', None)
        if post is None:
            return None
        owner = getattr(post, '_wait_owner', None)
        if type(owner) is not BoundedWaitOwner or owner.last_wait is None:
            return None
        receipt = owner.last_wait
        if type(receipt) is not _OwnedWait:
            raise CleanupReapError('unmatched retained wait owner or birth')
        if receipt.completed:
            return None
        if receipt.parent.birth != parent.birth:
            return None
        if (owner.dispatcher is not post or post.launch is not self.launch
                or not owner.started or owner.stopped is None or post.stopped is None
                or receipt.adopted or receipt.parent != parent
                or receipt.pending != (pending,) or receipt.tid != parent.tid):
            raise CleanupReapError('retained wait no longer matches pending creation')
        receipt.adopted = True
        self.adopted_wait = receipt
        status = decode_wait_status(receipt.raw)
        if status.kind != 'ptrace-event' or status.event != expected_event:
            raise CleanupReapError('retained wait is not the matching creation event')
        return receipt

    def _after_pidfd_kill(self, identities, *, partial=False):
        """Composing owner calls only after every pinned SIGKILL succeeded.

        Freeze exact birth identities, not TIDs or a generic permission flag.
        A failed later pin may leave a strict subset of complete groups killed.
        Freeze every unselected birth too; never wait on it or count its absence
        as cleanup. The composing owner must retain the original pin failure.
        This does not authorize ptrace event handling or further task creation.
        """
        self._check()
        inventory = self._inventory()
        if self.started or self._killed_births:
            raise CleanupReapError('settled killed inventory required before wait')
        if partial:
            if (type(identities) is not tuple or not identities
                    or len(set(identities)) != len(identities)
                    or any(item not in inventory for item in identities)
                    or any(item.group_birth in {k.group_birth for k in identities}
                           for item in inventory if item not in identities)):
                raise CleanupReapError('complete pinned groups required for partial cleanup')
            self._unselected_births = tuple(item for item in inventory if item not in identities)
        elif inventory != identities:
            raise CleanupReapError('settled killed inventory required before wait')
        self._killed_births = identities

    def _consume(self, item, result):
        self.last_wait = (item.birth, result)
        if type(result) is not tuple or len(result) != 2:
            raise CleanupReapError('malformed cleanup wait result')
        tid, raw = result
        # A terminal wait can already have released the kernel PID even if
        # later owner/deadline/tuple/birth validation fails. Never lose that
        # ambiguity or let another cleanup open a reused numeric PID.
        self.launch.retain_terminal_wait(tid, raw)
        # Shared debit precedes deadline/owner/tuple validation, including a
        # wrong target stop. Never use the latched normal StopReader here.
        if type(raw) is int and raw & 0xff == 0x7f:
            self.ledger.cleanup_wait_stop()
        self._check()
        if type(tid) is not int or type(raw) is not int:
            raise CleanupReapError('invalid cleanup wait tuple')
        if tid == 0 and raw == 0:
            return False
        if tid != item.tid:
            raise CleanupReapError('cleanup wait target mismatch')
        status = decode_wait_status(raw)
        if self.ledger.identity(item.birth) != item:
            raise CleanupReapError('cleanup birth changed during wait')
        if status.kind not in ('exited', 'signaled'):
            if (item in self._killed_births
                    and status.kind in ('signal-stop', 'syscall-stop')):
                self.drained_stops += 1
                self.last_drained_stop = (item.birth, item.tid, status)
                return False
            raise CleanupReapError('cleanup observed stop; termination remains required')
        if self.launch.dispatcher is None:
            self.ledger.reaped(item.birth)
        else:
            self.launch.dispatcher.reaped(item.birth, item.tid)
        self.launch.unsettled_terminal_waits.discard(item.tid)
        self.reaped.append((item.birth, item.tid, status))
        pin = self.launch.startup_pin
        if pin is not None and pin.pid == item.tid:
            pin.release()
        return True

    def run(self):
        """Drain only actual terminal waits; failure is one-shot, never retried."""
        if self.started:
            raise CleanupReapError('cleanup reap already attempted')
        self.started = True
        try:
            while True:
                self._check()
                identities = self._inventory()
                if (not self._killed_births
                        and (len({item.group_birth for item in identities}) != len(identities)
                             or any(item.tid != item.tgid for item in identities))):
                    raise CleanupReapError('thread/exec cleanup requires pinned group SIGKILL')
                if not identities:
                    if self.ledger.totals.live != 1 + len(self._unselected_births):
                        raise CleanupReapError('unsettled live reservations')
                    return tuple(self.reaped)
                progress = False
                for item in identities:
                    self._check()
                    try:
                        result = self.waitpid(item.tid, WAIT_OPTIONS)
                    except InterruptedError:
                        continue
                    progress = self._consume(item, result) or progress
                if not progress:
                    self._check()
                    remaining = self.launch.deadline - self.launch.clock()
                    if not math.isfinite(remaining) or remaining <= 0:
                        raise CleanupReapError('original cleanup deadline exhausted')
                    self.sleep(min(0.01, remaining))
        except BaseException:
            self.stopped = 'terminal cleanup incomplete; preserve identities'
            raise
