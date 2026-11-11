"""Pure fixed-probe matching/consumption, NOT exec authentication or permission.

Inputs are observations supplied by a future trusted owner. Nothing here reads
files/proc, debits stops, resumes, settles tasks or certifies SIGSYS. The normal
dispatcher remains native-only. A matching result is not an admission receipt.
"""
from dataclasses import dataclass
import hashlib
import json
from pathlib import PurePosixPath
import re

from fixed_witness_replay_ptrace import LINUX_SIGSYS, SYS_SECCOMP, SignalInfo, SyscallInfo
from fixed_witness_replay_wait import WaitStatus


_ENTRIES = {'x32': (0xc000003e, 0x40000029), 'compat': (0x40000003, 102)}


class AbiModelError(ValueError):
    """Mismatch permanently stops this model; no retry or replacement slot."""


def _uint(value, bits, minimum=0):
    return type(value) is int and minimum <= value < (1 << bits)


def _path(value):
    # Lexical validation only. The eventual host must prove canonical identity
    # and byte hashes; accepting a string cannot establish either fact.
    return (type(value) is str and '\x00' not in value
            and value.startswith('/') and not value.startswith('//')
            and '..' not in value.split('/') and str(PurePosixPath(value)) == value)


@dataclass(frozen=True)
class ProbeRecord:
    """Frozen expected bytes/argv, also used for untrusted observed values."""

    mode: str
    source: str
    source_sha256: str
    interpreter: str
    interpreter_sha256: str
    argv: tuple

    def valid(self):
        return (type(self.mode) is str and self.mode in _ENTRIES
                and _path(self.source) and _path(self.interpreter)
                and all(type(value) is str and re.fullmatch('[0-9a-f]{64}', value)
                        for value in (self.source_sha256, self.interpreter_sha256))
                and type(self.argv) is tuple
                and all(type(arg) is str for arg in self.argv)
                and self.argv == (self.interpreter, '-I', '-B', self.source, self.mode))


@dataclass(frozen=True)
class ExecIdentity:
    """Birth/group/TID/exec facts; supplying them does not authenticate a task."""

    birth: int
    group_birth: int
    tid: int
    generation: int

    def valid(self):
        return (_uint(self.birth, 64, 1) and _uint(self.group_birth, 64, 1)
                and _uint(self.tid, 31, 1) and _uint(self.generation, 64, 1))


