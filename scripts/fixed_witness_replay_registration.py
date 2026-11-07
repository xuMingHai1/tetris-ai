"""Cooperative one-shot intent retention, never execution admission.

The caller owns a durable external registry and excludes every other writer,
including hardlinks. Retained metadata is a declaration, not authentication of
source, input, compatibility, network isolation or permission to run a witness.
No CLI, fixture reader, subprocess, build or execution flow is provided.
"""
from dataclasses import dataclass, field
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import sys

from fixed_witness_replay_quota import ByteQuota


SCHEMA = 'fixed-witness-registration-intent-v1'
MAX_BYTES = 16384
LIMITS = dict(build_seconds=600, jvm_seconds=120, parent_seconds=180,
              total_seconds=900, heap_mib=256, metaspace_mib=128,
              output_bytes=10485760, max_processes=16, max_placements=22,
              max_searches=0, max_runs=1)
FLAGS = ('diagnostic_authorized', 'provenance_authenticated',
         'network_isolation_verified', 'eligible_for_runtime_validation', 'actions_started')
KEYS = frozenset(('schema', 'study_key', 'session_id', 'implementation_sha',
                  'input_sha256', 'compatibility_sha256', 'command',
                  'execution_location', 'limits', *FLAGS))


def _require(condition, message):
    if not condition:
        raise ValueError(message)


def _text(value, maximum):
    _require(type(value) is str and value and not any(c in value for c in '\0\r\n'),
             'nonempty text without NUL/CR/LF required')
    _require(len(value.encode('utf-8')) <= maximum, 'text byte limit exceeded')


def _validate(value):
    _require(type(value) is dict and value.keys() == KEYS, 'exact intent fields required')
    _require(value['schema'] == SCHEMA, 'wrong intent schema')
    for key in ('study_key', 'session_id'):
        item = value[key]
        _require(type(item) is str and re.fullmatch(r'[a-z0-9](?:[a-z0-9-]{0,62}[a-z0-9])?', item),
                 'invalid logical study/session key')
    for key, length in (('implementation_sha', 40), ('input_sha256', 64),
                        ('compatibility_sha256', 64)):
        _require(type(value[key]) is str and re.fullmatch('[0-9a-f]{' + str(length) + '}', value[key]),
                 'lowercase fixed-width digest required')
    _require(all(value[key] is False for key in FLAGS), 'intent cannot authorize execution')
    limits = value['limits']
    _require(type(limits) is dict and limits.keys() == LIMITS.keys()
             and all(type(limits[key]) is int and limits[key] == expected
                     for key, expected in LIMITS.items()), 'original exact integer limits required')
    command = value['command']
    _require(type(command) is list and 1 <= len(command) <= 64, 'bounded argv array required')
    for argument in command:
        _text(argument, 1024)
    _text(value['execution_location'], 4096)
    _require(Path(value['execution_location']).is_absolute(), 'absolute execution location required')


def encode_intent(value):
    """Validate before serializing this independent metadata schema to owned bytes."""
    _validate(value)
    data = (json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=True,
                       allow_nan=False) + '\n').encode('utf-8')
    _require(len(data) <= MAX_BYTES, 'intent byte limit exceeded')
    return data


def _unique(pairs):
    result = {}
    for key, value in pairs:
        _require(key not in result, 'duplicate intent field')
        result[key] = value
    return result


def _constant(value):
    raise ValueError('nonfinite JSON value: ' + value)


def decode_intent(data):
    """Reject alternate spellings as well as malformed or oversized declarations."""
    _require(type(data) is bytes and 0 < len(data) <= MAX_BYTES, 'bounded intent bytes required')
    value = json.loads(data.decode('utf-8', errors='strict'), object_pairs_hook=_unique,
                       parse_constant=_constant)
    _require(encode_intent(value) == data, 'canonical intent bytes required')
    return value


def _digest(data):
    return hashlib.sha256(data).hexdigest()


def _identity(info):
    return info.st_dev, info.st_ino, info.st_size, info.st_mtime_ns


