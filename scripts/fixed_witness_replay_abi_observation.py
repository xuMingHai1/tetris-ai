"""Bounded exec/proc observations for #219; no ABI dispatch or probe execution.

The caller must already own an authenticated, migrated EXEC stop and frozen
files on a cooperative trusted host. This reader cannot establish that origin,
generate an exec epoch or prevent hostile file/memory mutation. Its result is
an observation, never a binding/resume/terminal receipt.
"""
from dataclasses import dataclass
import hashlib
import os
from pathlib import Path
import stat
import sys

from fixed_witness_replay_abi_model import ExecIdentity, ProbeRecord
from fixed_witness_replay_observation import _stat
from fixed_witness_replay_probe_launch import ProbeLaunch


PROC_LIMIT = 64 * 1024
SOURCE_LIMIT = 1024 * 1024
INTERPRETER_LIMIT = 64 * 1024 * 1024
CHUNK = 64 * 1024


class AbiObservationError(ValueError):
    """Do not retry or bind; preserve the outer session's failure/cleanup."""


@dataclass(frozen=True)
class ExecObservation:
    """Immutable observed values only; no independent authentication authority."""

    identity: ExecIdentity
    record: ProbeRecord
    start_ticks: int


@dataclass(frozen=True)
class _LauncherImage:
    source: str
    source_sha256: str
    interpreter: str
    interpreter_sha256: str
    argv: tuple


class ProcFiles:
    """Native readonly I/O, instantiated explicitly; no helper process/thread."""

    getpid = staticmethod(os.getpid)
    listdir = staticmethod(os.listdir)
    readlink = staticmethod(os.readlink)
    close = staticmethod(os.close)
    read = staticmethod(os.read)
    fstat = staticmethod(os.fstat)

    @staticmethod
    def canonical(path):
        return str(Path(path).resolve(strict=True))

    @staticmethod
    def open(path, *, proc=False):
        # Only proc/exe deliberately follows a magic link. Frozen source and
        # interpreter final components may not become symlinks/FIFOs/devices.
        flags = os.O_RDONLY | os.O_CLOEXEC | os.O_NONBLOCK
        if not proc:
            flags |= os.O_NOFOLLOW
        return os.open(path, flags)


