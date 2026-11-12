"""Pure accounting for the accepted #174 task policy; NOT a ptrace supervisor.

One tracer thread must own this ledger and arbitrate every creation entry before
resuming it. No process is created, signalled, inspected or reaped here. Opaque
birth tickets, rather than reusable Linux TIDs, identify tasks and reservations.
The future syscall adapter must authenticate events and report actual reaps.
"""
from dataclasses import dataclass


TASK_ATTEMPTS = 512
LIVE_TASKS = 128
WAIT_STOPS = 2_000_000
CREATIONS = frozenset(('coordinator', 'fork', 'vfork', 'clone', 'clone3'))


class TaskAccountingError(ValueError):
    """A limit or unknown transition latched failure; no new work may start."""


@dataclass(frozen=True)
class TaskTotals:
    attempts: int
    live: int
    pending: int
    wait_stops: int
    clone3_enosys: int
    stopped: str | None


@dataclass
class _Task:
    tid: int
    group_birth: int
    alive: bool = True
    terminal: str | None = None


@dataclass(frozen=True)
class TaskIdentity:
    """Accounting snapshot, never a ptrace/kill or exec-migration receipt.

    group_birth identifies the group's original leader birth, not a reusable
    numeric TGID. tgid is its original numeric identity. terminal distinguishes
    observed reap from externally authenticated EXEC leader retirement.
    Reaping a leader neither changes membership nor releases other live slots.
    """

    birth: int
    tid: int
    group_birth: int
    tgid: int
    alive: bool
    terminal: str | None


@dataclass
class _Attempt:
    parent: int
    kind: str
    thread: bool
    child: int | None = None
    finished: bool = False


@dataclass(frozen=True)
class PendingCreation:
    """Read-only reservation facts, including an event-bound child birth.

    A missing child is unresolved, never proof that no kernel child exists.
    This snapshot neither settles a syscall nor authorizes native cleanup.
    """

    reservation: int
    parent: int
    kind: str
    thread: bool
    child: int | None


