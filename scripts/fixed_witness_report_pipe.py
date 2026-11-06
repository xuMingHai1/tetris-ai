"""Report-only saved-evidence JVM transport; no actions, history or network launcher.

The caller owns an exclusive private no-writer input directory (including hardlink
aliases), independently retained canonical declaration/hash and clean-build receipt.
These cooperative identity checks are not an OS sandbox or historical authentication.
"""
import copy
from dataclasses import dataclass
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import time

from fixed_witness_replay_admission import _classes, _object, command_for as verify_command
from fixed_witness_replay_identity import canonical, require
from fixed_witness_replay_observation import observe_process
from fixed_witness_replay_prepare import verify_prepared
from fixed_witness_replay_process import (
    JVM_SECONDS, PARENT_SECONDS, TOTAL_SECONDS, _cancellation, _seconds, _work, _stop_work, run_process,
)
from fixed_witness_replay_quota import OUTPUT_BYTES

SCHEMA = 'fixed-witness-report-jvm-v1'
SUCCESS = 'REPORT_WRITTEN_NO_REPLAY'
FLAGS = {'diagnostic_started', 'provenance_authenticated', 'eligible_for_runtime_validation'}
LIMITS = {'request.json': 16*1024, 'input.csv': 64*1024, 'journal.jsonl': OUTPUT_BYTES}
STATES = {'INCOMPLETE', 'INVALID', 'EVIDENCE_MISMATCH', 'COMPLETE'}
REPORT_KEYS = {'schema', 'inputHash', 'journalState', 'attempted', 'completed', 'structuralPrefixValid',
               'recordedRows', 'observations', 'terminals', 'provenanceAuthenticated', 'qCertified',
               'eligibleForRuntimeValidation', 'requestIdentity'}


def _hash(raw):
    return hashlib.sha256(raw).hexdigest()


def _json(raw):
    require(raw.endswith(b'\n'), 'incomplete JSON')
    value = json.loads(raw.decode('utf-8', errors='strict'), object_pairs_hook=_object,
                       parse_constant=lambda value: require(False, 'nonfinite JSON'))
    require(type(value) is dict, 'JSON object required')
    return value


def _same(left, right):
    # Python's True == 1 must never authenticate a declaration's integer fields.
    if type(left) is not type(right):
        return False
    if type(left) is dict:
        return set(left) == set(right) and all(_same(left[k], right[k]) for k in left)
    if type(left) is list:
        return len(left) == len(right) and all(_same(a, b) for a, b in zip(left, right))
    return left == right


def _identity(value):
    require(value.st_ino != 0, 'file identity unavailable')
    return value.st_dev, value.st_ino, value.st_mode, value.st_size, value.st_mtime_ns


def _directory(path):
    path = Path(path)
    require(path.is_absolute() and path == canonical(path), 'canonical absolute directory required')
    for ancestor in (path, *path.parents):
        require(stat.S_ISDIR(ancestor.lstat().st_mode), 'linked/non-directory ancestor')
    return _identity(path.lstat())


def _read(path, limit):
    """Bounded NOFOLLOW read bracketed by descriptor/path identity checks."""
    before = path.lstat()
    require(stat.S_ISREG(before.st_mode) and before.st_size <= limit, 'file type/size limit')
    require(not path.is_symlink(), 'linked file')
    flags = os.O_RDONLY | getattr(os, 'O_BINARY', 0) | getattr(os, 'O_NOFOLLOW', 0)
    with os.fdopen(os.open(path, flags), 'rb') as stream:
        require(_identity(os.fstat(stream.fileno())) == _identity(before), 'opened file drift')
        raw = stream.read(limit + 1)
        require(_identity(os.fstat(stream.fileno())) == _identity(before), 'read file drift')
    require(_identity(path.lstat()) == _identity(before) and len(raw) == before.st_size
            and len(raw) <= limit, 'file metadata/size drift')
    return raw, _identity(before)


def _snapshot(directory):
    before = _directory(directory)
    require({p.name for p in directory.iterdir()} == set(LIMITS), 'exact three input files required')
    files = tuple((name, *_read(directory / name, limit)) for name, limit in LIMITS.items())
    require(before == _directory(directory), 'input directory drift')
    return before, files


