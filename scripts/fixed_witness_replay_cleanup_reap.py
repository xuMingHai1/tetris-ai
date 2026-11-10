"""Terminal-only cleanup waits; no kill, resume, probe or COMPLETE receipt.

This intentionally supports settled process births only. The trusted host must
still terminate tasks and handle unresolved creations/thread exec transitions.
Stops are never resumed. Only settled births successfully pidfd-SIGKILLed by
the composing cleanup owner may drain queued signal/syscall stops.
"""
import math
import os
import time

from fixed_witness_replay_launch import CoordinatorLaunch
from fixed_witness_replay_wait import decode_wait_status
from fixed_witness_replay_wait_owner import WAIT_OPTIONS


class CleanupReapError(ValueError):
    """Cleanup incomplete; preserve identities, original failure and evidence."""


class CleanupReaper:
    """Take over the sole wait owner once, using the launch's original deadline.

    No lookup/signalling by cleanup_tid, no wait(-1), no inferred ECHILD reap.
    A settled, single live birth at each original group TGID cannot silently
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
        identities = self.ledger.cleanup_identities()
        if any(item.tid in self.launch.unsettled_terminal_waits for item in identities):
            raise CleanupReapError('consumed terminal wait has unresolved birth identity')
        if self.ledger.totals.pending:
            raise CleanupReapError('unresolved creation requires integrated tree cleanup')
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
