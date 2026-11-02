"""Small artificial process evidence; no Java or historical fixture execution."""
import copy
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

import fixed_witness_replay_observation as observation
from fixed_witness_replay_identity import sha256


class ObservationTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name).resolve()
        self.executable = self.root / 'java'
        self.executable.write_bytes(b'artificial executable')
        self.pid = 12345
        self.command = [str(self.executable), '-Xmx256m', '-cp', 'z.jar:a.jar', 'Artificial']
        self.environment = {'HOME': str(self.root), 'LANG': 'C.UTF-8'}
        self.digest = sha256(self.executable)
        self.snapshot = {'identity': {'pid': self.pid, 'parent': os.getpid(),
                                     'group': self.pid, 'session': self.pid, 'start_ticks': 123},
                         'argv': self.command, 'environment': self.environment,
                         'cwd': str(self.root), 'executable': str(self.executable),
                         'executable_sha256': self.digest}

    def observe(self, snapshot=None):
        with patch.object(observation.sys, 'platform', 'linux'), \
                patch.object(observation, '_snapshot', return_value=snapshot or self.snapshot):
            return observation.observe_process(self.pid, self.command, self.environment,
                                               self.root, self.digest)

    def test_matching_observation_is_not_diagnostic_permission(self):
        result = self.observe()
        self.assertEqual('OBSERVED', result['state'])
        self.assertNotIn('environment', result)
        self.assertFalse(result['diagnostic_authorized'])
        self.assertFalse(result['loaded_classes_verified'])
        self.assertFalse(result['network_isolation_verified'])

    def test_argv_classpath_order_and_option_drift_rejected(self):
        for argv in (self.command + ['extra'], self.command[:-1],
                     [*self.command[:3], 'a.jar:z.jar', 'Artificial']):
            with self.subTest(argv=argv):
                snapshot = copy.deepcopy(self.snapshot)
                snapshot['argv'] = argv
                with self.assertRaisesRegex(ValueError, 'argv mismatch'):
                    self.observe(snapshot)

    def test_unexpected_environment_rejected_without_logging_value(self):
        snapshot = copy.deepcopy(self.snapshot)
        snapshot['environment']['JAVA_TOOL_OPTIONS'] = 'private-value'
        with self.assertRaisesRegex(ValueError, '^observed environment mismatch$'):
            self.observe(snapshot)

    def test_not_direct_owned_group_leader_rejected(self):
        for field in ('parent', 'group', 'session'):
            snapshot = copy.deepcopy(self.snapshot)
            snapshot['identity'][field] += 1
            with self.subTest(field=field), self.assertRaisesRegex(ValueError, 'not directly owned'):
                self.observe(snapshot)

    def test_wrong_executable_hash_path_and_cwd_rejected(self):
        for field in ('executable_sha256', 'executable', 'cwd'):
            snapshot = copy.deepcopy(self.snapshot)
            snapshot[field] += 'changed'
            with self.subTest(field=field), self.assertRaisesRegex(ValueError, 'mismatch'):
                self.observe(snapshot)

    def test_reused_pid_or_mutating_evidence_rejected(self):
        for field in ('identity', 'argv', 'environment'):
            second = copy.deepcopy(self.snapshot)
            if field == 'identity':
                second[field]['start_ticks'] += 1
            elif field == 'argv':
                second[field].append('changed')
            else:
                second[field]['changed'] = 'value'
            with patch.object(observation.sys, 'platform', 'linux'), \
                    patch.object(observation, '_snapshot', side_effect=[self.snapshot, second]), \
                    self.assertRaisesRegex(ValueError, 'changed during observation'):
                observation.observe_process(self.pid, self.command, self.environment, self.root, self.digest)

    def test_missing_process_and_denied_proc_fail_closed(self):
        for error in (FileNotFoundError(), ProcessLookupError(), PermissionError()):
            with patch.object(observation.sys, 'platform', 'linux'), \
                    patch.object(observation, '_snapshot', side_effect=error), \
                    self.assertRaises(type(error)):
                observation.observe_process(self.pid, self.command, self.environment, self.root, self.digest)

    def test_stat_parentheses_zombies_and_starttime(self):
        # Fields 4..22: ppid, pgrp, session, then 15 fields, then starttime.
        suffix = b' 1 2 3' + b' 0' * 15 + b' 999'
        result = observation._stat(b'12345 (name ) with spaces)) S' + suffix, 12345)
        self.assertEqual(999, result['start_ticks'])
        self.assertEqual(1, result['parent'])
        for data in (b'12345 (z) Z' + suffix, b'999 (other) S' + suffix, b'invalid'):
            with self.subTest(data=data), self.assertRaises(ValueError):
                observation._stat(data, 12345)

    def test_nul_and_read_limit_failures(self):
        for data in (b'', b'a', b'a\0\0', b'\0'):
            with self.subTest(data=data), self.assertRaises(ValueError):
                observation._nul(data)
        path = self.root / 'large'
        path.write_bytes(b'x' * (observation.PROC_BYTES + 1))
        with self.assertRaisesRegex(ValueError, 'read ceiling'):
            observation._read(path)
        self.assertEqual(['a', 'b'], observation._nul(b'a\0b\0'))

    def test_snapshot_rejects_duplicate_or_malformed_environment(self):
        stat = b'12345 (child) S 1 2 3' + b' 0' * 15 + b' 999'
        for environment in (b'KEY=a\0KEY=b\0', b'missing-equals\0', b'=value\0'):
            with patch.object(observation, '_read', side_effect=[stat, b'java\0', environment]), \
                    self.assertRaisesRegex(ValueError, 'ambiguous process environment'):
                observation._snapshot(12345)

    def test_snapshot_reads_executable_and_rejects_stat_change(self):
        stat = b'12345 (child) S 1 2 3' + b' 0' * 15 + b' 999'
        changed = stat[:-3] + b'998'
        for after in (stat, changed):
            with patch.object(observation, '_read', side_effect=[stat, b'java\0', b'KEY=a\0', after]), \
                    patch.object(Path, 'resolve', side_effect=[self.executable, self.root]), \
                    patch.object(observation, 'sha256', return_value=self.digest):
                if after != stat:
                    with self.assertRaisesRegex(ValueError, 'identity changed'):
                        observation._snapshot(12345)
                else:
                    result = observation._snapshot(12345)
                    self.assertEqual({'KEY': 'a'}, result['environment'])
                    self.assertEqual(self.digest, result['executable_sha256'])

    def test_invalid_inputs_and_non_linux_rejected(self):
        with patch.object(observation.sys, 'platform', 'win32'), self.assertRaisesRegex(ValueError, 'Linux'):
            observation.observe_process(self.pid, self.command, self.environment, self.root, self.digest)
        for pid, command, environment, digest in (
                (True, self.command, self.environment, self.digest),
                (self.pid, ['bad\0arg'], self.environment, self.digest),
                (self.pid, self.command, {'bad=key': 'x'}, self.digest),
                (self.pid, self.command, self.environment, 'wrong')):
            with patch.object(observation.sys, 'platform', 'linux'), self.assertRaises(ValueError):
                observation.observe_process(pid, command, environment, self.root, digest)

    @unittest.skipUnless(sys.platform == 'linux', 'real proc observation requires Linux')
    def test_live_artificial_child_and_wrong_actual_argv(self):
        import select
        executable = str(Path(sys.executable).resolve())
        command = [executable, '-c', "print('READY', flush=True); input()"]
        environment = {'LANG': 'C.UTF-8'}
        process = subprocess.Popen(command, cwd=self.root, env=environment, start_new_session=True,
                                   stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        try:
            self.assertTrue(select.select([process.stdout], [], [], 5)[0], 'child not ready')
            self.assertEqual(b'READY\n', process.stdout.readline())
            digest = sha256(executable)
            with self.assertRaisesRegex(ValueError, 'argv mismatch'):
                observation.observe_process(process.pid, command + ['extra'], environment, self.root, digest)
            result = observation.observe_process(process.pid, command, environment, self.root, digest)
            self.assertEqual(process.pid, result['pid'])
            self.assertEqual(command, result['argv'])
            self.assertFalse(result['diagnostic_authorized'])
        finally:
            process.kill()
            process.wait(timeout=5)
            for stream in (process.stdin, process.stdout, process.stderr):
                stream.close()


if __name__ == '__main__':
    unittest.main()
