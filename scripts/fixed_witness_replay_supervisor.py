"""Compose the existing tracer lifecycle; no CLI, source freeze or acceptance run.

The trusted host supplies the original deadline and reserved tracer slot. The
for_host factory enforces coordinator pipe retention and tracer lifecycle
evidence from their original writer partitions; a caller-provided launch still
owns its stdio/evidence policy. This entry does
not authenticate the host, transfer cross-process quotas or certify capability.
"""
import os
import time

from fixed_witness_replay_exec_observation import OwnedExecObservations
from fixed_witness_replay_initial_wait import InitialWaitOwner
from fixed_witness_replay_launch import CoordinatorLaunch
from fixed_witness_replay_settled_cleanup import SettledProcessCleanup
from fixed_witness_replay_stdio import CoordinatorOutput, CoordinatorOutputError
from fixed_witness_replay_tracer_evidence import TracerEvidence
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

    def __init__(self, launch, *, exec_observations=None, output=None, evidence=None, waitpid=os.waitpid,
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
        if evidence is not None:
            if type(evidence) is not TracerEvidence or evidence.owner is not None:
                raise SupervisorError('original unclaimed tracer evidence required')
        if output is not None:
            if type(output) is not CoordinatorOutput:
                raise SupervisorError('original coordinator output transport required')
            output.claim(launch)
        launch._supervisor_claimed = True
        if evidence is not None:
            evidence.claim(self)
        self.launch, self.exec_observations = launch, exec_observations
        self.output = output
        self.evidence, self.evidence_error = evidence, None
        self.waitpid, self.sleep = waitpid, sleep
        self.open_pidfd, self.send_signal, self.close = open_pidfd, send_signal, close
        self.started = False
        self.initial = self.dispatcher = self.waiter = self.cleanup = None
        self.failure = self.cleanup_error = None
        self.drained = False

    @classmethod
    def for_host(cls, ledger, command, cwd, environment, stdin, directory, deadline,
                 writers, *, exec_observations=None):
        """Prepare prefix, native launch and enforced coordinator output pipes.

        This does not run the launch or satisfy the original acceptance guard.
        The dedicated tracer receives exclusive reservations from its host;
        artifact/JVM sub-reservations must use writers.coordinator as well.
        The immutable tracer INCOMPLETE prefix precedes native preparation;
        construction failure leaves it intact and closes any prepared pipes.
        """
        evidence = TracerEvidence(writers, directory)
        output = CoordinatorOutput(writers, directory)
        try:
            launch = CoordinatorLaunch.for_host(
                ledger, command, cwd, environment, (stdin, *output.writers), deadline)
            return cls(launch, exec_observations=exec_observations, output=output, evidence=evidence)
        except BaseException:
            output.close()
            raise

    def _wait(self, tid, options):
        # Poll BEFORE consuming a wait tuple. An output failure must not lose
        # an EXEC/terminal receipt or release a live credit by inference.
        if self.output is not None:
            self.output.poll(cleanup=self.failure is not None)
            self.launch._owner_and_deadline()
        return self.waitpid(tid, options)

    def _finish_output(self, *, cleanup=False):
        if self.output is None:
            return
        while not self.output.drained:
            self.launch._owner_and_deadline()
            self.output.poll(cleanup=cleanup)
            if not self.output.drained:
                remaining = self.launch.deadline - self.launch.clock()
                if remaining <= 0:
                    raise CoordinatorOutputError('original output drain deadline exhausted')
                self.sleep(min(0.01, remaining))

    def run(self):
        """Finish bounded evidence after the original lifecycle and sink close.

        Evidence failure never replaces an earlier execution/cleanup error.
        If lifecycle otherwise drained, evidence failure still prevents return
        as success; the immutable prelaunch prefix remains INCOMPLETE.
        """
        if self.started:
            raise SupervisorError('supervisor already attempted')
        self.started = True
        try:
            return self._run()
        finally:
            if self.evidence is not None:
                try:
                    self.evidence.finish(self)
                except BaseException as error:
                    self.evidence_error = error
                    self.drained = False
                    if self.failure is None:
                        self.failure = SupervisorError('tracer evidence failed')
                        if self.launch.stopped is None:
                            self.launch.stopped = str(self.failure)
                        if self.exec_observations is not None:
                            self.exec_observations.fail(self.launch.stopped)
                        raise self.failure from error

    def _run(self):
        """Attempt once; retain every intermediate owner for failure evidence.

        A fork/parent-init exception may already own a child, so cleanup is
        selected from ledger duties, not whether launch() returned successfully.
        No child exists after a failed pre-fork check or settled fork error;
        those paths must not open a pidfd or consume a wait.
        """
        try:
            try:
                if self.output is not None:
                    self.output.require_launchable()
                self.launch.launch()
            finally:
                if self.output is not None:
                    self.output.release_parent_writers()
            self.initial = InitialWaitOwner(
                self.launch, waitpid=self._wait, sleep=self.sleep,
                exec_observations=self.exec_observations)
            self.dispatcher = self.initial.run()
            self.waiter = BoundedWaitOwner(
                self.dispatcher, waitpid=self._wait, sleep=self.sleep)
            totals = self.waiter.run()
            self._finish_output()
            if self.output is not None and self.output.failure is not None:
                raise CoordinatorOutputError('coordinator output failed') from self.output.failure
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
                        waitpid=self._wait, sleep=self.sleep)
                    self.cleanup.run()
                self._finish_output(cleanup=True)
            except BaseException as cleanup_error:
                self.cleanup_error = cleanup_error
            raise
        finally:
            if self.output is not None:
                self.output.close()
                if self.output.close_errors and self.failure is None:
                    self.drained = False
                    self.failure = CoordinatorOutputError('coordinator output close failed')
                    if self.launch.stopped is None:
                        self.launch.stopped = str(self.failure)
                    if self.exec_observations is not None:
                        self.exec_observations.fail(self.launch.stopped)
                    raise self.failure from self.output.close_errors[0]
