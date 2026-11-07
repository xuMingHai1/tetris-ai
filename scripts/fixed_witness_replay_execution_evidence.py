"""Saved fixed-action execution evidence, without launching or authenticating a JVM.

The future handshake must independently authenticate the process/build and retain
READY bytes before ACK. This component checks those same bytes after exit, the
parent-owned request, and saved journal/report structure; it never replays rules.
Private input directories must exclude all writers, including hardlink aliases.
"""
from dataclasses import dataclass
from pathlib import Path
import re

from fixed_witness_replay_identity import require
from fixed_witness_replay_quota import ByteQuota, OUTPUT_BYTES
from fixed_witness_report_pipe import (
    REPORT_KEYS, _directory, _hash, _json, _read, _same,
)

SCHEMA = 'fixed-witness-replay-execution-v1'
SUCCESS = 'REPLAY_REPORTED'
INPUT_LIMITS = {'request.json': 16*1024, 'input.csv': 64*1024}
LIMIT_KEYS = ('handshake_bytes_limit', 'journal_bytes_limit',
              'declaration_bytes_limit', 'report_bytes_limit')
FLAGS = {'diagnostic_authorized', 'provenance_authenticated',
         'eligible_for_runtime_validation', 'network_isolation_verified'}
EXPECTED_KEYS = {'required', 'forbidden', 'rawHoles', 'requiredHoles',
                 'forbiddenHoles', 'supportHoles', 'outsideHoles'}


def _hex(value):
    return type(value) is str and re.fullmatch('[0-9a-f]{64}', value) is not None


def _snapshot(directory):
    before = _directory(directory)
    require({p.name for p in directory.iterdir()} == set(INPUT_LIMITS), 'exact two input files required')
    files = tuple((name, *_read(directory / name, limit)) for name, limit in INPUT_LIMITS.items())
    require(before == _directory(directory), 'execution input directory drift')
    return before, files


@dataclass(frozen=True)
class ExecutionRequestReceipt:
    """Immutable parent bytes/identities; child outputs cannot replace authority."""
    directory: Path
    declaration: bytes
    request_sha256: str
    snapshot: tuple

    def recheck(self):
        require(_snapshot(self.directory) == self.snapshot, 'execution request receipt drift')


def retain_execution_request(directory, declaration, expected_sha256):
    """Compare caller-retained Java canonical bytes, never serialize them in Python.

    Java Request remains the input vocabulary/canonical validator. This preflight
    checks declaration types/conservation and byte identities without game logic.
    Copies or persisted receipts must be charged separately to the same root.
    """
    require(type(declaration) is bytes and 0 < len(declaration) <= 16*1024
            and _hex(expected_sha256) and _hash(declaration) == expected_sha256,
            'independent execution request bytes/hash required')
    directory = Path(directory)
    snapshot = _snapshot(directory)
    raw = {name: data for name, data, _ in snapshot[1]}
    require(raw['request.json'] == declaration, 'execution request differs from parent')
    value = _json(declaration)
    require(set(value) == {'schema', 'inputSha256', 'paths', 'expected'}
            and value['schema'] == 'fixed-witness-replay-request-v1', 'execution request schema/fields')
    require(_hex(value['inputSha256']) and 0 < len(raw['input.csv']) <= 64*1024
            and value['inputSha256'] == _hash(raw['input.csv']), 'execution input digest')
    paths, expected = value['paths'], value['expected']
    require(type(paths) is list and 1 <= len(paths) <= 2 and type(expected) is dict,
            'execution request paths/expected types')
    ids = []
    for path in paths:
        require(type(path) is dict and set(path) == {'id', 'placements'}
                and type(path['id']) is str and type(path['placements']) is int
                and 1 <= path['placements'] <= 22, 'execution path fields')
        ids.append(path['id'])
    require(sum(p['placements'] for p in paths) <= 22 and len(set(ids)) == len(ids)
            and set(expected) == set(ids), 'execution path counts/expected IDs')
    for fields in expected.values():
        require(type(fields) is dict and set(fields) == EXPECTED_KEYS
                and all(type(n) is int and 0 <= n <= 200 for n in fields.values()),
                'execution expected fields')
        require(fields['required'] <= 32 and fields['forbidden'] <= 16
                and fields['rawHoles'] == sum(fields[k] for k in
                    ('requiredHoles', 'forbiddenHoles', 'supportHoles', 'outsideHoles')),
                'execution expected conservation')
    return ExecutionRequestReceipt(directory, declaration, expected_sha256, snapshot)