class AbiProbeModel:
    """Two frozen slots, serial one-shot matching and fail-closed revocation.

    This finite subset matches supplied entry, delivery and terminal facts.
    Real wait/reap evidence, identity authentication and outer owner/deadline/
    shared ledger gates remain unimplemented, so no execution path imports it.
    """

    def __init__(self, records):
        if (type(records) is not tuple or len(records) != 2
                or any(type(r) is not ProbeRecord or not r.valid() for r in records)
                or {r.mode for r in records} != set(_ENTRIES)):
            raise AbiModelError('exact two frozen probe records required')
        ordered = tuple(sorted(records, key=lambda r: r.mode))
        if (ordered[0].source, ordered[0].source_sha256,
                ordered[0].interpreter, ordered[0].interpreter_sha256) != (
                ordered[1].source, ordered[1].source_sha256,
                ordered[1].interpreter, ordered[1].interpreter_sha256):
            raise AbiModelError('both modes require the same frozen source/interpreter')
        self._records = {r.mode: r for r in ordered}
        self._states = {r.mode: 'UNBOUND' for r in ordered}
        self._identities = {}
        self._stopped = None
        payload = [(r.mode, r.source, r.source_sha256, r.interpreter,
                    r.interpreter_sha256, r.argv, _ENTRIES[r.mode]) for r in ordered]
        self._manifest_sha256 = hashlib.sha256(json.dumps(
            payload, ensure_ascii=True, separators=(',', ':')).encode('ascii')).hexdigest()

    @property
    def manifest_sha256(self):
        return self._manifest_sha256

    @property
    def stopped(self):
        return self._stopped

    def state(self, mode):
        return self._states[mode]

    def _fail(self, message):
        if self._stopped is None:
            self._stopped = message
        raise AbiModelError(self._stopped)

    def fail(self, message):
        """Latch a caller's failed gate/resume; never restore a consumed slot."""
        self._fail(message if type(message) is str and message else 'outer gate failed')

    def _running(self, mode):
        if self._stopped is not None:
            self._fail(self._stopped)
        if type(mode) is not str or mode not in self._records:
            self._fail('unknown probe mode')

    def bind(self, mode, identity, observed):
        self._running(mode)
        if (self._states[mode] != 'UNBOUND' or type(identity) is not ExecIdentity
                or not identity.valid() or type(observed) is not ProbeRecord
                or not observed.valid() or observed != self._records[mode]):
            self._fail('probe binding mismatch or reused slot')
        # A task/exec cannot serve both modes, nor can a reused numeric TID
        # become a second live model binding without a real lifecycle adapter.
        if any(identity.birth == old.birth or identity.tid == old.tid
               for old in self._identities.values()):
            self._fail('probe identity already assigned')
        self._identities[mode] = identity
        self._states[mode] = 'BOUND'

    def consume_entry(self, mode, identity, manifest_sha256, info):
        """Consume before any future fallible resume; returns no authority."""
        self._running(mode)
        if (self._states[mode] != 'BOUND' or type(identity) is not ExecIdentity
                or not identity.valid() or identity != self._identities[mode]
                or type(manifest_sha256) is not str
                or manifest_sha256 != self._manifest_sha256):
            self._fail('unbound, stale or mismatched probe entry')
        if (type(info) is not SyscallInfo or not _uint(info.operation, 8)
                or info.operation != 1 or not _uint(info.arch, 32)
                or not _uint(info.number, 64)
                or (info.arch, info.number) != _ENTRIES[mode]
                or not _uint(info.instruction_pointer, 64)
                or not _uint(info.stack_pointer, 64)
                or type(info.arguments) is not tuple or len(info.arguments) != 6
                or not all(_uint(value, 64) for value in info.arguments)
                or info.result is not None or info.is_error is not None):
            self._fail('exact decoded fixed-probe ENTRY required')
        self._states[mode] = 'AWAITING_SIGSYS'

    def observe_sigsys(self, mode, identity, manifest_sha256, info):
        """Match a supplied delivery fact without resume or terminal authority.

        No delivery is required by this model. Matching observations leave the
        consumed slot awaiting a separately authenticated terminal wait/reap;
        repeated facts cannot release credit or turn into a success receipt.
        """
        self._running(mode)
        if (self._states[mode] != 'AWAITING_SIGSYS'
                or type(identity) is not ExecIdentity or not identity.valid()
                or identity != self._identities[mode]
                or type(manifest_sha256) is not str
                or manifest_sha256 != self._manifest_sha256):
            self._fail('unconsumed, stale or mismatched SIGSYS observation')
        if (type(info) is not SignalInfo
                or not _uint(info.signal, 8) or info.signal != LINUX_SIGSYS
                or type(info.code) is not int or info.code != SYS_SECCOMP
                or type(info.error) is not int or not -(1 << 31) <= info.error < (1 << 31)
                or not _uint(info.call_address, 64)
                or not _uint(info.syscall, 31) or not _uint(info.arch, 32)
                or (info.arch, info.syscall) != _ENTRIES[mode]
                or info.sender_pid is not None or info.sender_uid is not None):
            self._fail('exact decoded fixed-probe SYS_SECCOMP delivery required')

    def observe_terminal(self, mode, identity, manifest_sha256, status):
        """Consume a supplied SIGSYS terminal fact once, without certifying reap.

        TERMINAL_FACT_MATCHED is deliberately not KERNEL_TERMINAL_OBSERVED.
        The future sole wait owner must authenticate the original birth and
        settle the real reap before calling; this pure model cannot verify
        that provenance, release ledger credit or certify filter/no-injection.
        A delivery stop is not required and the core-dump bit is not proof.
        """
        self._running(mode)
        if (self._states[mode] != 'AWAITING_SIGSYS'
                or type(identity) is not ExecIdentity or not identity.valid()
                or identity != self._identities[mode]
                or type(manifest_sha256) is not str
                or manifest_sha256 != self._manifest_sha256):
            self._fail('unconsumed, stale or mismatched terminal observation')
        if (type(status) is not WaitStatus or type(status.kind) is not str
                or status.kind != 'signaled' or not _uint(status.signal, 8)
                or status.signal != LINUX_SIGSYS or status.exit_code is not None
                or status.event is not None or type(status.core_dumped) is not bool):
            self._fail('exact decoded fixed-probe SIGSYS terminal required')
        self._states[mode] = 'TERMINAL_FACT_MATCHED'

    def revoke_exec(self, mode, identity):
        """A bound task's next EXEC uses up its slot; never rebind a new exec."""
        self._running(mode)
        if (self._states[mode] != 'BOUND' or type(identity) is not ExecIdentity
                or not identity.valid() or identity != self._identities[mode]):
            self._fail('unexpected EXEC or mismatched probe identity')
        self._states[mode] = 'REVOKED'
