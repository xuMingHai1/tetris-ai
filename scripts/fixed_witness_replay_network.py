"""Cooperative Linux x86-64 exec-before-JVM network denial; no replay CLI.

The parent binds this file, interpreter and libseccomp bytes. Proc status does
not reveal filter contents; this is not hostile-host or bytecode attestation.
This module is also the isolated launcher, so its imports are stdlib only.
"""
import ctypes
import errno
import hashlib
import json
import os
from pathlib import Path
import platform
import sys


ARCH = 0xc000003e
DENY = {'socket': 41, 'socketpair': 53, 'connect': 42, 'bind': 49, 'listen': 50,
        'accept': 43, 'accept4': 288, 'sendto': 44, 'sendmsg': 46, 'sendmmsg': 307,
        'recvfrom': 45, 'recvmsg': 47, 'recvmmsg': 299, 'shutdown': 48, 'io_uring_setup': 425}
POLICY = {'architecture': ARCH, 'bad_arch': 'KILL_PROCESS', 'default': 'ALLOW',
          'deny': DENY, 'errno': errno.EPERM, 'no_new_privs': 1}


def require(value, message):
    if not value:
        raise ValueError(message)


def digest(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def encoded(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=True)


def policy_hash():
    return hashlib.sha256(encoded(POLICY).encode('ascii')).hexdigest()


def host():
    require(sys.platform == 'linux' and platform.machine() == 'x86_64'
            and ctypes.sizeof(ctypes.c_void_p) == 8, 'CAPABILITY_UNAVAILABLE: Linux x86-64 required')


def status(pid):
    with (Path('/proc') / str(pid) / 'status').open('rb') as stream:
        raw = stream.read(65537)
    require(len(raw) <= 65536, 'oversized process status')
    fields = {}
    for line in raw.decode('ascii').splitlines():
        key, sep, value = line.partition(':')
        if key in ('NoNewPrivs', 'Seccomp', 'Seccomp_filters'):
            require(sep and key not in fields and value.strip().isdigit(), 'invalid seccomp status')
            fields[key] = int(value.strip())
    require(set(fields) == {'NoNewPrivs', 'Seccomp', 'Seccomp_filters'}, 'missing seccomp status')
    return fields


def library(path):
    lib = ctypes.CDLL(str(path), use_errno=True)
    signatures = {
        'seccomp_arch_native': (ctypes.c_uint32, []),
        'seccomp_arch_exist': (ctypes.c_int, [ctypes.c_void_p, ctypes.c_uint32]),
        'seccomp_init': (ctypes.c_void_p, [ctypes.c_uint32]),
        'seccomp_release': (None, [ctypes.c_void_p]),
        'seccomp_attr_set': (ctypes.c_int, [ctypes.c_void_p, ctypes.c_int, ctypes.c_uint32]),
        'seccomp_syscall_resolve_name': (ctypes.c_int, [ctypes.c_char_p]),
        'seccomp_rule_add': (ctypes.c_int, [ctypes.c_void_p, ctypes.c_uint32, ctypes.c_int, ctypes.c_uint]),
        'seccomp_load': (ctypes.c_int, [ctypes.c_void_p]),
    }
    for name, (result, args) in signatures.items():
        fn = getattr(lib, name)
        fn.restype, fn.argtypes = result, args
    class Version(ctypes.Structure):
        _fields_ = [('major', ctypes.c_uint), ('minor', ctypes.c_uint), ('micro', ctypes.c_uint)]
    lib.seccomp_version.restype = ctypes.POINTER(Version)
    lib.seccomp_version.argtypes = []
    return lib


def version(lib):
    value = lib.seccomp_version().contents
    return [value.major, value.minor, value.micro]


def binding():
    """Resolve the actually loaded library; never record a bare soname as identity."""
    host()
    loaded = library('libseccomp.so.2')
    paths = set()
    with Path('/proc/self/maps').open() as stream:
        for line in stream:
            parts = line.split()
            if len(parts) == 6 and Path(parts[5]).name.startswith('libseccomp.so.'):
                paths.add(str(Path(parts[5]).resolve(strict=True)))
    require(len(paths) == 1, 'CAPABILITY_UNAVAILABLE: ambiguous libseccomp mapping')
    path = paths.pop()
    require(loaded.seccomp_arch_native() == ARCH, 'CAPABILITY_UNAVAILABLE: seccomp ABI mismatch')
    return {'path': path, 'sha256': digest(path), 'version': version(loaded)}


def chain(command):
    """Retained launch identity; the caller must still authenticate the Java argv."""
    host()
    require(isinstance(command, list) and command and all(
        isinstance(arg, str) and arg and '\0' not in arg for arg in command), 'explicit argv required')
    require(Path(command[0]).is_absolute() and Path(command[0]).is_file(), 'absolute executable required')
    python, launcher = Path(sys.executable).resolve(strict=True), Path(__file__).resolve(strict=True)
    return {'schema': 'fixed-witness-network-v1', 'python': str(python), 'python_sha256': digest(python),
            'launcher': str(launcher), 'launcher_sha256': digest(launcher),
            'library': binding(), 'policy_sha256': policy_hash(), 'command': list(command),
            'inherited_filters': status('self')['Seccomp_filters']}


def validate(record):
    host()
    require(set(record) == {'schema', 'python', 'python_sha256', 'launcher', 'launcher_sha256',
                            'library', 'policy_sha256', 'command', 'inherited_filters'}, 'invalid network record')
    require(record['schema'] == 'fixed-witness-network-v1'
            and record['policy_sha256'] == policy_hash(), 'network policy mismatch')
    for key in ('python', 'launcher'):
        path = Path(record[key])
        require(path.is_absolute() and str(path.resolve(strict=True)) == str(path)
                and digest(path) == record[key + '_sha256'], 'network launch bytes changed')
    require(record['python'] == str(Path(sys.executable).resolve(strict=True))
            and record['launcher'] == str(Path(__file__).resolve(strict=True)), 'network launch identity mismatch')
    command = record['command']
    require(isinstance(command, list) and command and all(isinstance(x, str) and x and '\0' not in x
            for x in command) and Path(command[0]).is_absolute(), 'invalid exec argv')
    require(type(record['inherited_filters']) is int and record['inherited_filters'] >= 0,
            'invalid inherited filter count')
    item = record['library']
    require(set(item) == {'path', 'sha256', 'version'}, 'invalid library binding')
    path = Path(item['path'])
    require(path.is_absolute() and str(path.resolve(strict=True)) == str(path)
            and digest(path) == item['sha256'], 'libseccomp bytes changed')
    lib = library(path)
    require(version(lib) == item['version'] and lib.seccomp_arch_native() == ARCH,
            'libseccomp version/ABI mismatch')
    return lib


def launch_command(record):
    validate(record)
    text = encoded(record)
    require(len(text) <= 65536, 'network launch record exceeds ceiling')
    return [record['python'], '-I', '-B', record['launcher'], text]


def bound_launch(command):
    record = chain(command)
    return record, launch_command(record)


def no_extra_fds():
    for entry in Path('/proc/self/fd').iterdir():
        try:
            target = os.readlink(entry)
        except FileNotFoundError:  # directory enumeration's own fd is already closed
            continue
        require(int(entry.name) <= 2 and not target.startswith('socket:'), 'extra inherited descriptor')


def install(lib):
    """Install one native-only filter. No fallbacks or omitted syscall rules."""
    libc = ctypes.CDLL(None, use_errno=True)
    libc.prctl.argtypes = [ctypes.c_int, ctypes.c_ulong, ctypes.c_ulong, ctypes.c_ulong, ctypes.c_ulong]
    libc.prctl.restype = ctypes.c_int
    require(libc.prctl(38, 1, 0, 0, 0) == 0 and libc.prctl(39, 0, 0, 0, 0) == 1,
            'CAPABILITY_UNAVAILABLE: no-new-privs failed')
    context = lib.seccomp_init(0x7fff0000)  # SCMP_ACT_ALLOW
    require(context, 'CAPABILITY_UNAVAILABLE: seccomp init failed')
    try:
        require(lib.seccomp_arch_exist(context, ARCH) == 0
                and lib.seccomp_arch_exist(context, 0x40000003) == -errno.EEXIST
                and lib.seccomp_arch_exist(context, 0x4000003e) == -errno.EEXIST,
                'CAPABILITY_UNAVAILABLE: native-only ABI required')
        require(lib.seccomp_attr_set(context, 2, 0x80000000) == 0, 'bad-ABI kill unavailable')
        for name, number in DENY.items():
            resolved = lib.seccomp_syscall_resolve_name(name.encode('ascii'))
            require(resolved == number, 'CAPABILITY_UNAVAILABLE: syscall resolution mismatch')
            require(lib.seccomp_rule_add(context, 0x50000 | errno.EPERM, resolved, 0) == 0,
                    'CAPABILITY_UNAVAILABLE: seccomp rule failed')
        require(lib.seccomp_load(context) == 0, 'CAPABILITY_UNAVAILABLE: filter load failed')
    finally:
        lib.seccomp_release(context)


def observe(pid, record):
    """Bracket Java admission with this check; mode alone is not policy proof."""
    validate(record)
    observed = status(pid)
    require(observed == {'NoNewPrivs': 1, 'Seccomp': 2,
                         'Seccomp_filters': record['inherited_filters'] + 1}, 'network status mismatch')
    return observed


def disable_core():
    import resource
    resource.setrlimit(resource.RLIMIT_CORE, (0, 0))


def execute(record):
    lib = validate(record)
    no_extra_fds()
    disable_core()  # ABI rejection must not create an unbudgeted core file.
    require(status('self')['Seccomp_filters'] == record['inherited_filters'], 'inherited filter drift')
    install(lib)
    observe(os.getpid(), record)
    os.execv(record['command'][0], record['command'])


if __name__ == '__main__':
    try:
        require(len(sys.argv) == 2 and len(sys.argv[1]) <= 65536, 'one bounded launch record required')
        execute(json.loads(sys.argv[1]))
    except Exception as error:
        # Fixed class only: command/env paths and arbitrary exception text are not logged.
        sys.stderr.write('NETWORK_LAUNCH_FAILED ' + type(error).__name__ + '\n')
        sys.exit(78)
