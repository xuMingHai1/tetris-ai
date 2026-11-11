"""Finite fake-I/O exec observations; no proc, fork, ptrace or ABI execution."""
from dataclasses import FrozenInstanceError, replace
import hashlib
import os
import stat
from types import SimpleNamespace
import unittest
from unittest.mock import patch

from fixed_witness_replay_abi_model import ExecIdentity, ProbeRecord, AbiProbeModel
from fixed_witness_replay_abi_observation import (
    AbiExecObserver, AbiObservationError, ExecObservation, ProcFiles,
    CHUNK, PROC_LIMIT, SOURCE_LIMIT, INTERPRETER_LIMIT,
)


def metadata(size, inode=1, mode=stat.S_IFREG | 0o444):
    return SimpleNamespace(st_dev=7, st_ino=inode, st_mode=mode, st_size=size,
                           st_mtime_ns=11, st_ctime_ns=12)


class Files:
    def __init__(self, record):
        self.record = record
        self.owner = 77
        self.tasks = ['77']
        self.data = {
            '/proc/100/stat': b'100 (a tricky ) name) t 77 100 100 ' + b'0 ' * 15 + b'123\n',
            '/proc/100/status': b'Name:\tprobe\nState:\tt (tracing stop)\n'
                                b'Pid:\t100\nTgid:\t100\nTracerPid:\t77\n',
            '/proc/100/cmdline': b'\0'.join(a.encode() for a in record.argv) + b'\0',
            record.source: b'artificial probe bytes',
            record.interpreter: b'artificial interpreter bytes',
            '/proc/100/exe': b'artificial interpreter bytes',
        }
        self.meta = {path: metadata(len(data), 2 if path == record.source else 1)
                     for path, data in self.data.items()}
        for path in self.data:
            if path.startswith('/proc/') and path != '/proc/100/exe':
                self.meta[path] = metadata(0)
        self.links = {'/proc/100/exe': record.interpreter}
        self.canon = {record.source: record.source, record.interpreter: record.interpreter}
        self.fds, self.closed, self.opens, self.reads = {}, [], [], []
        self.hook = lambda operation, path: None

    def getpid(self):
        return self.owner

    def listdir(self, path):
        assert path == '/proc/self/task'
        return self.tasks

    def open(self, path, *, proc=False):
        self.hook('open', path)
        fd = 3 + len(self.opens)
        self.opens.append((path, proc))
        self.fds[fd] = [path, 0]
        return fd

    def fstat(self, fd):
        path = self.fds[fd][0]
        self.hook('fstat', path)
        return self.meta[path]

    def read(self, fd, size):
        path, offset = self.fds[fd]
        self.hook('read', path)
        self.reads.append((path, size))
        data = self.data[path][offset:offset + size]
        self.fds[fd][1] += len(data)
        return data

    def close(self, fd):
        self.closed.append(fd)
        del self.fds[fd]
        self.hook('close', '')

    def readlink(self, path):
        self.hook('readlink', path)
        return self.links[path]

    def canonical(self, path):
        self.hook('canonical', path)
        return self.canon[path]


