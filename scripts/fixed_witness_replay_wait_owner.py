"""Bounded targeted wait owner; no CLI, probe, cleanup or historical execution.

Unknown newborn stops remain unconsumed in the kernel until the parent's
creation event binds their birth. Never wait(-1), guess a birth or stage a raw
unknown stop. Native scheduling still requires the host's original watchdog.
"""
import errno
import os
import time
from dataclasses import dataclass

from fixed_witness_replay_abi_model import ExecIdentity
from fixed_witness_replay_stop_dispatch import PostHandshakeDispatcher
from fixed_witness_replay_tasks import LIVE_TASKS, TASK_ATTEMPTS
from fixed_witness_replay_wait import decode_wait_status


# Linux UAPI: nonblocking, all clone children, only this tracer thread's tasks.
WAIT_OPTIONS = 1 | 0x40000000 | 0x20000000


class WaitOwnerError(ValueError):
    """Whole-tree cleanup required; never retry a failed wait session."""


@dataclass(frozen=True)
class SettledReap:
    """Retained owner facts, NOT an ABI/filter/no-injection success receipt.

    Only require_settled_reap on the original owner checks provenance. Constructing
    equal values, or retaining a record after session failure, grants no authority.
    An optional EXEC identity comes only from the dispatcher's installed epoch
    sampled before this wait, never inferred from terminal bits or proc data.
    """

    before: object
    after: object
    raw: int
    status: object
    exec_identity: object = None


@dataclass
class _OwnedWait:
    """Internal sole-waiter record, never supplied by a raw-stop caller.

    Capture pre-wait birth/reservation and retain even partial GETEVENTMSG work.
    A failed request is uncertain and must never be retried during cleanup.
    """

    parent: object
    pending: tuple
    tid: int
    raw: int
    event_attempted: bool = False
    event_read: bool = False
    event_tid: object = None
    adopted: bool = False


