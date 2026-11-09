"""Pure native creation-entry policy for #174; no ptrace or resume operation.

The future tracer must authenticate syscall-info and the task birth ticket,
call arbitrate while the task is stopped, then enact the returned obligation.
This component cannot certify ABI identity, child events or actual ENOSYS.
"""
from dataclasses import dataclass

from fixed_witness_replay_tasks import TaskLedger


AUDIT_ARCH_X86_64 = 0xC000003E
SYS_CLONE, SYS_FORK, SYS_VFORK, SYS_CLONE3 = 56, 57, 58, 435
SIGCHLD = 17  # Linux ABI values, independent of the Python host OS.
CLONE_VM = 0x100
CLONE_FS = 0x200
CLONE_FILES = 0x400
CLONE_SIGHAND = 0x800
CLONE_VFORK = 0x4000
CLONE_THREAD = 0x10000
CLONE_SYSVSEM = 0x40000
CLONE_SETTLS = 0x80000
CLONE_PARENT_SETTID = 0x100000
CLONE_CHILD_CLEARTID = 0x200000
CLONE_UNTRACED = 0x800000
CLONE_CHILD_SETTID = 0x1000000
_ALLOWED_CLONE = (CLONE_VM | CLONE_FS | CLONE_FILES | CLONE_SIGHAND
                  | CLONE_VFORK | CLONE_THREAD | CLONE_SYSVSEM | CLONE_SETTLS
                  | CLONE_PARENT_SETTID | CLONE_CHILD_CLEARTID | CLONE_CHILD_SETTID)
_KINDS = {SYS_CLONE: 'clone', SYS_FORK: 'fork', SYS_VFORK: 'vfork', SYS_CLONE3: 'clone3'}


class CreationPolicyError(ValueError):
    """Stop the complete tree; never resume the rejected entry."""


@dataclass(frozen=True)
class CreationObligation:
    reservation: int
    kind: str
    expected_event: str | None
    suppress_enosys: bool


class CreationPolicy:
    """Single-owner, fail-closed arbitration backed by the session ledger.

    A recognized native creation attempt is charged even when its clone flags
    are rejected. On rejection any pending live slot stays reserved until the
    adapter reaps its parent; the caller must begin cleanup immediately. No
    resume API is exposed. A policy error latches this object permanently.
    """

    def __init__(self, ledger: TaskLedger):
        self.ledger = ledger
        self.stopped = None

    def _fail(self, reason):
        if self.stopped is None:
            self.stopped = reason
        raise CreationPolicyError(reason)

    def arbitrate(self, parent, arch, syscall, arguments):
        """Reserve before returning permission/clone3-suppression obligations.

        Only authenticated native creation entries belong here. The separate
        dispatcher must reject other ABIs except the frozen noncreation probes;
        this module deliberately grants no compat/x32 exemption. clone3 pointer
        and size are never dereferenced: every such call must be suppressed.
        """
        if self.stopped is not None:
            self._fail('creation policy stopped')
        if type(arch) is not int or arch != AUDIT_ARCH_X86_64:
            self._fail('native x86-64 ABI required')
        if type(syscall) is not int or syscall not in _KINDS:
            self._fail('unknown native creation syscall')
        if (type(arguments) is not tuple or len(arguments) != 6
                or any(type(value) is not int or not 0 <= value < 2**64
                       for value in arguments)):
            self._fail('six unsigned native register arguments required')
        kind = _KINDS[syscall]
        try:
            reservation = self.ledger.reserve(
                parent, kind, thread=kind == 'clone' and bool(arguments[0] & CLONE_THREAD))
        except ValueError:
            self._fail('task reservation failed')
        if kind == 'clone3':
            return CreationObligation(reservation, kind, None, True)
        event = kind
        if kind == 'clone':
            flags = arguments[0]
            if flags & CLONE_UNTRACED:
                self._fail('CLONE_UNTRACED forbidden')
            if flags & ~(_ALLOWED_CLONE | 0xFF):
                self._fail('unknown or relationship-changing clone flags')
            signal = flags & 0xFF
            thread = bool(flags & CLONE_THREAD)
            if flags & CLONE_SIGHAND and not flags & CLONE_VM:
                self._fail('CLONE_SIGHAND requires CLONE_VM')
            if thread and (not flags & CLONE_SIGHAND or signal != 0
                           or flags & CLONE_VFORK):
                self._fail('unsupported thread clone combination')
            if not thread and signal != SIGCHLD:
                self._fail('process clone requires SIGCHLD')
            # ptrace reports VFORK before testing the exit signal. Otherwise
            # legacy clone(SIGCHLD) generates FORK, including CLONE_VM spawn.
            event = 'vfork' if flags & CLONE_VFORK else ('clone' if thread else 'fork')
        return CreationObligation(reservation, kind, event, False)