def _directory(registry, checkout):
    _require(sys.platform.startswith('linux') and hasattr(os, 'O_NOFOLLOW'),
             'Linux nofollow/directory-fsync capability required')
    paths = [Path(path) for path in (registry, checkout)]
    for path in paths:
        _require(path.is_absolute() and path == path.resolve(strict=True) and path.is_dir(),
                 'canonical unlinked directory required')
    registry, checkout = paths
    _require(not registry.is_relative_to(checkout), 'external registry required')
    return registry, checkout


@dataclass(frozen=True)
class IntentReceipt:
    """Caller-retained authority; no file beside the intent can replace this receipt."""
    registry: Path
    checkout: Path
    study_key: str
    canonical_bytes: bytes
    sha256: str
    registry_identity: tuple
    file_identity: tuple
    state: str = field(default='INTENT_RETAINED', init=False)


def retain_intent(registry, checkout, manifest_bytes, expected_sha256, quota):
    """Permanently debit and exclusively retain one logical study, without retry.

    A failed create/write/fsync keeps its debit and any prefix. Existing records,
    even identical ones, are never adopted, overwritten, removed or refunded.
    The directory and checkout must remain privately owned throughout this call.
    """
    value = decode_intent(manifest_bytes)
    _require(type(expected_sha256) is str and expected_sha256 == _digest(manifest_bytes),
             'independent intent digest mismatch')
    _require(isinstance(quota, ByteQuota), 'shared root ByteQuota required')
    registry, checkout = _directory(registry, checkout)
    directory = os.open(registry, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        registry_id = _identity(os.fstat(directory))[:2]
        _require(_identity(registry.stat())[:2] == registry_id, 'registry identity drift')
        name = value['study_key'] + '.intent.json'
        quota.charge(len(manifest_bytes))
        descriptor = os.open(name, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW,
                             0o600, dir_fd=directory)
        try:
            _require(stat.S_ISREG(os.fstat(descriptor).st_mode), 'regular intent file required')
            if os.write(descriptor, manifest_bytes) != len(manifest_bytes):
                raise OSError('short intent write')
            os.fsync(descriptor)
            file_id = _identity(os.fstat(descriptor))
        finally:
            os.close(descriptor)
        os.fsync(directory)
        receipt = IntentReceipt(registry, checkout, value['study_key'], bytes(manifest_bytes),
                                expected_sha256, registry_id, file_id)
        verify_intent(receipt)
        return receipt
    finally:
        os.close(directory)


def verify_intent(receipt):
    """Read against independently retained bytes and identities without promotion."""
    _require(type(receipt) is IntentReceipt, 'independent IntentReceipt required')
    value = decode_intent(receipt.canonical_bytes)
    _require(value['study_key'] == receipt.study_key
             and receipt.sha256 == _digest(receipt.canonical_bytes), 'receipt mismatch')
    registry, _ = _directory(receipt.registry, receipt.checkout)
    directory = os.open(registry, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        _require(_identity(os.fstat(directory))[:2] == receipt.registry_identity,
                 'registry identity drift')
        name = receipt.study_key + '.intent.json'
        descriptor = os.open(name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=directory)
        try:
            before = os.fstat(descriptor)
            _require(stat.S_ISREG(before.st_mode) and _identity(before) == receipt.file_identity
                     and 0 < before.st_size <= MAX_BYTES, 'intent identity drift')
            data = bytearray()
            while len(data) <= MAX_BYTES:
                block = os.read(descriptor, MAX_BYTES + 1 - len(data))
                if not block:
                    break
                data.extend(block)
            _require(_identity(os.fstat(descriptor)) == receipt.file_identity,
                     'intent changed during read')
        finally:
            os.close(descriptor)
        _require(_identity(os.stat(name, dir_fd=directory, follow_symlinks=False)) == receipt.file_identity,
                 'intent path replacement')
        _require(bytes(data) == receipt.canonical_bytes and _digest(data) == receipt.sha256,
                 'retained intent bytes changed')
        _directory(receipt.registry, receipt.checkout)
        _require(_identity(registry.stat())[:2] == receipt.registry_identity,
                 'registry path replacement')
    finally:
        os.close(directory)
    return receipt