@dataclass(frozen=True)
class RequestReceipt:
    """Parent-held immutable bytes; never reconstruct authority from child output."""
    directory: Path
    declaration: bytes
    request_sha256: str
    snapshot: tuple

    def recheck(self):
        require(_snapshot(self.directory) == self.snapshot, 'request receipt drift')


def retain_request(directory, declaration, expected_sha256):
    """Caller supplies the independently retained canonical declaration and digest.

    Java Descriptor remains the sole canonical validator/serializer. Python compares
    parsed declarations without regenerating canonical bytes or replaying geometry.
    """
    require(type(declaration) is bytes and len(declaration) <= 16*1024
            and isinstance(expected_sha256, str) and re.fullmatch('[0-9a-f]{64}', expected_sha256)
            and _hash(declaration) == expected_sha256, 'independent request bytes/hash required')
    directory = Path(directory)
    snapshot = _snapshot(directory)
    raw = {name: data for name, data, _ in snapshot[1]}
    require(raw['request.json'] == declaration, 'request declaration differs from parent')
    value = _json(declaration)
    require(set(value) == {'schema', 'inputSha256', 'journalSha256', 'paths', 'expected'}
            and value['schema'] == 'fixed-witness-report-request-v1', 'request declaration fields/schema')
    require(value['inputSha256'] == _hash(raw['input.csv'])
            and value['journalSha256'] == _hash(raw['journal.jsonl']), 'request evidence digest mismatch')
    paths, expected = value['paths'], value['expected']
    require(type(paths) is list and 1 <= len(paths) <= 2 and type(expected) is dict, 'request path types')
    ids = []
    for path in paths:
        require(type(path) is dict and set(path) == {'id', 'placements'}
                and type(path['id']) is str and type(path['placements']) is int
                and path['placements'] > 0, 'request path declaration')
        ids.append(path['id'])
    require(len(set(ids)) == len(ids) and set(expected) == set(ids), 'request expected IDs')
    fields = {'required', 'forbidden', 'rawHoles', 'requiredHoles', 'forbiddenHoles', 'supportHoles', 'outsideHoles'}
    for value in expected.values():
        require(type(value) is dict and set(value) == fields
                and all(type(n) is int and 0 <= n <= 2147483647 for n in value.values()), 'request expected fields')
    return RequestReceipt(directory, declaration, expected_sha256, snapshot)


def command_for(manifest, root, java_home, output, nonce, request, handshake_bytes, report_bytes):
    require(type(handshake_bytes) is int and 0 < handshake_bytes <= 2*1024*1024
            and type(report_bytes) is int and 0 < report_bytes <= OUTPUT_BYTES, 'report allowance limits')
    command = verify_command(manifest, root, java_home, output, nonce, handshake_bytes)
    paths = [canonical(root), Path(output), request.directory,
             canonical(manifest['environment']['HOME']), canonical(manifest['environment']['TMPDIR'])]
    for i, first in enumerate(paths):
        for second in paths[i+1:]:
            require(not first.is_relative_to(second) and not second.is_relative_to(first),
                    'disjoint checkout/output/snapshot/HOME/TMP required')
    command[7:] = ['xyz.xuminghai.tetris.ai.FixedWitnessReportApplication', '--report-only',
                   str(paths[0]), str(output), nonce, str(handshake_bytes), str(report_bytes),
                   str(request.directory), request.request_sha256]
    return command