def _limits(handshake_bytes, journal_bytes, declaration_bytes, report_bytes):
    values = (handshake_bytes, journal_bytes, declaration_bytes, report_bytes)
    require(all(type(n) is int and 0 < n <= maximum for n, maximum in
                zip(values, (2*1024*1024, OUTPUT_BYTES, 16*1024, OUTPUT_BYTES))),
            'execution allowance limits')
    require(sum(values) <= OUTPUT_BYTES, 'execution allowances exceed root ceiling')
    return dict(zip(LIMIT_KEYS, values))


def reserve_execution(quota, *, handshake_bytes, journal_bytes, declaration_bytes, report_bytes):
    """Atomically and permanently debit H+J+D+R before any producer may start.

    All four child allowances descend from the one charged reservation. Failure
    or unused space never refunds the caller's root; this is cooperative writer
    accounting, not monitoring HOME/TMP/target or an execution permission.
    """
    require(isinstance(quota, ByteQuota), 'shared parent ByteQuota required')
    limits = _limits(handshake_bytes, journal_bytes, declaration_bytes, report_bytes)
    reserved = quota.reserve(sum(limits.values()))
    return tuple(reserved.reserve(n) for n in limits.values())


def _transport(value, limits, request_hash):
    require(value.get('schema') == SCHEMA and value.get('request_sha256') == request_hash,
            'execution schema/request identity')
    require(all(value.get(name) is False for name in FLAGS), 'execution authority flags')
    require(all(type(value.get(name)) is int and value[name] == n for name, n in limits.items()),
            'execution allowance identity')


def _journal(raw, declaration, report):
    """Check event order/counts and saved-row equality, without geometric derivation.

    The Java reader/checker owns geometry/terminal assessment. An authenticated
    execution can report INVALID or EVIDENCE_MISMATCH; neither is promoted here.
    A missing/torn END cannot close the execution transport successfully.
    """
    require(raw.endswith(b'\n'), 'execution journal requires complete END')
    lines = raw.split(b'\n')[:-1]
    require(2 <= len(lines) <= 47 and all(0 < len(line) <= 64*1024 for line in lines),
            'execution journal event/line limit')
    events = [_json(line + b'\n') for line in lines]
    start = dict(event='START', schema=1, inputHash=declaration['inputSha256'],
                 paths=declaration['paths'], expected=declaration['expected'])
    require(_same(events[0], start), 'execution START request identity')
    steps = [(path['id'], step) for path in declaration['paths']
             for step in range(1, path['placements'] + 1)]
    rows, attempts, pending = [], 0, False
    for event in events[1:-1]:
        if event.get('event') == 'ATTEMPT':
            require(not pending and len(rows) < len(steps), 'execution ATTEMPT order')
            path, step = steps[len(rows)]
            require(_same(event, dict(event='ATTEMPT', attempt=attempts+1, pathId=path, step=step)),
                    'execution ATTEMPT identity')
            attempts += 1
            pending = True
        else:
            require(set(event) == {'event', 'row'} and event['event'] == 'STEP' and pending,
                    'execution STEP order/fields')
            row = event['row']
            path, step = steps[len(rows)]
            require(type(row) is dict and type(row.get('step')) is int
                    and row['step'] == step and row.get('pathId') == path
                    and row.get('inputHash') == declaration['inputSha256'], 'execution row identity')
            rows.append(row)
            pending = False
    end = events[-1]
    require(set(end) == {'event', 'status', 'attempted', 'completed', 'reason'}
            and end['event'] == 'END' and type(end['status']) is str
            and end['status'] in {'COMPLETE', 'INVALID', 'EVIDENCE_MISMATCH'}
            and type(end['reason']) is str, 'execution END fields/state')
    require(type(end['attempted']) is int and end['attempted'] == attempts
            and type(end['completed']) is int and end['completed'] == len(rows), 'execution END counts')
    if end['status'] == 'COMPLETE':
        require(not pending and len(rows) == len(steps) and end['reason'] == '', 'execution false COMPLETE')
    else:
        require(end['reason'] != '', 'execution failure reason missing')
        require((end['status'] == 'INVALID' and len(rows) < len(steps))
                or (end['status'] == 'EVIDENCE_MISMATCH' and not pending and rows),
                'execution failure prefix')
    require(_same(report['recordedRows'], rows) and type(report['attempted']) is int
            and report['attempted'] == attempts and type(report['completed']) is int
            and report['completed'] == len(rows) and report['journalState'] == end['status'],
            'execution journal/report state or counts conflict')


