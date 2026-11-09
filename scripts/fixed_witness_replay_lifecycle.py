"""Pure creation lifecycle joining the accepted policy and task ledger.

Inputs MUST come from the future authenticated ptrace dispatcher. This module
does not read stops, resume tasks, inject ENOSYS or certify kernel behaviour.
"""
from fixed_witness_replay_creation import CreationPolicy, CreationPolicyError
from fixed_witness_replay_tasks import TaskAccountingError


class CreationLifecycleError(ValueError):
    """Stop and clean up the whole tree; never resume new work."""


class CreationLifecycle:
    """Single-owner matching of entry obligations, child events and exits.

    One pending creation belongs to each immutable parent birth ticket. A child
    event does not refund/debit another slot; a successful syscall result must
    match that event. clone3 must have no event and return Linux ENOSYS. The
    wire adapter must complete its one-shot suppression token before reporting
    that exit here. In-flight events/exits and terminal reaps remain available
    after failure for accounting during cleanup; no new entry is accepted.
    """

    def __init__(self, ledger):
        self.ledger = ledger
        self.policy = CreationPolicy(ledger)
        self._pending = {}
        self.stopped = None

    def _fail(self, reason):
        if self.stopped is None:
            self.stopped = reason
        raise CreationLifecycleError(reason)

    def _obligation(self, parent):
        if type(parent) is not int or parent not in self._pending:
            self._fail('no pending creation for parent birth ticket')
        return self._pending[parent]

    def entry(self, parent, arch, syscall, arguments):
        """Return a reserved obligation while the native creation is stopped."""
        if self.stopped is not None:
            self._fail('creation lifecycle stopped')
        try:
            obligation = self.policy.arbitrate(parent, arch, syscall, arguments)
        except (CreationPolicyError, TaskAccountingError) as error:
            self._fail(str(error))
        self._pending[parent] = obligation
        return obligation

    def child_event(self, parent, event, child_tid):
        """Match an authenticated FORK/VFORK/CLONE event before its result."""
        obligation = self._obligation(parent)
        if type(event) is not str or event != obligation.expected_event:
            self._fail('unexpected creation event kind')
        try:
            return self.ledger.created(obligation.reservation, child_tid)
        except TaskAccountingError as error:
            self._fail(str(error))

    def result(self, parent, value, is_error):
        """Consume a kernel syscall-info exit, not an inferred entry/exit pair.

        Linux errors are negative errno in [-4095, -1]. A native creation
        success must return a positive TID. Never coerce a bool or trust only
        the sign when syscall-info says otherwise.
        """
        obligation = self._obligation(parent)
        if (type(value) is not int or type(is_error) is not bool
                or (is_error and not -4095 <= value <= -1)
                or (not is_error and not 0 < value < 2**31)):
            self._fail('invalid creation syscall result')
        if obligation.suppress_enosys and (not is_error or value != -38):
            self._fail('clone3 requires confirmed Linux ENOSYS')
        try:
            self.ledger.finish(obligation.reservation, None if is_error else value)
        except TaskAccountingError as error:
            self._fail(str(error))
        del self._pending[parent]

    def reaped(self, parent):
        """Report an actual terminal reap, including a killed pending parent."""
        try:
            self.ledger.reaped(parent)
        except TaskAccountingError as error:
            self._fail(str(error))
        self._pending.pop(parent, None)

    def require_drained(self):
        """Bookkeeping consistency only; not a COMPLETE/capability receipt."""
        if self._pending:
            self._fail('pending creation lifecycles')
        try:
            self.ledger.require_drained()
        except TaskAccountingError as error:
            self._fail(str(error))
        if self.stopped is not None:
            self._fail('creation lifecycle stopped')