class ReportHandshake:
    """Read-only callbacks. Only run_process has stdin and may release ACK."""
    def __init__(self, root, java_home, receipt, output, nonce, manifest, request, handshake_bytes, report_bytes):
        self.root, self.java_home = canonical(root), canonical(java_home)
        self.receipt, self.manifest = copy.deepcopy(receipt), copy.deepcopy(manifest)
        self.output, self.nonce, self.request = Path(output), nonce, request
        self.handshake_bytes, self.report_bytes = handshake_bytes, report_bytes

    def ready(self):
        path = self.output / 'ready.json'
        if not path.exists() and not path.is_symlink():
            return False
        _directory(self.output)
        # CREATE_NEW is visible before the child finishes writing. Drift during this
        # readiness probe means wait, not reject; authentication later reads strictly.
        limit = min(1024*1024, self.handshake_bytes)
        before = path.lstat()
        require(stat.S_ISREG(before.st_mode) and before.st_size <= limit, 'READY type/size limit')
        with os.fdopen(os.open(path, os.O_RDONLY | getattr(os, 'O_BINARY', 0)
                               | getattr(os, 'O_NOFOLLOW', 0)), 'rb') as stream:
            opened = os.fstat(stream.fileno())
            require(_identity(opened)[:3] == _identity(before)[:3], 'READY opened identity drift')
            raw = stream.read(limit + 1)
            after = os.fstat(stream.fileno())
        require(len(raw) <= limit and after.st_size <= limit, 'READY size limit')
        require(_identity(after)[:3] == _identity(path.lstat())[:3], 'READY replaced')
        return _identity(before) == _identity(after) and len(raw) == after.st_size and raw.endswith(b'\n')

    def _prepared(self):
        require(verify_prepared(self.root, self.java_home, self.receipt) == self.manifest, 'build receipt drift')
        self.request.recheck()

    def _limits(self, value):
        require(type(value.get('handshake_bytes_limit')) is int
                and value['handshake_bytes_limit'] == self.handshake_bytes
                and type(value.get('report_bytes_limit')) is int
                and value['report_bytes_limit'] == self.report_bytes, 'report allowance mismatch')
        require(all(value.get(name) is False for name in FLAGS), 'unexpected transport authority')
        require(value.get('schema') == SCHEMA and value.get('request_sha256') == self.request.request_sha256,
                'report schema/request mismatch')

    def authenticate(self, pid):
        self._prepared()
        require(type(pid) is int and pid > 0, 'positive pid required')
        command = command_for(self.manifest, self.root, self.java_home, self.output, self.nonce,
                              self.request, self.handshake_bytes, self.report_bytes)
        raw, _ = _read(self.output / 'ready.json', min(1024*1024, self.handshake_bytes))
        ready = _json(raw)
        require(set(ready) == FLAGS | {'schema', 'state', 'nonce', 'observation', 'handshake_bytes_limit',
                                      'report_bytes_limit', 'request_sha256'}, 'unexpected report READY fields')
        self._limits(ready)
        require(ready['state'] == 'READY' and ready['nonce'] == self.nonce, 'report READY state/nonce')
        observed = ready['observation']
        require(type(observed) is dict and set(observed) == {'classes', 'java_runtime', 'classpath', 'pid'},
                'unexpected report observation')
        require(type(observed['pid']) is int and observed['pid'] == pid
                and observed['classpath'] == command[6]
                and observed['classes'] == _classes(self.manifest, self.root, report=True), 'loaded report identity')
        version, runtime = self.manifest['toolchain']['java_version'], observed['java_runtime']
        require(type(runtime) is str and (runtime == version or runtime.startswith(version + '+')), 'runtime mismatch')
        digest = self.manifest['toolchain']['jdk_files']['bin/java']
        process = observe_process(pid, command, self.manifest['environment'], self.root, digest)
        self._prepared()
        require(_read(self.output / 'ready.json', min(1024*1024, self.handshake_bytes))[0] == raw, 'READY drift')
        require(observe_process(pid, command, self.manifest['environment'], self.root, digest) == process,
                'report process drift')
        return {'ack': ('ACK ' + self.nonce + ' ' + _hash(raw) + '\n').encode('ascii'),
                'evidence': {'ready_sha256': _hash(raw), 'process': process,
                             'request_sha256': self.request.request_sha256,
                             'manifest_sha256': self.receipt['sha256'], 'loaded_classes_verified': True,
                             'diagnostic_started': False, 'diagnostic_authorized': False,
                             'network_isolation_verified': False}}

    def finalize(self, admitted):
        self._prepared()
        before = _directory(self.output)
        require({p.name for p in self.output.iterdir()} == {'ready.json', 'report.json', 'reported.json'},
                'unexpected report artifacts')
        ready, _ = _read(self.output / 'ready.json', min(1024*1024, self.handshake_bytes))
        terminal_raw, _ = _read(self.output / 'reported.json', min(1024*1024, self.handshake_bytes))
        raw, _ = _read(self.output / 'report.json', self.report_bytes)
        require(len(ready) + len(terminal_raw) <= self.handshake_bytes, 'combined handshake allowance exceeded')
        require(_hash(ready) == admitted['ready_sha256'], 'post-exit READY drift')
        terminal, report = _json(terminal_raw), _json(raw)
        require(set(terminal) == FLAGS | {'schema', 'state', 'ready_sha256', 'request_sha256', 'report_sha256',
                                         'report_bytes', 'journal_state', 'handshake_bytes_limit', 'report_bytes_limit'},
                'unexpected reported fields')
        self._limits(terminal)
        require(terminal['state'] == SUCCESS and terminal['ready_sha256'] == admitted['ready_sha256']
                and terminal['report_sha256'] == _hash(raw)
                and type(terminal['report_bytes']) is int and terminal['report_bytes'] == len(raw), 'reported byte binding')
        require(set(report) == REPORT_KEYS and report['schema'] == 'fixed-witness-report-v1', 'report schema/fields')
        require(all(report.get(name) is False for name in ('qCertified', 'provenanceAuthenticated',
                                                         'eligibleForRuntimeValidation')), 'report authority')
        declaration = _json(self.request.declaration)
        require(_same(report['requestIdentity'], dict(declaration, requestSha256=self.request.request_sha256))
                and report['inputHash'] == declaration['inputSha256'], 'report request identity')
        require(type(report['journalState']) is str and report['journalState'] in STATES
                and terminal['journal_state'] == report['journalState'], 'report journal state mismatch')
        require(type(report['attempted']) is int and report['attempted'] >= 0
                and type(report['completed']) is int and 0 <= report['completed'] <= report['attempted']
                and type(report['structuralPrefixValid']) is bool
                and all(type(report[name]) is list for name in ('recordedRows', 'observations', 'terminals')),
                'report field types')
        require(_directory(self.output) == before, 'output directory drift')
        self._prepared()
        return {'state': SUCCESS, 'journal_state': report['journalState'], 'report_sha256': _hash(raw),
                'request_sha256': self.request.request_sha256, 'ready_sha256': _hash(ready),
                'manifest_sha256': self.receipt['sha256'], 'diagnostic_started': False,
                'diagnostic_authorized': False, 'network_isolation_verified': False,
                'handshake_bytes_limit': self.handshake_bytes, 'report_bytes_limit': self.report_bytes,
                'artifact_bytes': len(ready) + len(terminal_raw) + len(raw)}