class TaskLedger:
    """Serial, nonrefundable accounting with fail-closed state transitions.

    Construction accounts for the dedicated tracer, which the trusted host must
    have reserved BEFORE launch. Smaller limits are for pure synthetic tests;
    they are not independent allowances for manual probes. A stopped ledger
    still accepts matching in-flight events/results and reaps for cleanup, but
    never a new reservation. Caller must kill/reap the tree on any exception.
    """

    def __init__(self, tracer_tid, *, attempts=TASK_ATTEMPTS, live=LIVE_TASKS,
                 wait_stops=WAIT_STOPS):
        for value, ceiling in ((attempts, TASK_ATTEMPTS), (live, LIVE_TASKS),
                               (wait_stops, WAIT_STOPS)):
            if type(value) is not int or not 1 <= value <= ceiling:
                raise ValueError('positive integer within accepted policy required')
        if type(tracer_tid) is not int or tracer_tid <= 0:
            raise ValueError('positive tracer TID required')
        self._attempt_limit, self._live_limit, self._event_limit = attempts, live, wait_stops
        self._tasks = {0: _Task(tracer_tid, 0)}
        self._leaders = {0: 0}
        self._attempts = {}
        self._pending = {}
        self._live = 1
        self._events = 0
        self._clone3 = 0
        self._stopped = None

    @property
    def totals(self):
        return TaskTotals(1 + len(self._attempts), self._live, len(self._pending),
                          self._events, self._clone3, self._stopped)

    def _fail(self, reason):
        if self._stopped is None:
            self._stopped = reason
        raise TaskAccountingError(reason)

    def _running(self):
        if self._stopped is not None:
            self._fail('ledger stopped')

    def _task(self, ticket):
        if type(ticket) is not int or ticket not in self._tasks:
            self._fail('unknown task birth ticket')
        return self._tasks[ticket]

    def _attempt(self, ticket):
        if type(ticket) is not int or ticket not in self._attempts:
            self._fail('unknown creation reservation')
        attempt = self._attempts[ticket]
        if attempt.finished or self._pending.get(attempt.parent) != ticket:
            self._fail('creation reservation already finished')
        return attempt

    def reserve(self, parent, kind, *, thread=False):
        """Debit before any creation is resumed, including clone3 denial.

        clone3 debits attempts without reserving live space: the adapter MUST
        suppress the syscall and return ENOSYS, then call finish(ticket, None).
        All other kinds reserve live space until failure or a confirmed reap.
        Only the initial coordinator may be created directly by the tracer.
        The creation policy supplies thread=True only for native CLONE_THREAD;
        all process creations, including clone(CLONE_VM), start a new group.
        Freeze this relation at entry, never infer it from a later numeric TID.
        """
        self._running()
        task = self._task(parent)
        if not task.alive or parent in self._pending:
            self._fail('dead parent or unresolved creation')
        if type(kind) is not str or kind not in CREATIONS:
            self._fail('unknown creation kind')
        if type(thread) is not bool or (thread and kind != 'clone'):
            self._fail('thread relation requires legacy clone')
        if (parent == 0) != (kind == 'coordinator') or (parent == 0 and self._attempts):
            self._fail('only one trusted coordinator fork allowed')
        if 1 + len(self._attempts) >= self._attempt_limit:
            self._fail('task attempt limit')
        # An observed creation attempt is never refunded, even at the live cap.
        ticket = 1 + len(self._attempts)
        self._attempts[ticket] = _Attempt(parent, kind, thread)
        if kind != 'clone3' and self._live >= self._live_limit:
            self._attempts[ticket].finished = True
            self._fail('live task limit')
        self._pending[parent] = ticket
        if kind == 'clone3':
            self._clone3 += 1
        else:
            self._live += 1
        return ticket

    def require_tracer(self, tid):
        """Check the caller's native leader against the original tracer binding.

        The launcher separately authenticates its single-thread OS inventory;
        this check cannot prove provenance and never creates a reservation.
        """
        self._running()
        if type(tid) is not int or tid != self._tasks[0].tid:
            self._fail('tracer identity mismatch')

    def created(self, reservation, tid):
        """Associate one authenticated fork event, without another debit.

        For the coordinator, the adapter supplies the reserved fork's returned
        TID before its stop handshake. Other TIDs must come from ptrace events.
        A child may run/reap before the parent's syscall result (notably vfork).
        """
        attempt = self._attempt(reservation)
        if attempt.kind == 'clone3' or attempt.child is not None:
            self._fail('unexpected or duplicate creation event')
        if type(tid) is not int or tid <= 0 or any(
                task.alive and task.tid == tid for task in self._tasks.values()):
            self._fail('invalid or already live child TID')
        attempt.child = reservation
        group = self._tasks[attempt.parent].group_birth if attempt.thread else reservation
        self._tasks[reservation] = _Task(tid, group)
        if not attempt.thread:
            self._leaders[group] = reservation
        return reservation

    def identity(self, ticket):
        """Snapshot a known birth, including stopped-ledger cleanup history.

        No live credit changes and no lookup by PID. The trusted adapter must
        still authenticate actual stops/reaps; this metadata grants no resume.
        """
        task = self._task(ticket)
        return TaskIdentity(ticket, task.tid, task.group_birth,
                            self._tasks[task.group_birth].tid, task.alive, task.terminal)

    def cleanup_identities(self):
        """Snapshot live tracee births even after failure; no signal permission.

        Includes births whose wire construction failed. Unbound reservations
        remain in totals.pending and must not be mistaken for a drained tree.
        """
        return tuple(self.identity(ticket) for ticket, task in self._tasks.items()
                     if ticket != 0 and task.alive)

    def cleanup_wait_stop(self):
        """Charge an observed cleanup stop without clearing the failure latch.

        Same original counter/ceiling as normal supervision. This grants no
        ptrace access or resume and never permits work beyond an exhausted cap.
        """
        if self._events < self._event_limit:
            self._events += 1
        if self._events >= self._event_limit:
            self._fail('wait-stop limit')

    def cleanup_pending(self):
        """Retain in-flight birth relations after failure without any refund.

        Event-bound children may already be reaped before the parent's result.
        Keep their birth tickets rather than reconstructing them from TIDs.
        """
        return tuple(PendingCreation(ticket, parent, attempt.kind, attempt.thread,
                                     attempt.child)
                     for parent, ticket in self._pending.items()
                     for attempt in (self._attempts[ticket],))

    def exec_identity(self, ticket, old_tid, new_tid):
        """Apply externally authenticated EXEC facts; never authorize a resume.

        Caller must bind native exec entry, EXEC stop and GETEVENTMSG(old TID)
        to this exact birth, and update dispatcher/wire/wait identities before
        any resume. This pure model does not establish that authentication.
        Other siblings must already have actual terminal reaps recorded. Only
        the replaced current leader may be retired without a separate reap:
        Linux de_thread releases it silently after exchanging TIDs. Unresolved
        group creations fail closed rather than inventing syscall outcomes.
        """
        self._running()
        return self.cleanup_exec_identity(ticket, old_tid, new_tid)

    def cleanup_exec_identity(self, ticket, old_tid, new_tid):
        """Apply original-owner authenticated EXEC during failed cleanup only.

        Same identity checks as normal EXEC, without clearing a stopped ledger.
        This bookkeeping is not authentication, a new allowance or resume proof.
        """
        task = self._task(ticket)
        if (ticket == 0 or not task.alive or type(old_tid) is not int
                or type(new_tid) is not int or old_tid != task.tid):
            self._fail('invalid exec birth or old TID')
        leader_birth = self._leaders[task.group_birth]
        leader = self._tasks[leader_birth]
        if new_tid != self._tasks[task.group_birth].tid:
            self._fail('exec target is not original group TGID')
        group = {birth for birth, member in self._tasks.items()
                 if member.group_birth == task.group_birth}
        if any(birth in self._pending for birth in group):
            self._fail('exec with unresolved group creation')
        replacing = old_tid != new_tid
        if replacing and (ticket == leader_birth or not leader.alive):
            self._fail('exec replacement requires live current leader')
        allowed = {ticket, leader_birth} if replacing else {ticket}
        if any(self._tasks[birth].alive for birth in group - allowed):
            self._fail('exec siblings require observed terminal reaps')
        if replacing:
            leader.alive = False
            leader.terminal = 'exec-replaced'
            self._live -= 1
            task.tid = new_tid
            self._leaders[task.group_birth] = ticket
        return self.identity(ticket)

    def finish(self, reservation, child_tid):
        """Resolve a creation result; None means authenticated failure/ENOSYS.

        A successful result requires its event, even if the child has reaped.
        Kernel failure returns a live reservation but never an attempt slot.
        The adapter must verify the actual errno, including clone3 ENOSYS.
        """
        attempt = self._attempt(reservation)
        if child_tid is None:
            if attempt.child is not None:
                self._fail('kernel failure after child event')
            if attempt.kind != 'clone3':
                self._live -= 1
        elif (type(child_tid) is not int or attempt.child is None
              or self._tasks[attempt.child].tid != child_tid):
            self._fail('success without matching child event')
        attempt.finished = True
        del self._pending[attempt.parent]

    def reaped(self, task_ticket):
        """Release live credit only after the adapter observed a terminal reap."""
        task = self._task(task_ticket)
        if task_ticket == 0 or not task.alive:
            self._fail('invalid or duplicate reap')
        if task_ticket in self._pending:
            # Terminal parent reap closes a syscall killed before its result.
            # An event-bound child owns its own slot, even after parent death.
            attempt = self._attempts[self._pending.pop(task_ticket)]
            if attempt.child is None and attempt.kind != 'clone3':
                self._live -= 1
            attempt.finished = True
        task.alive = False
        task.terminal = 'reaped'
        self._live -= 1

    def wait_stop(self):
        """Count every wait-stop; reaching the frozen ceiling latches failure."""
        if self._events < self._event_limit:
            self._events += 1
        if self._events >= self._event_limit:
            self._fail('wait-stop limit')
        self._running()

    def require_drained(self):
        """Check bookkeeping only; never a capability or COMPLETE certificate."""
        if self._pending or self._live != 1:
            self._fail('unreaped tasks or pending creations')
        self._running()
