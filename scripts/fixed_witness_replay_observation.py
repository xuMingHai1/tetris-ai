"""Observe a cooperative, directly owned Linux process before replay admission.

Expected argv/environment/executable digest must come from the independently
retained preparation receipt. This component neither launches nor authorizes a
diagnostic; it does not prove loaded classes, JVM limits or network isolation.
The final supervisor must hold the child at a pre-replay handshake, call this
observer, and release it only after all other gates pass. A child exiting before
observation is INCOMPLETE, never a successful authentication or retry trigger.
"""
import hashlib
import os
from pathlib import Path
import re
import sys

from fixed_witness_replay_identity import canonical, require, sha256


PROC_BYTES = 1024 * 1024


def _read(path):
    # proc pseudo-files report size zero. Bound the actual read instead.
    with path.open('rb') as stream:
        data = stream.read(PROC_BYTES + 1)
    require(len(data) <= PROC_BYTES, 'process evidence exceeds read ceiling')
    return data


def _nul(data):
    require(data and data.endswith(b'\0'), 'incomplete NUL-delimited process evidence')
    result = data[:-1].split(b'\0')
    require(all(result), 'empty process evidence entry')
    return [value.decode('utf-8', errors='strict') for value in result]


def _stat(data, pid):
    # comm may contain spaces and closing parentheses. Numeric fields follow
    # the LAST closing parenthesis, not the first whitespace-separated word.
    prefix, separator, suffix = data.rpartition(b') ')
    require(separator and prefix.startswith(str(pid).encode() + b' ('), 'invalid process stat')
    fields = suffix.split()
    require(len(fields) >= 20 and fields[0] not in (b'Z', b'X', b'x'), 'process not live')
    return {'pid': pid, 'parent': int(fields[1]), 'group': int(fields[2]),
            'session': int(fields[3]), 'start_ticks': int(fields[19])}


def _snapshot(pid):
    root = Path('/proc') / str(pid)
    before = _stat(_read(root / 'stat'), pid)
    argv = _nul(_read(root / 'cmdline'))
    environment = {}
    for entry in _nul(_read(root / 'environ')):
        key, separator, value = entry.partition('=')
        require(separator and key and key not in environment, 'ambiguous process environment')
        environment[key] = value
    executable = (root / 'exe').resolve(strict=True)
    cwd = (root / 'cwd').resolve(strict=True)
    digest = sha256(executable)
    after = _stat(_read(root / 'stat'), pid)
    require(before == after, 'process identity changed during observation')
    return {'identity': before, 'argv': argv, 'environment': environment,
            'executable': str(executable), 'executable_sha256': digest, 'cwd': str(cwd)}


def observe_process(pid, command, environment, cwd, executable_sha256):
    """Fail closed unless two bounded proc observations match retained inputs.

    Only the supervisor's direct session/group leader is eligible. No ambient
    environment is read or logged. On mismatch no actual environment values are
    included in errors; successful evidence contains only its byte digest.
    Reads may fail if the child exits; callers retain that failure and must not
    rerun the diagnostic. This is cooperative observation, not hostile-process
    attestation: same-PID exec/argv mutation after observation is not prevented.
    """
    require(sys.platform == 'linux', 'Linux process observation required')
    require(type(pid) is int and pid > 0, 'positive child pid required')
    require(isinstance(command, (list, tuple)) and command and all(
        isinstance(value, str) and value and '\0' not in value for value in command),
        'explicit argv required')
    require(isinstance(environment, dict) and environment and all(
        isinstance(key, str) and key and '=' not in key and '\0' not in key
        and isinstance(value, str) and '\0' not in value
        for key, value in environment.items()), 'explicit environment required')
    require(isinstance(executable_sha256, str) and re.fullmatch('[0-9a-f]{64}', executable_sha256),
            'retained executable digest required')
    executable, cwd = canonical(command[0]), canonical(cwd)
    require(executable.is_file() and cwd.is_dir(), 'executable and cwd required')
    first = _snapshot(pid)
    require(first['identity']['parent'] == os.getpid()
            and first['identity']['group'] == pid and first['identity']['session'] == pid,
            'process is not directly owned session leader')
    require(first['argv'] == list(command), 'observed argv mismatch')
    require(first['environment'] == environment, 'observed environment mismatch')
    require(first['cwd'] == str(cwd), 'observed cwd mismatch')
    require(first['executable'] == str(executable)
            and first['executable_sha256'] == executable_sha256, 'observed executable mismatch')
    require(first == _snapshot(pid), 'process evidence changed during observation')
    raw_environment = b'\0'.join((key + '=' + environment[key]).encode('utf-8')
                                 for key in sorted(environment)) + b'\0'
    return {'state': 'OBSERVED', **first['identity'], 'argv': first['argv'],
            'executable': str(executable), 'executable_sha256': executable_sha256,
            'cwd': str(cwd), 'environment_sha256': hashlib.sha256(raw_environment).hexdigest(),
            'environment_digest_format': 'sorted-utf8-key=value-NUL',
            'loaded_classes_verified': False, 'network_isolation_verified': False,
            'diagnostic_authorized': False}