def run_report_only(root, java_home, receipt, output, logs, budget, *, nonce, request,
                    handshake_bytes, report_bytes, total_deadline):
    """One report JVM within the caller's existing absolute deadline and root quota.

    Existing inputs are not new output; any copies/receipt/log/failure persistence
    must be charged by their caller to this same root. No directory is exempted.
    """
    started = time.monotonic()
    require(not isinstance(total_deadline, bool), 'absolute total deadline required')
    _seconds(total_deadline - started)
    require(total_deadline <= started + TOTAL_SECONDS, 'total exceeds frozen ceiling')
    deadline = min(started + PARENT_SECONDS, total_deadline) - 3
    require(deadline > started, 'cleanup reserve exhausted')
    receipt = copy.deepcopy(receipt)
    with _cancellation() as cancellation:
        pending = _work(verify_prepared, root, java_home, receipt, deadline=deadline)
        try:
            while pending[0].empty():
                require(time.monotonic() < deadline and cancellation['signal'] is None,
                        'prelaunch authentication expired/cancelled')
                time.sleep(.01)
            ok, manifest = pending[0].get()
        finally:
            _stop_work(pending)
        if not ok:
            raise manifest
        command = command_for(manifest, root, java_home, output, nonce, request, handshake_bytes, report_bytes)
        request.recheck()
        require(not Path(output).exists() and not Path(output).is_symlink(), 'new report output required')
        require(time.monotonic() < deadline and cancellation['signal'] is None, 'prelaunch expired/cancelled')
        # Atomic predebit of BOTH allowances. Never refund on failure or unused output.
        budget.quota.reserve(handshake_bytes + report_bytes)
        return run_process(command, root, logs, 'report-only', budget,
                           seconds=min(JVM_SECONDS, deadline - time.monotonic()), total_deadline=deadline,
                           environment=manifest['environment'], cancellation=cancellation,
                           success_state=SUCCESS, handshake=ReportHandshake(root, java_home, receipt,
                               output, nonce, manifest, request, handshake_bytes, report_bytes))
