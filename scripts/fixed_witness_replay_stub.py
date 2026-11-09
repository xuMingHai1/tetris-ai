"""Trusted coordinator child stub; no fork, wait loop, CLI or capability probe.

Prepare in the dedicated single-thread tracer BEFORE its one reserved fork.
Only the child may call run. The parent must bind that fork's actual PID/birth
and use CoordinatorHandshake before resume. This is not a launch supervisor.
"""
import os
from pathlib import PurePosixPath

from fixed_witness_replay_ptrace import PtraceAccess


class CoordinatorStub:
    """TRACEME -> self-SIGSTOP -> explicit cwd/execve, with no shell or PATH.

    Inputs and the native library are prepared before fork. The caller must
    have a unique tracer thread, an already-debited coordinator reservation,
    fixed descriptors and the original deadline/cleanup owner. No arbitrary
    child callback is supplied. Exceptions and even a returned execve terminate
    via _exit(125), without traceback, inherited stream writes or exit hooks.
    This component does not authenticate the executable or install seccomp.
    """

    def __init__(self, command, cwd, environment, parent_pid, access, system):
        if (type(command) not in (tuple, list) or not command
                or any(type(value) is not str or not value or '\0' in value
                       for value in command)
                or not PurePosixPath(command[0]).is_absolute()
                or type(cwd) is not str or '\0' in cwd
                or not PurePosixPath(cwd).is_absolute()
                or type(environment) is not dict
                or any(type(key) is not str or not key or '=' in key or '\0' in key
                       or type(value) is not str or '\0' in value
                       for key, value in environment.items())
                or type(parent_pid) is not int or not 0 < parent_pid < 2**31):
            raise ValueError('explicit coordinator argv/cwd/environment/parent required')
        # Copy and encode before fork; later mutation of caller inputs cannot
        # change the fixed command. execve never inherits the tracer environment.
        self.command = tuple(os.fsencode(value) for value in command)
        self.cwd = os.fsencode(cwd)
        self.environment = {os.fsencode(key): os.fsencode(value)
                            for key, value in environment.items()}
        self.parent_pid = parent_pid
        self.access, self.system = access, system
        self.attempted = False

    @classmethod
    def for_host(cls, command, cwd, environment, parent_pid):
        """Prepare native transport in the parent; never fork or trace here."""
        return cls(command, cwd, environment, parent_pid, PtraceAccess.for_host(), os)

    def run(self):
        """Child-only, never returns in native use; execute once after admission.

        Returning from kill(SIGSTOP) is possible only after a resume. The trusted
        parent owns options-before-resume; this child cannot certify that fact.
        Parent identity is checked again before exec to reject orphaned startup.
        A PID check is not a birth certificate or an EXITKILL capability proof.
        """
        try:
            if self.attempted:
                raise ValueError('coordinator stub already attempted')
            self.attempted = True
            pid = self.system.getpid()
            if (type(pid) is not int or not 0 < pid < 2**31
                    or pid == self.parent_pid or self.system.getppid() != self.parent_pid):
                raise ValueError('coordinator child/parent mismatch')
            self.access.trace_me(pid)
            self.system.kill(pid, 19)  # Linux SIGSTOP, frozen native ABI.
            if self.system.getppid() != self.parent_pid:
                raise ValueError('coordinator parent changed before exec')
            self.system.chdir(self.cwd)
            self.system.execve(self.command[0], self.command, self.environment)
        except BaseException:
            # No exception payload or arbitrary log write enters evidence.
            pass
        # Also fail if an injected/invalid exec implementation unexpectedly returns.
        self.system._exit(125)