class AbiObservationTest(unittest.TestCase):
    def setup_observer(self, mode='x32'):
        source, executable = '/frozen/probe.py', '/usr/bin/python3.14'
        record = ProbeRecord(mode, source, hashlib.sha256(b'artificial probe bytes').hexdigest(),
                             executable, hashlib.sha256(b'artificial interpreter bytes').hexdigest(),
                             (executable, '-I', '-B', source, mode))
        self.identity = ExecIdentity(2, 1, 100, 3)
        self.record = record
        self.files = Files(record)
        self.current = self.identity
        self.checks = 0
        self.observer = AbiExecObserver(77, self.check, files=self.files)

    def setUp(self):
        self.setup_observer()

    def check(self):
        self.checks += 1
        return self.current

    def observe(self):
        return self.observer.observe(self.record, self.identity)

    def reject(self, error=AbiObservationError):
        with self.assertRaises(error):
            self.observe()
        self.assertIsNotNone(self.observer.stopped)
        self.assertFalse(self.files.fds)
        calls = len(self.files.opens)
        with self.assertRaises(AbiObservationError):
            self.observe()
        self.assertEqual(calls, len(self.files.opens))

    def test_both_modes_observe_without_binding_or_authority(self):
        for mode in ('x32', 'compat'):
            self.setup_observer(mode)
            result = self.observe()
            self.assertEqual(ExecObservation(self.identity, self.record, 123), result)
            self.assertIsNone(self.observer.stopped)
            self.assertFalse(self.files.fds)
            self.assertEqual(len(self.files.opens), len(set(self.files.closed)))
            self.assertGreater(self.checks, len(self.files.reads) * 2)
            other = replace(self.record, mode='compat' if mode == 'x32' else 'x32',
                            argv=self.record.argv[:-1] + ('compat' if mode == 'x32' else 'x32',))
            model = AbiProbeModel((self.record, other))
            self.assertEqual('UNBOUND', model.state(mode))
            with self.assertRaises(FrozenInstanceError):
                result.start_ticks = 9

    def test_success_cannot_retry(self):
        self.observe()
        calls = len(self.files.opens)
        with self.assertRaises(AbiObservationError):
            self.observe()
        self.assertEqual(calls, len(self.files.opens))

    def test_constructor_requires_owner_and_gate(self):
        for owner, check in ((True, self.check), (0, self.check), (2**31, self.check), (77, None)):
            with self.assertRaises(AbiObservationError):
                AbiExecObserver(owner, check, files=self.files)
        with patch('fixed_witness_replay_abi_observation.sys.platform', 'win32'):
            with self.assertRaises(AbiObservationError):
                AbiExecObserver(77, self.check)

    def test_bad_record_or_identity_precedes_io(self):
        for record, identity in ((True, self.identity), (self.record, True),
                                 (replace(self.record, source_sha256='bad'), self.identity),
                                 (self.record, replace(self.identity, generation=0))):
            self.setup_observer()
            self.record, self.identity = record, identity
            self.reject()
            self.assertFalse(self.files.opens)

    def test_owner_or_thread_inventory_drift(self):
        for owner, tasks in ((78, ['77']), (77, ['77', '78']), (77, [])):
            self.setup_observer()
            self.files.owner, self.files.tasks = owner, tasks
            self.reject()
            self.assertFalse(self.files.opens)

    def test_birth_group_tid_generation_or_gate_type_drift(self):
        for current in (True, replace(self.identity, birth=3),
                        replace(self.identity, group_birth=2),
                        replace(self.identity, tid=101), replace(self.identity, generation=4)):
            self.setup_observer()
            self.current = current
            self.reject()
            self.assertFalse(self.files.opens)

    def test_deadline_or_failure_gate_after_open_closes_fd(self):
        def hook(operation, path):
            if operation == 'open':
                self.observer.check = lambda: (_ for _ in ()).throw(TimeoutError('original deadline'))
        self.files.hook = hook
        self.reject(TimeoutError)
        self.assertEqual([3], self.files.closed)

    def test_malformed_dead_or_reused_proc_lifetime(self):
        for data in (b'', b'101 (wrong) t ' + b'0 ' * 20,
                     self.files.data['/proc/100/stat'].replace(b') t ', b') Z '),
                     self.files.data['/proc/100/stat'].replace(b'123', b'0')):
            self.setup_observer()
            self.files.data['/proc/100/stat'] = data
            self.reject(ValueError)

    def test_proc_lifetime_changes_between_reads(self):
        count = 0
        def hook(operation, path):
            nonlocal count
            if operation == 'open' and path == '/proc/100/stat':
                count += 1
                if count == 2:
                    self.files.data[path] = self.files.data[path].replace(b'123', b'124')
        self.files.hook = hook
        self.reject()

    def test_status_requires_tracer_leader_tracing_state_and_unique_fields(self):
        original = self.files.data['/proc/100/status']
        for data in (original.replace(b'TracerPid:\t77', b'TracerPid:\t78'),
                     original.replace(b'Tgid:\t100', b'Tgid:\t99'),
                     original.replace(b'Pid:\t100', b'Pid:\t101'),
                     original.replace(b't (tracing stop)', b'T (stopped)'),
                     original.replace(b'TracerPid:', b'Other:'), original + b'Pid:\t100\n'):
            self.setup_observer()
            self.files.data['/proc/100/status'] = data
            self.reject()

    def test_argv_is_exact_bytes_length_and_order(self):
        original = self.files.data['/proc/100/cmdline']
        for data in (b'', original[:-1], original + b'\0', original + b'extra\0',
                     original.replace(b'-I\0-B', b'-B\0-I'), original.replace(b'x32', b'compat'),
                     original.replace(b'/frozen', b'/other'), b'\xff\0'):
            self.setup_observer()
            self.files.data['/proc/100/cmdline'] = data
            self.reject()

    def test_canonical_paths_and_deleted_executable_rejected(self):
        for path in (self.record.source, self.record.interpreter, '/proc/100/exe'):
            self.setup_observer()
            if path == '/proc/100/exe':
                self.files.links[path] += ' (deleted)'
            else:
                self.files.canon[path] = '/alias'
            self.reject()

    def test_source_interpreter_proc_bytes_and_inode_must_match(self):
        for path in (self.record.source, self.record.interpreter, '/proc/100/exe'):
            self.setup_observer()
            self.files.data[path] = b'changed'
            self.files.meta[path] = metadata(7)
            self.reject()
        self.setup_observer()
        self.files.meta['/proc/100/exe'] = metadata(len(self.files.data['/proc/100/exe']), inode=99)
        self.reject()

    def test_nonregular_or_oversized_file_fails_before_read(self):
        for path, limit in ((self.record.source, SOURCE_LIMIT),
                            (self.record.interpreter, INTERPRETER_LIMIT),
                            ('/proc/100/status', PROC_LIMIT)):
            for meta in (metadata(limit + 1), metadata(0, mode=stat.S_IFIFO | 0o444)):
                self.setup_observer()
                self.files.meta[path] = meta
                self.reject()
                self.assertNotIn(path, [p for p, _ in self.files.reads])

    def test_zero_sized_proc_is_bounded_by_actual_read(self):
        self.files.data['/proc/100/status'] = b'x' * (PROC_LIMIT + 1)
        self.reject()
        self.assertTrue(all(size <= CHUNK for _, size in self.files.reads))

    def test_hashing_checks_gate_between_chunks(self):
        data = b'a' * (CHUNK + 1)
        self.record = replace(self.record, source_sha256=hashlib.sha256(data).hexdigest())
        self.files.data[self.record.source] = data
        self.files.meta[self.record.source] = metadata(len(data), inode=2)
        result = self.observe()
        self.assertEqual(self.record, result.record)
        sizes = [size for path, size in self.files.reads if path == self.record.source]
        self.assertGreaterEqual(len(sizes), 6)
        self.assertTrue(all(size <= CHUNK for size in sizes))

    def test_file_metadata_and_length_drift_rejected(self):
        for change in ('metadata', 'short'):
            self.setup_observer()
            def hook(operation, path):
                if operation == 'read' and path == self.record.source:
                    if change == 'metadata':
                        self.files.meta[path] = metadata(len(self.files.data[path]), inode=99)
                    else:
                        self.files.data[path] = b''
            self.files.hook = hook
            self.reject()

    def test_two_snapshots_compare_file_identity_even_with_same_bytes(self):
        count = 0
        def hook(operation, path):
            nonlocal count
            if operation == 'open' and path == self.record.source:
                count += 1
                if count == 2:
                    self.files.meta[path] = metadata(len(self.files.data[path]), inode=99)
        self.files.hook = hook
        self.reject()

    def test_io_close_and_transport_failures_latch_without_retry(self):
        for operation in ('open', 'fstat', 'read', 'close', 'canonical', 'readlink'):
            self.setup_observer()
            def hook(actual, path):
                if actual == operation:
                    raise OSError('artificial I/O failure')
            self.files.hook = hook
            self.reject(OSError)
        self.setup_observer()
        self.files.read = lambda fd, size: bytearray()
        self.reject()

    def test_native_transport_is_readonly_without_final_symlink_follow(self):
        # Exercise flag wiring only, never open a native file or proc path.
        with patch.object(os, 'open', return_value=3) as opened, \
                patch.object(os, 'O_CLOEXEC', 0x10000, create=True), \
                patch.object(os, 'O_NONBLOCK', 0x20000, create=True), \
                patch.object(os, 'O_NOFOLLOW', 0x40000, create=True):
            ProcFiles.open('/frozen/source')
            self.assertEqual(os.O_RDONLY | 0x70000, opened.call_args.args[1])
            ProcFiles.open('/proc/100/exe', proc=True)
            self.assertEqual(os.O_RDONLY | 0x30000, opened.call_args.args[1])


if __name__ == '__main__':
    unittest.main()
