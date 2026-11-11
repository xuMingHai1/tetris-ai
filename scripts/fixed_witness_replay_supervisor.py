"""Compose the existing tracer lifecycle; no CLI, source freeze or acceptance run.

The trusted host must supply a prepared CoordinatorLaunch with charged stdio,
original deadline and reserved tracer slot. This entry does not authenticate
that host, enforce cross-process writer quotas or certify kernel capabilities.
"""
import os
import time

from fixed_witness_replay_exec_observation import OwnedExecObservations
from fixed_witness_replay_initial_wait import InitialWaitOwner
from fixed_witness_replay_launch import CoordinatorLaunch
from fixed_witness_replay_settled_cleanup import SettledProcessCleanup
from fixed_witness_replay_wait_owner import BoundedWaitOwner


class SupervisorError(ValueError):
    """One-shot lifecycle unavailable; do not create a replacement session."""


class TracerSupervisor:
    """One original launch -> handshake -> targeted waits, with failure cleanup.

    Only the dedicated tracer calls run. Prepared native transports are never
    loaded or invoked on construction/import. A successful return is drained
    accounting, NOT manual-suite success or permission to run historical input.
    Failure attempts the existing supported cleanup once within the unchanged
    deadline; incomplete cleanup is retained separately and never hides the
    original exception. Unsupported thread/event inventories remain incomplete.
    """

    def __init__(self, launch, *, exec_observations=None, waitpid=os.waitpid,
                 sleep=time.sleep, open_pidfd=None, send_signal=None, close=os.close):
        if (type(launch) is not CoordinatorLaunch or launch.attempted
                or launch.stopped is not None
                or getattr(launch, '_supervisor_claimed', False)):
            raise SupervisorError('exclusive unattempted prepared launch required')
        if (exec_observations is not None
                and (type(exec_observations) is not OwnedExecObservations
                     or exec_observations._dispatcher is not None
                     or exec_observations.stopped is not None)):
            raise SupervisorError('fresh original exec observations required')
        if not all(callable(fn) for fn in (waitpid, sleep, close)):
            raise SupervisorError('fixed wait and descriptor transports required')
        if any(fn is not None and not callable(fn) for fn in (open_pidfd, send_signal)):
            raise SupervisorError('fixed pidfd transports required')
        launch._supervisor_claimed = True
        self.launch, self.exec_observations = launch, exec_observations
        self.waitpid, self.sleep = waitpid, sleep
        self.open_pidfd, self.send_signal, self.close = open_pidfd, send_signal, close
        self.started = False
        self.initial = self.dispatcher = self.waiter = self.cleanup = None
        self.failure = self.cleanup_error = None
        self.drained = False

    def run(self):
        """Attempt once; retain every intermediate owner for failure evidence.

        A fork/parent-init exception may already own a child, so cleanup is
        selected from ledger duties, not whether launch() returned successfully.
        No child exists after a failed pre-fork check or settled fork error;
        those paths must not open a pidfd or consume a wait.
        """
        if self.started:
            raise SupervisorError('supervisor already attempted')
        self.started = True
        try:
            self.launch.launch()
            self.initial = InitialWaitOwner(
                self.launch, waitpid=self.waitpid, sleep=self.sleep,
                exec_observations=self.exec_observations)
            self.dispatcher = self.initial.run()
            self.waiter = BoundedWaitOwner(
                self.dispatcher, waitpid=self.waitpid, sleep=self.sleep)
            totals = self.waiter.run()
            self.drained = True
            return totals
        except BaseException as error:
            self.failure = error
            if self.launch.stopped is None:
                self.launch.stopped = 'supervisor failed; cleanup required'
            if self.exec_observations is not None:
                self.exec_observations.fail(self.launch.stopped)
            # Keep the original exception even if acquisition, cleanup or close
            # fails. The host must inspect cleanup_error and the original ledger.
            try:
                totals = self.launch.ledger.totals
                if totals.live != 1 or totals.pending:
                    self.cleanup = SettledProcessCleanup(
                        self.launch, open_pidfd=self.open_pidfd,
                        send_signal=self.send_signal, close=self.close,
                        waitpid=self.waitpid, sleep=self.sleep)
                    self.cleanup.run()
            except BaseException as cleanup_error:
                self.cleanup_error = cleanup_error
            raise