def verify_execution_artifacts(output, request, *, ready_bytes, nonce,
                               handshake_bytes, journal_bytes, declaration_bytes, report_bytes):
    """Read exact saved artifacts after exit; caller must authenticate READY before ACK.

    No process is started and no report/JVM success grants diagnostic authority.
    Rechecks input and every output's bytes/identity across the complete read, so
    drift in an earlier file while a later one is read cannot silently pass.
    """
    require(isinstance(request, ExecutionRequestReceipt) and type(ready_bytes) is bytes
            and _hex(nonce), 'parent request/READY/nonce required')
    limits = _limits(handshake_bytes, journal_bytes, declaration_bytes, report_bytes)
    request.recheck()
    output = Path(output)
    before = _directory(output)
    require(not output.is_relative_to(request.directory) and not request.directory.is_relative_to(output),
            'separate execution output/input required')
    files = {'ready.json': min(1024*1024, handshake_bytes), 'journal.jsonl': journal_bytes,
             'report-request.json': declaration_bytes, 'report.json': report_bytes,
             'replayed.json': min(1024*1024, handshake_bytes)}
    require({p.name for p in output.iterdir()} == set(files), 'exact five execution artifacts required')
    snapshots = {name: _read(output / name, limit) for name, limit in files.items()}
    raw = {name: value[0] for name, value in snapshots.items()}
    require(raw['ready.json'] == ready_bytes, 'post-exit execution READY drift')
    require(len(raw['ready.json']) + len(raw['replayed.json']) <= handshake_bytes,
            'combined execution handshake allowance')
    ready, terminal = _json(ready_bytes), _json(raw['replayed.json'])
    require(set(ready) == FLAGS | set(LIMIT_KEYS) | {'schema', 'state', 'nonce', 'observation',
            'request_sha256', 'actions_started'}, 'execution READY fields')
    _transport(ready, limits, request.request_sha256)
    require(ready['state'] == 'READY' and ready['nonce'] == nonce and ready['actions_started'] is False
            and type(ready['observation']) is dict, 'execution READY identity/stage')
    require(set(terminal) == FLAGS | set(LIMIT_KEYS) | {'schema', 'state', 'ready_sha256',
            'request_sha256', 'journal_sha256', 'journal_bytes', 'declaration_sha256',
            'declaration_bytes', 'report_sha256', 'report_bytes', 'journal_state', 'actions_started'},
            'execution terminal fields')
    _transport(terminal, limits, request.request_sha256)
    require(terminal['state'] == SUCCESS and terminal['actions_started'] is True
            and terminal['ready_sha256'] == _hash(ready_bytes), 'execution terminal identity/stage')
    for prefix, filename in (('journal', 'journal.jsonl'), ('declaration', 'report-request.json'),
                              ('report', 'report.json')):
        require(type(terminal[prefix+'_bytes']) is int and terminal[prefix+'_bytes'] == len(raw[filename])
                and terminal[prefix+'_sha256'] == _hash(raw[filename]), 'execution artifact byte binding')
    declaration = _json(request.declaration)
    derived = dict(declaration, schema='fixed-witness-report-request-v1',
                   journalSha256=_hash(raw['journal.jsonl']))
    require(_same(_json(raw['report-request.json']), derived), 'derived report declaration identity')
    report = _json(raw['report.json'])
    require(set(report) == REPORT_KEYS and report['schema'] == 'fixed-witness-report-v1'
            and report['inputHash'] == declaration['inputSha256'], 'execution report identity/fields')
    require(all(report[name] is False for name in ('provenanceAuthenticated', 'qCertified',
                                                  'eligibleForRuntimeValidation')), 'execution report authority')
    require(_same(report['requestIdentity'], dict(derived,
                requestSha256=_hash(raw['report-request.json']))), 'execution report request identity')
    require(type(report['structuralPrefixValid']) is bool
            and all(type(report[name]) is list for name in ('recordedRows', 'observations', 'terminals')),
            'execution report structural types')
    _journal(raw['journal.jsonl'], declaration, report)
    require(type(terminal['journal_state']) is str and terminal['journal_state'] == report['journalState'],
            'execution terminal/report state conflict')
    for name, limit in files.items():
        require(_read(output / name, limit) == snapshots[name], 'post-exit execution artifact drift')
    require(_directory(output) == before and {p.name for p in output.iterdir()} == set(files),
            'execution output directory drift')
    request.recheck()
    return dict(state=SUCCESS, journal_state=report['journalState'], actions_started=True,
                request_sha256=request.request_sha256, ready_sha256=_hash(ready_bytes),
                report_sha256=_hash(raw['report.json']), artifact_bytes=sum(map(len, raw.values())),
                **limits, **{name: False for name in FLAGS})