class BoundedWaitOwner:
    """Claim one completed handshake's dispatcher and consume actual waits.

    The dedicated single-thread tracer must be the only waiter throughout;
    no SIGCHLD handler or other component may reap/ptrace these tasks. Native
    waitpid is WNOHANG, polled in birth-ticket round-robin order with at most
    the accepted live cap per round. Sleep is at most 10ms and the remaining
    original deadline, never a new timeout. Errors retain cleanup identities.

    EXEC rekeys the next inventory; authenticated ordinary delivery preserves
    the signal. WNOHANG polling continues servicing a vfork child while its
    parent's syscall result is unavailable. Job-control stops fail closed. Drained
    bookkeeping is NOT a capability, whole-tree cleanup or COMPLETE receipt.
    """

    def __init__(self, dispatcher, *, waitpid=os.waitpid, sleep=time.sleep):
        if (type(dispatcher) is not PostHandshakeDispatcher
                or getattr(dispatcher, '_wait_owner_claimed', False)):
            raise WaitOwnerError('exclusive post-handshake wait owner required')
        self.dispatcher = dispatcher
        self.launch = dispatcher.launch
        self._check()
        dispatcher._wait_owner_claimed = True
        self.waitpid, self.sleep = waitpid, sleep
        self._last_birth = 0
        self.started = False
        self.stopped = None
        self.last_wait = None
        self._settled_reaps = {}
        dispatcher._wait_owner = self

    def _check(self):
        if (self.dispatcher.stopped is not None or self.launch.stopped is not None
                or self.launch.reader.stopped is not None):
            raise WaitOwnerError('wait session stopped')
        self.launch._owner_and_deadline()

    @property
    def settled_reaps(self):
        """Immutable diagnostic snapshot, retained even after later failure."""
        return tuple(self._settled_reaps.values())

    def _reap_gates(self):
        creation = self.launch.dispatcher
        if (self.stopped is not None
                or getattr(self.dispatcher, '_wait_owner', None) is not self
                or getattr(self.launch, '_normal_wait_dispatcher', None) is not self.dispatcher
                or self.dispatcher.launch is not self.launch
                or self.launch.reader.ledger is not self.launch.ledger
                or creation.lifecycle.ledger is not self.launch.ledger
                or creation.stopped is not None or creation.lifecycle.stopped is not None
                or creation.lifecycle.policy.stopped is not None):
            raise WaitOwnerError('settled reap owner or shared gate failed')
        self._check()

    def require_settled_reap(self, record):
        """Validate retained membership and current gates; never consume/resume.

        No raw tuple, copied dataclass, cleanup reap or another owner's record
        can substitute. Later failures revoke usability but preserve diagnostics.
        The eventual ABI adapter still needs independently authenticated EXEC,
        consumed ENTRY, manifest, filter and no-injection facts.
        """
        try:
            self._reap_gates()
            if (type(record) is not SettledReap
                    or self._settled_reaps.get(record.before.birth) is not record
                    or self.launch.ledger.identity(record.before.birth) != record.after):
                raise WaitOwnerError('original settled reap record required')
        except BaseException:
            self.stopped = 'settled reap validation failed; cleanup required'
            self.dispatcher.stopped = self.launch.stopped = self.stopped
            raise

    def require_exec_reap(self, record, identity):
        """Match the original settled record's installed EXEC epoch, read-only.

        This does not authenticate a probe binding, consumed ENTRY, SIGSYS,
        filter or no-injection evidence. A future adapter must establish those
        independently. Pre-EXEC reaps have no epoch and cannot pass this gate.
        """
        try:
            self.require_settled_reap(record)
            if (type(identity) is not ExecIdentity or not identity.valid()
                    or record.exec_identity != identity
                    or self._exec_identity(record.before) != identity):
                raise WaitOwnerError('settled reap EXEC identity mismatch')
        except BaseException:
            self.stopped = 'settled EXEC reap validation failed; cleanup required'
            self.dispatcher.stopped = self.launch.stopped = self.stopped
            raise

    def _exec_identity(self, parent):
        generation = self.dispatcher._exec_generations.get(parent.birth)
        if generation is None:
            return None
        identity = ExecIdentity(parent.birth, parent.group_birth, parent.tid, generation)
        if not identity.valid():
            raise WaitOwnerError('invalid installed EXEC epoch')
        return identity

    def _retain_reap(self, parent, tid, raw, facts, exec_identity):
        if facts.status.kind not in ('exited', 'signaled'):
            return
        self._reap_gates()
        after = self.launch.ledger.identity(parent.birth)
        if (not parent.alive or parent.tid != tid or parent.terminal is not None
                or after.alive or after.terminal != 'reaped'
                or (after.birth, after.tid, after.group_birth, after.tgid) != (
                    parent.birth, parent.tid, parent.group_birth, parent.tgid)
                or tid in self.launch.unsettled_terminal_waits
                or any(ticket == parent.birth or bound_tid == tid
                       for ticket, bound_tid in self.launch.dispatcher.waiting_births())
                or facts.tid != tid or facts.status != decode_wait_status(raw)
                or self._exec_identity(parent) != exec_identity
                or parent.birth in self._settled_reaps
                or len(self._settled_reaps) >= TASK_ATTEMPTS - 1):
            raise WaitOwnerError('terminal reap settlement mismatch')
        self._settled_reaps[parent.birth] = SettledReap(
            parent, after, raw, facts.status, exec_identity)
        observations = self.dispatcher._exec_observations
        if observations is not None:
            observations.observe_reap(self.dispatcher, self._settled_reaps[parent.birth])

    def _reject_wait(self, tid, status):
        # Account any observed stop BEFORE rejecting the impossible wait tuple;
        # terminal mismatches never settle a birth or release live credit.
        self.launch.reader.read(tid, status, _RejectAccess())
        raise WaitOwnerError('wait result does not match targeted birth')

    def _round(self):
        self._check()
        births = self.launch.dispatcher.waiting_births()
        if not births:
            self.launch.dispatcher.require_drained()
            return 'drained'
        if len(births) >= LIVE_TASKS:
            raise WaitOwnerError('bound task inventory exceeds accepted live cap')
        births = sorted(births, key=lambda item: (item[0] <= self._last_birth, item[0]))
        for ticket, tid in births:
            self._check()
            parent = self.launch.ledger.identity(ticket)
            # Freeze the installed epoch before the fallible wait. A terminal
            # dispatch must not change it; EXEC stops themselves retain no reap.
            exec_identity = self._exec_identity(parent)
            pending = tuple(item for item in self.launch.ledger.cleanup_pending()
                            if item.parent == ticket and item.child is None)
            try:
                result = self.waitpid(tid, WAIT_OPTIONS)
            except InterruptedError:
                # EINTR is not a stop and creates no new allowance. Move on to
                # the next birth; the original deadline is checked each time.
                continue
            except ChildProcessError as error:
                # de_thread exchanges TIDs and silently retires the old leader.
                # An old nonleader target can disappear BEFORE its TGID's EXEC
                # stop is consumed. Never treat ECHILD as reap or migration.
                if (error.errno != errno.ECHILD
                        or not self.launch.dispatcher.awaiting_exec_migration(ticket, tid)):
                    raise
                continue
            if type(result) is not tuple or len(result) != 2:
                raise WaitOwnerError('malformed wait result')
            waited, status = result
            self.launch.retain_terminal_wait(waited, status)
            if type(waited) is not int or type(status) is not int:
                self._reject_wait(waited, status)
            if waited == 0:
                if status != 0:
                    self._reject_wait(waited, status)
                continue
            if waited != tid:
                self._reject_wait(waited, status)
            # No owner/deadline recheck before accept: an observed stop must
            # debit even if the wait crossed the deadline or ownership changed.
            self.last_wait = _OwnedWait(parent, pending, waited, status)
            facts = self.dispatcher._accept_owned_wait(self, self.last_wait)
            self._retain_reap(parent, waited, status, facts, exec_identity)
            self._last_birth = ticket
            return 'event'
        self._check()
        remaining = self.launch.deadline - self.launch.clock()
        if remaining <= 0:
            raise WaitOwnerError('original deadline exhausted')
        self.sleep(min(0.01, remaining))
        self._check()
        return 'idle'

    def run(self):
        """Run once to drained bookkeeping or fail closed, without cleanup retry."""
        if self.started:
            raise WaitOwnerError('wait session already attempted')
        self.started = True
        try:
            while self._round() != 'drained':
                pass
            return self.launch.ledger.totals
        except BaseException:
            self.stopped = 'bounded wait owner failed; cleanup required'
            self.dispatcher.stopped = self.launch.stopped = self.stopped
            raise


class _RejectAccess:
    def __getattr__(self, name):
        raise OSError('mismatched wait result: no task access')
