"""Initial coordinator wait only; no native probe, launch or cleanup policy."""
import os
import time

from fixed_witness_replay_launch import CoordinatorLaunch
from fixed_witness_replay_stop_dispatch import PostHandshakeDispatcher


# WNOHANG | __WALL | __WNOTHREAD: only the dedicated tracer's known child.
WAIT_OPTIONS = 0x60000001


class InitialWaitError(ValueError):
    """Original session failed; retain cleanup identities and never retry."""


class InitialWaitOwner:
    """Claim an already launched coordinator and wait for its trusted stop.

    Only a successful handshake transfers control to PostHandshakeDispatcher.
    The caller must remain the sole waiter; no signal handler or other thread
    may consume statuses. This supplies no watchdog, source authentication,
    whole-tree cleanup, or permission to execute a capability probe.
    """

    def __init__(self, launch, *, waitpid=os.waitpid, sleep=time.sleep):
        if (type(launch) is not CoordinatorLaunch or launch.stopped is not None
                or launch.handshake is None or launch.admitted
                or getattr(launch, '_initial_wait_owner_claimed', False)):
            raise InitialWaitError('exclusive launched coordinator required')
        launch._owner_and_deadline()
        launch._initial_wait_owner_claimed = True
        self.launch, self.waitpid, self.sleep = launch, waitpid, sleep
        self.started = False
        self.stopped = None

    def _check(self):
        if self.launch.stopped is not None or self.launch.reader.stopped is not None:
            raise InitialWaitError('initial wait session stopped')
        self.launch._owner_and_deadline()

    def run(self):
        """Consume one actual initial status within the original deadline."""
        if self.started:
            raise InitialWaitError('initial wait already attempted')
        self.started = True
        try:
            while True:
                self._check()
                try:
                    result = self.waitpid(self.launch.cleanup_tid, WAIT_OPTIONS)
                except InterruptedError:
                    result = (0, 0)
                if type(result) is not tuple or len(result) != 2:
                    raise InitialWaitError('malformed initial wait result')
                tid, status = result
                if type(tid) is not int or type(status) is not int or tid != 0 or status != 0:
                    # Admission accounts an observed stop before checking a
                    # changed owner/deadline or touching a different task.
                    self.launch.accept_initial_wait(tid, status)
                    return PostHandshakeDispatcher(self.launch)
                self._check()
                remaining = self.launch.deadline - self.launch.clock()
                if remaining <= 0:
                    raise InitialWaitError('original deadline exhausted')
                self.sleep(min(0.01, remaining))
        except BaseException:
            self.stopped = 'initial bounded wait failed; cleanup required'
            # Keep the first session failure; the wait owner records its own
            # context separately, without erasing cancellation/admission cause.
            if self.launch.stopped is None:
                self.launch.stopped = self.stopped
            raise