class AbiExecObserver:
    """One attempt under a caller-supplied sole-owner/epoch/deadline gate.

    check() MUST revalidate the original shared gates and the authenticated
    stopped EXEC generation, returning the current ExecIdentity. That adapter
    is not supplied here. Proc start time corroborates stability, never replaces
    the ledger's nonreusable birth/exec epoch. Checks surround every read/chunk;
    a blocking host filesystem operation is not forcibly preempted by this class.
    No evidence is written and no shared stop/task budget is created or reset.
    """

    def __init__(self, owner, check, *, files=None):
        if type(owner) is not int or not 0 < owner < 2**31 or not callable(check):
            raise AbiObservationError('original tracer and exec gate required')
        if files is None and sys.platform != 'linux':
            raise AbiObservationError('Linux proc observation required')
        self.owner, self.check = owner, check
        self.files = ProcFiles() if files is None else files
        self.attempted = False
        self.stopped = None

    def _gate(self, identity):
        if (self.files.getpid() != self.owner
                or self.files.listdir('/proc/self/task') != [str(self.owner)]):
            raise AbiObservationError('original single-thread tracer required')
        current = self.check()
        if (type(current) is not ExecIdentity or not current.valid()
                or current != identity):
            raise AbiObservationError('stopped exec identity changed')

    def _call(self, identity, operation, *args, **kwargs):
        self._gate(identity)
        value = operation(*args, **kwargs)
        self._gate(identity)
        return value

    def _file(self, identity, path, limit, *, digest=False, proc=False):
        self._gate(identity)
        fd = self.files.open(path, proc=proc)
        # Retain descriptor ownership before a fallible post-open gate; finally
        # closes exactly once even on expiry. A failed close is not retried.
        try:
            self._gate(identity)
            before = self._call(identity, self.files.fstat, fd)
            if not stat.S_ISREG(before.st_mode) or not 0 <= before.st_size <= limit:
                raise AbiObservationError('nonregular or oversized observation input')
            fingerprint = self._fingerprint(before)
            result = hashlib.sha256() if digest else bytearray()
            size = 0
            while True:
                data = self._call(identity, self.files.read, fd, min(CHUNK, limit - size + 1))
                if type(data) is not bytes:
                    raise AbiObservationError('invalid file transport bytes')
                size += len(data)
                if size > limit:
                    raise AbiObservationError('observation read ceiling exhausted')
                if not data:
                    break
                if digest:
                    result.update(data)
                else:
                    result.extend(data)
            after = self._call(identity, self.files.fstat, fd)
            if fingerprint != self._fingerprint(after) or (digest and size != before.st_size):
                raise AbiObservationError('observation file changed while reading')
            return (result.hexdigest() if digest else bytes(result)), fingerprint
        finally:
            self.files.close(fd)

    @staticmethod
    def _fingerprint(info):
        return (info.st_dev, info.st_ino, info.st_mode, info.st_size,
                info.st_mtime_ns, info.st_ctime_ns)

    def _snapshot(self, record, identity, network=None, launcher=False):
        root = '/proc/' + str(identity.tid)
        before = _stat(self._file(identity, root + '/stat', PROC_LIMIT, proc=True)[0], identity.tid)
        status = self._file(identity, root + '/status', PROC_LIMIT, proc=True)[0]
        fields = {}
        for line in status.splitlines():
            key, separator, value = line.partition(b':')
            if key in (b'Pid', b'Tgid', b'TracerPid', b'State'):
                if not separator or key in fields:
                    raise AbiObservationError('ambiguous proc status')
                fields[key] = value.strip()
        if (fields.keys() != {b'Pid', b'Tgid', b'TracerPid', b'State'}
                or fields[b'Pid'] != str(identity.tid).encode('ascii')
                or fields[b'Tgid'] != fields[b'Pid']
                or fields[b'TracerPid'] != str(self.owner).encode('ascii')
                or not fields[b'State'].startswith(b't (')):
            raise AbiObservationError('expected migrated EXEC tracing stop')
        security = None
        if network is not None:
            security = {}
            for line in status.splitlines():
                key, separator, value = line.partition(b':')
                if key in (b'NoNewPrivs', b'Seccomp', b'Seccomp_filters'):
                    if not separator or key in security or not value.strip().isdigit():
                        raise AbiObservationError('ambiguous network proc status')
                    security[key] = int(value.strip())
            count = network['inherited_filters'] + (0 if launcher else 1)
            if (set(security) != {b'NoNewPrivs', b'Seccomp', b'Seccomp_filters'}
                    or security[b'Seccomp_filters'] != count
                    or security[b'NoNewPrivs'] not in (0, 1)
                    or security[b'Seccomp'] != (2 if count else 0)
                    or (not launcher and security[b'NoNewPrivs'] != 1)):
                raise AbiObservationError('network installation status mismatch')
        raw = self._file(identity, root + '/cmdline', PROC_LIMIT, proc=True)[0]
        expected = b'\0'.join(arg.encode('utf-8', errors='strict') for arg in record.argv) + b'\0'
        if raw != expected:
            raise AbiObservationError('exact frozen argv mismatch')
        for path in (record.source, record.interpreter):
            if self._call(identity, self.files.canonical, path) != path:
                raise AbiObservationError('frozen path is no longer canonical')
        if self._call(identity, self.files.readlink, root + '/exe') != record.interpreter:
            raise AbiObservationError('proc executable path mismatch')
        source, source_file = self._file(identity, record.source, SOURCE_LIMIT, digest=True)
        interpreter, executable_file = self._file(
            identity, record.interpreter, INTERPRETER_LIMIT, digest=True)
        actual, proc_file = self._file(identity, root + '/exe', INTERPRETER_LIMIT,
                                      digest=True, proc=True)
        if (source != record.source_sha256 or interpreter != record.interpreter_sha256
                or actual != interpreter or executable_file != proc_file):
            raise AbiObservationError('frozen source or executable bytes/identity mismatch')
        library_file = None
        if network is not None:
            library = network['library']
            path = library['path']
            if self._call(identity, self.files.canonical, path) != path:
                raise AbiObservationError('frozen library path changed')
            value, library_file = self._file(identity, path, INTERPRETER_LIMIT, digest=True)
            if value != library['sha256']:
                raise AbiObservationError('frozen library bytes changed')
        after = _stat(self._file(identity, root + '/stat', PROC_LIMIT, proc=True)[0], identity.tid)
        if before != after or before['start_ticks'] <= 0:
            raise AbiObservationError('proc lifetime changed during observation')
        return before, source_file, executable_file, security, library_file

    def observe(self, record, identity, *, network_launch=None, launcher=False):
        """Compare two bounded real-shaped observations, never bind the model.

        Expected bytes/argv come from the independently frozen manifest. There
        is no refresh/retry path. The caller must latch any failure in the outer
        session as well; this component latches its own one attempt only.
        """
        if self.attempted:
            raise AbiObservationError('exec observation already attempted')
        self.attempted = True
        try:
            if (type(record) is not ProbeRecord or not record.valid()
                    or type(identity) is not ExecIdentity or not identity.valid()):
                raise AbiObservationError('frozen record and exec identity required')
            network = None
            image = record
            if network_launch is not None:
                if type(network_launch) is not ProbeLaunch or network_launch.probe != record:
                    raise AbiObservationError('original frozen network/probe relation required')
                network = network_launch.network()
                if launcher:
                    image = _LauncherImage(network['launcher'], network['launcher_sha256'],
                                           record.interpreter, record.interpreter_sha256,
                                           network_launch.argv())
            elif launcher:
                raise AbiObservationError('launcher requires frozen network relation')
            first = self._snapshot(image, identity, network, launcher)
            if first != self._snapshot(image, identity, network, launcher):
                raise AbiObservationError('exec evidence changed between observations')
            self._gate(identity)
            return ExecObservation(identity, record, first[0]['start_ticks'])
        except BaseException:
            # Keep raw argv and content out of failure messages/evidence.
            self.stopped = 'exec observation failed; no binding or retry'
            raise
