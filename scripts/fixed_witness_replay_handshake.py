"""One trusted coordinator's initial stop; no launch, wait loop or probe.

The single-owner tracer must already have forked its sole coordinator using a
ledger reservation, bound the returned PID/birth in the creation dispatcher,
and kept the child in the trusted TRACEME/self-SIGSTOP pre-exec stub. This
component does not establish that trusted provenance or inspect /proc.
"""
from fixed_witness_replay_dispatch import CreationDispatchError
from fixed_witness_replay_ptrace import PtraceBoundaryError
from fixed_witness_replay_stops import StopReadError


class CoordinatorHandshakeError(ValueError):
    """Do not retry or resume; the caller must clean up the whole tree."""


class CoordinatorHandshake:
    """Consume exactly the initial wait result and install options before resume.

    StopReader charges the shared ledger before reading siginfo. Only the
    trusted child's self-generated SIGSTOP (SI_USER or SI_TKILL) is suppressed;
    no general signal-reinjection policy is supplied. The dispatcher's one-shot
    coordinator admission validates the birth binding before any SETOPTIONS or
    resume. A successful options call is not a proof of EXITKILL behaviour.
    The launcher supplies check to revalidate owner/deadline after siginfo and
    options; standalone model callers retain their own environment checks.
    """

    def __init__(self, reader, dispatcher, access, ticket, tid, uid, *, check=None):
        if (reader.ledger is not dispatcher.lifecycle.ledger
                or type(ticket) is not int or ticket <= 0
                or type(tid) is not int or not 0 < tid < 2**31
                or type(uid) is not int or not 0 <= uid < 2**32):
            raise CoordinatorHandshakeError('invalid coordinator handshake binding')
        self.reader, self.dispatcher, self.access = reader, dispatcher, access
        self.ticket, self.tid, self.uid = ticket, tid, uid
        self.check = check
        self.attempted = False
        self.completed = False
        self.stopped = None

    def _fail(self, reason):
        if self.stopped is None:
            self.stopped = reason
        raise CoordinatorHandshakeError(reason)

    def accept(self, waited_tid, raw_status):
        """Pass the actual initial waitpid tuple once; never synthesize a stop.

        Caller retains the original deadline and cleanup registry. All later
        waits belong to the future authenticated loop, not another handshake.
        No exception authorizes retry, even if resume's outcome is uncertain.
        """
        if self.attempted:
            self._fail('coordinator handshake already attempted')
        self.attempted = True
        try:
            # StopReader validates/charges the raw wait before siginfo reads.
            # An unexpected waited TID must not trigger ptrace on another task.
            if type(waited_tid) is not int or waited_tid != self.tid:
                # Preserve accounting without letting StopReader touch a task.
                self.reader.read(waited_tid, raw_status, _NoAccess())
                self._fail('unexpected coordinator waited TID')
            facts = self.reader.read(waited_tid, raw_status, self.access)
            info = facts.signal_info
            if (facts.status.kind != 'signal-stop' or facts.status.signal != 19
                    or info is None or info.signal != 19 or info.error != 0
                    or info.code not in (0, -6) or info.sender_pid != self.tid
                    or info.sender_uid != self.uid):
                self._fail('expected trusted coordinator self-SIGSTOP')
            self.dispatcher.coordinator_signal_stop(self.ticket, self.tid)
            if self.check is not None:
                self.check()
            self.access.set_options(self.tid)
            if self.check is not None:
                self.check()
            self.access.resume_syscall(self.tid, 0)
            self.completed = True
        except (StopReadError, CreationDispatchError, PtraceBoundaryError, OSError) as error:
            self._fail(str(error))


class _NoAccess:
    """Reject unexpected wait identity after StopReader's debit, before ptrace."""

    def __getattr__(self, name):
        raise PtraceBoundaryError('unexpected coordinator waited TID')
