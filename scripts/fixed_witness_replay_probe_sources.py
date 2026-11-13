"""Join prepared repository Python sources to the two frozen ABI records.

Readonly cooperative file observations, not a Java host or filter certificate.
No Git, helper process, library load, filter installation or native probe runs.
The trusted host retains the receipt independently and excludes concurrent
writers; these checks cannot prevent malicious change-and-restore attacks.
"""
import ast
import hashlib
import json
import stat
import re

from fixed_witness_replay_abi_model import ProbeRecord, _path
from fixed_witness_replay_abi_observation import (
    AbiObservationError, ProcFiles, CHUNK, SOURCE_LIMIT, INTERPRETER_LIMIT,
)
from fixed_witness_replay_exec_observation import OwnedExecObservations
from fixed_witness_replay_network import encoded
from fixed_witness_replay_probe_launch import ProbeLaunch
from fixed_witness_replay_quota import OUTPUT_BYTES


PROBE = 'scripts/test_fixed_witness_replay_network.py'
LAUNCHER = 'scripts/fixed_witness_replay_network.py'
SOURCE_ROOTS = (PROBE, LAUNCHER, 'scripts/fixed_witness_replay_supervisor.py',
                'scripts/fixed_witness_replay_writer_handoff.py',
                'scripts/fixed_witness_replay_coordinator_artifacts.py')


