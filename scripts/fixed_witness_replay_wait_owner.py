"""Bounded targeted wait owner; no CLI, probe, cleanup or historical execution.

Unknown newborn stops remain unconsumed in the kernel until the parent's
creation event binds their birth. Never wait(-1), guess a birth or stage a raw
unknown stop. Native scheduling still requires the host's original watchdog.
"""
import errno
import os
import time

from fixed_witness_replay_stop_dispatch import PostHandshakeDispatcher
from fixed_witness_replay_tasks import LIVE_TASKS


# Linux UAPI: nonblocking, all clone children, only this tracer thread's tasks.
WAIT_OPTIONS = 1 | 0x40000000 | 0x20000000


class WaitOwnerError(ValueError):
    """Whole-tree cleanup required; never retry a failed wait session."""


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

    def _check(self):
        if (self.dispatcher.stopped is not None or self.launch.stopped is not None
                or self.launch.reader.stopped is not None):
            raise WaitOwnerError('wait session stopped')
        self.launch._owner_and_deadline()

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
            self.dispatcher.accept(waited, status)
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
