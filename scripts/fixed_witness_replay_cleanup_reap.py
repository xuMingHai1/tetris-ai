"""Terminal-only cleanup waits; no kill, resume, probe or COMPLETE receipt.

This supports process births with no unknown children, including event-bound
creations awaiting parent results. The trusted host must still handle unbound
creations and thread exec transitions.
Stops are never resumed. Only known births successfully pidfd-SIGKILLed by
the composing cleanup owner may drain queued signal/syscall stops.
"""
import math
import os
import time

from fixed_witness_replay_launch import CoordinatorLaunch
from fixed_witness_replay_wait import decode_wait_status
from fixed_witness_replay_wait_owner import WAIT_OPTIONS, BoundedWaitOwner, _OwnedWait


class CleanupReapError(ValueError):
    """Cleanup incomplete; preserve identities, original failure and evidence."""


class CleanupReaper:
    """Take over the sole wait owner once, using the launch's original deadline.

    No lookup/signalling by cleanup_tid, no wait(-1), no inferred ECHILD reap.
    A single live birth at each original group TGID cannot silently
    retire via another thread's exec. Reject all other inventories before wait.
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
        self.drained_stops = 0
        self.last_drained_stop = None
        self.creation_binding_started = False
        self.bound_creations = []
        self.last_creation_event = None
        self.adopted_wait = None
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
            if pending.child is None or pending.thread:
                raise CleanupReapError('unresolved creation requires integrated tree cleanup')
            parent = self.ledger.identity(pending.parent)
            child = self.ledger.identity(pending.child)
            if (not parent.alive or pending.child != pending.reservation
                    or pending.kind not in ('fork', 'vfork', 'clone')
                    or child.group_birth != child.birth
                    or child.tid != child.tgid
                    or (not child.alive and child.terminal != 'reaped')):
                raise CleanupReapError('ambiguous pending process birth')
        groups = [item.group_birth for item in identities]
        if (len(groups) != len(set(groups))
                or any(item.tid != item.tgid for item in identities)):
            raise CleanupReapError('thread/exec identity requires integrated tree cleanup')
        dispatcher = self.launch.dispatcher
        # Failure before the dispatcher was assigned is supported: the ledger
        # preserves the returned coordinator birth before fallible wire setup.
        if dispatcher is not None:
            for item in identities:
                dispatcher._task(item.birth, item.tid)
        elif identities and (len(identities) != 1
                              or identities[0].birth != self.launch.ticket):
            raise CleanupReapError('unbound cleanup inventory')
        return identities

    def bind_creation_events(self):
        """Consume in-flight process events before pidfd inventory acquisition.

        Explicit, one-shot cleanup phase: never normal StopReader/dispatch,
        resume, syscall-result settlement or a new reservation. Only positive
        known parent TIDs are polled; no child stop or unknown task is guessed.
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
                if (len({item.group_birth for item in identities}) != len(identities)
                        or any(item.tid != item.tgid
                               or item.tid in self.launch.unsettled_terminal_waits
                               for item in identities)):
                    raise CleanupReapError('ambiguous cleanup creation inventory')
                pending = tuple(item for item in self.ledger.cleanup_pending()
                                if item.child is None)
                if not pending:
                    self._inventory()
                    return tuple(self.bound_creations)
                dispatcher = self.launch.dispatcher
                if dispatcher is None:
                    raise CleanupReapError('cleanup creation dispatcher required')
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

    def _after_pidfd_kill(self, identities):
        """Composing owner calls only after every pinned SIGKILL succeeded.

        Freeze exact birth identities, not TIDs or a generic permission flag.
        This does not authorize ptrace event handling or further task creation.
        """
        self._check()
        if self.started or self._killed_births or self._inventory() != identities:
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
                if not identities:
                    if self.ledger.totals.live != 1:
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