class PreparedProbeSources:
    """One source selection for one supervisor; never refreeze after failure.

    Expected hashes come from the independently retained prepare receipt and
    its original manifest, not from the probe or current file observations.
    Repository Python sources are checked before selection and retained for
    the original supervisor's pre-fork and post-reap continuity checks.
    Births must already be selected by the host's frozen task schedule. This
    adapter does not allocate tasks or establish that still-missing schedule.
    """

    def __init__(self, root, receipt, births, networks, check, *, files=None):
        self.files = ProcFiles() if files is None else files
        self.check = check
        self.owner = None
        self.failure = None
        self._expected = ()
        self.observations = None
        try:
            if (not callable(check) or not _path(root) or type(receipt) is not dict
                    or set(receipt) != {'manifest', 'sha256'}
                    or not _path(receipt['manifest']) or not ProbeLaunch._hash(receipt['sha256'])
                    or type(births) is not tuple or len(births) != 2
                    or any(type(n) is not int or not 0 < n < 2**64 for n in births)
                    or births[0] == births[1]
                    or type(networks) is not tuple or len(networks) != 2):
                raise AbiObservationError('original receipt and x32/compat birth schedule required')
            if self._call(self.files.canonical, root) != root:
                raise AbiObservationError('canonical prepared source root required')
            raw = self._read(receipt['manifest'], OUTPUT_BYTES, content=True)
            if hashlib.sha256(raw).hexdigest() != receipt['sha256']:
                raise AbiObservationError('original prepare manifest digest mismatch')
            manifest = json.loads(raw.decode('utf-8'), object_pairs_hook=self._object)
            if (manifest['state'] != 'PREPARED' or manifest['diagnostic_started'] is not False
                    or type(manifest['source']['source_sha']) is not str
                    or re.fullmatch('[0-9a-f]{40}', manifest['source']['source_sha']) is None
                    or manifest['classpath']['classpath'][:2] != [
                        root + '/target/classes', root + '/target/test-classes']):
                raise AbiObservationError('original prepared checkout manifest required')
            source = root + '/' + PROBE
            launcher = root + '/' + LAUNCHER
            inventory = manifest['source']['source_files']
            expected = [(receipt['manifest'], receipt['sha256'], OUTPUT_BYTES)]
            expected.extend(self._python_sources(root, inventory))
            records, launches = [], []
            for birth, mode, network in zip(births, ('x32', 'compat'), networks):
                # Canonical JSON is copied before retaining any caller data.
                item = json.loads(encoded(network))
                record = ProbeRecord(mode, source, inventory[PROBE], item['python'],
                                     item['python_sha256'],
                                     (item['python'], '-I', '-B', source, mode))
                launch = ProbeLaunch(record, encoded(item))
                if item['launcher'] != launcher or item['launcher_sha256'] != inventory[LAUNCHER]:
                    raise AbiObservationError('network launcher is not the prepared source')
                expected.extend(((record.interpreter, record.interpreter_sha256, INTERPRETER_LIMIT),
                                 (item['library']['path'], item['library']['sha256'], INTERPRETER_LIMIT)))
                records.append((birth, record))
                launches.append((birth, launch))
            if records[0][1].interpreter != records[1][1].interpreter:
                raise AbiObservationError('one frozen interpreter required')
            self._expected = tuple(dict.fromkeys(expected))
            self.revalidate()
            self.observations = OwnedExecObservations(tuple(records), files=files, bind_abi=True,
                                                      network_launches=tuple(launches))
        except BaseException as error:
            self.failure = error
            raise

    @staticmethod
    def _object(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise AbiObservationError('duplicate prepared manifest key')
            result[key] = value
        return result

    def _python_sources(self, root, inventory):
        """Freeze prepared repository Python bytes, including deferred imports.

        Parse authenticated bytes without importing/executing them. This checks
        the repository's static import inventory only: Python stdlib, loaded
        module origins, dynamic imports and toolchain libraries still require
        the trusted host's independent certificate.
        """
        if type(inventory) is not dict:
            raise AbiObservationError('prepared Python source inventory required')
        pattern = r'scripts/(?:test_)?fixed_witness[a-z0-9_]*\.py'
        selected = {name: value for name, value in inventory.items()
                    if type(name) is str and re.fullmatch(pattern, name)}
        if not set(SOURCE_ROOTS) <= selected.keys():
            raise AbiObservationError('prepared supervisor source roots required')
        expected = []
        for name, digest in sorted(selected.items()):
            if not ProbeLaunch._hash(digest):
                raise AbiObservationError('prepared Python source digest required')
            path = root + '/' + name
            raw = self._read(path, SOURCE_LIMIT, content=True)
            if hashlib.sha256(raw).hexdigest() != digest:
                raise AbiObservationError('original frozen Python source bytes changed')
            self._gate()
            tree = ast.parse(raw, filename=name)
            self._gate()
            for node in ast.walk(tree):
                self._gate()
                if isinstance(node, ast.Import):
                    modules = tuple(alias.name for alias in node.names)
                elif isinstance(node, ast.ImportFrom):
                    if node.level:
                        raise AbiObservationError('relative prepared Python import unsupported')
                    modules = (node.module or '',)
                else:
                    continue
                for module in modules:
                    if module.startswith(('fixed_witness', 'test_fixed_witness')):
                        if 'scripts/' + module + '.py' not in selected:
                            raise AbiObservationError('prepared Python import missing from inventory')
            expected.append((path, digest, SOURCE_LIMIT))
        return expected

    def _call(self, operation, *args):
        self._gate()
        value = operation(*args)
        self._gate()
        return value

    def _gate(self):
        # The prepared host callback alone cannot authorize another launch.
        # After claim, every chunk is also bound to the actual sole tracer and
        # unchanged deadline that will perform this source selection's waits.
        if self.owner is not None:
            self.owner.launch._owner_and_deadline()
        self.check()
        if self.owner is not None:
            self.owner.launch._owner_and_deadline()

    def _read(self, path, limit, *, content=False):
        if self._call(self.files.canonical, path) != path:
            raise AbiObservationError('frozen file path changed')
        self._gate()
        fd = self.files.open(path)
        try:
            self._gate()
            before = self._call(self.files.fstat, fd)
            if not stat.S_ISREG(before.st_mode) or not 0 <= before.st_size <= limit:
                raise AbiObservationError('nonregular or oversized frozen file')
            fingerprint = self._fingerprint(before)
            digest, data, size = hashlib.sha256(), bytearray(), 0
            while True:
                block = self._call(self.files.read, fd, min(CHUNK, limit - size + 1))
                if type(block) is not bytes:
                    raise AbiObservationError('invalid frozen file transport')
                size += len(block)
                if size > limit:
                    raise AbiObservationError('frozen file read ceiling exhausted')
                if not block:
                    break
                digest.update(block)
                if content:
                    data.extend(block)
            after = self._call(self.files.fstat, fd)
            if fingerprint != self._fingerprint(after) or size != before.st_size:
                raise AbiObservationError('frozen file changed during observation')
            return bytes(data) if content else digest.hexdigest()
        finally:
            self.files.close(fd)

    @staticmethod
    def _fingerprint(info):
        return (info.st_dev, info.st_ino, info.st_mode, info.st_size,
                info.st_mtime_ns, info.st_ctime_ns)

    def claim(self, owner):
        if self.owner is not None or self.failure is not None:
            raise AbiObservationError('original exclusive source owner required')
        self.owner = owner

    def revalidate(self):
        """Recheck the original bytes; failure permanently revokes ABI selection."""
        try:
            if self.failure is not None:
                raise AbiObservationError('prepared source selection stopped') from self.failure
            for path, digest, limit in self._expected:
                if not ProbeLaunch._hash(digest) or self._read(path, limit) != digest:
                    raise AbiObservationError('original frozen source bytes changed')
        except BaseException as error:
            if self.failure is None:
                self.failure = error
            if self.observations is not None:
                self.observations.fail('prepared source continuity failed')
            raise
