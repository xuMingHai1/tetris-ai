"""Synthetic process/protocol checks; never run the HEART solver or build Maven."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

import supervise_terminal_certificate as supervisor


class SupervisionTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name).resolve()
        self.root = self.base / 'checkout'
        self.root.mkdir()
        self.output = self.base / 'evidence'
        host = patch.object(supervisor.sys, 'platform', 'linux')
        host.start()
        self.addCleanup(host.stop)

    def fake_process(self, args, root, output, timeout, *, log_prefix):
        self.assertEqual(root, self.root)
        self.assertGreater(timeout, 0)
        if log_prefix == 'prepare-':
            self.assertEqual(args, [sys.executable, str(root / 'scripts/prepare_terminal_certificate_build.py'),
                                   '--root', str(root), '--output', str(output / 'build')])
            data = {'manifest': str(output / 'build/build-manifest.json'),
                    'manifest_sha256': 'a' * 64, 'source_sha': 'b' * 40}
        else:
            self.assertEqual(log_prefix, 'verify-')
            self.assertLessEqual(timeout, 120)
            self.assertEqual(args[-4:], ['--verify', str(output / 'build/build-manifest.json'),
                                        '--manifest-sha256', 'a' * 64])
            data = {'status': 'VERIFIED', 'source_sha': 'b' * 40, 'provenance_authenticated': True,
                    'diagnostic_started': False, 'eligible_for_runtime_validation': False}
        (output / (log_prefix + 'stdout.log')).write_text(json.dumps(data))
        return {'state': 'EXITED', 'returncode': 0}

    def test_fixed_launch_digest_and_exclusive_output(self):
        with patch.object(supervisor, 'run_process', side_effect=self.fake_process) as launch:
            report = supervisor.preflight(self.root, self.output)
            self.assertEqual(report['status'], 'VERIFIED')
            self.assertFalse(report['diagnostic_started'])
            self.assertEqual(launch.call_count, 2)
            original = (self.output / 'report.json').read_bytes()
            with self.assertRaises(FileExistsError):
                supervisor.preflight(self.root, self.output)
            self.assertEqual((self.output / 'report.json').read_bytes(), original)

    def test_failed_build_stops_without_retry_and_preserves_prefix(self):
        def fail(args, root, output, timeout, *, log_prefix):
            (output / 'prepare-stderr.log').write_text('partial build error')
            return {'state': 'TIMEOUT', 'returncode': -9}
        with patch.object(supervisor, 'run_process', side_effect=fail) as launch:
            report = supervisor.preflight(self.root, self.output)
        self.assertEqual(report['status'], 'INCOMPLETE')
        self.assertFalse(report['provenance_authenticated'])
        self.assertEqual(launch.call_count, 1)
        self.assertEqual((self.output / 'prepare-stderr.log').read_text(), 'partial build error')

    def test_wrong_worker_result_is_not_authenticated(self):
        def altered(*args, **kwargs):
            result = self.fake_process(*args, **kwargs)
            if kwargs['log_prefix'] == 'verify-':
                path = self.output / 'verify-stdout.log'
                data = json.loads(path.read_text())
                data['diagnostic_started'] = True
                path.write_text(json.dumps(data))
            return result
        with patch.object(supervisor, 'run_process', side_effect=altered):
            self.assertEqual(supervisor.preflight(self.root, self.output)['status'], 'INCOMPLETE')

    def test_total_deadline_prevents_launch(self):
        with patch.object(supervisor.time, 'monotonic', side_effect=[0, 601, 602]), \
                patch.object(supervisor, 'run_process') as launch:
            report = supervisor.preflight(self.root, self.output)
        self.assertEqual(report['status'], 'INCOMPLETE')
        launch.assert_not_called()

    @unittest.skipUnless(os.name == 'posix', 'real process groups require POSIX')
    def test_real_timeout_and_nonzero_exit_retain_output(self):
        self.output.mkdir()
        result = supervisor.run_process([sys.executable, '-c',
                    'import time; print("prefix", flush=True); time.sleep(30)'],
                    self.root, self.output, .3, log_prefix='timeout-')
        self.assertEqual(result['state'], 'TIMEOUT')
        self.assertIn('prefix', (self.output / 'timeout-stdout.log').read_text())
        with self.assertRaises(ProcessLookupError):
            os.killpg(result['pid'], 0)
        result = supervisor.run_process([sys.executable, '-c', 'raise SystemExit(7)'],
                                        self.root, self.output, 5, log_prefix='exit-')
        self.assertEqual(result['state'], 'EXITED')
        self.assertEqual(result['returncode'], 7)

    def test_cli_rejects_collect_and_does_not_create_output(self):
        result = subprocess.run([sys.executable, str(Path(supervisor.__file__)), '--collect',
                                 '--output', str(self.output)], capture_output=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(self.output.exists())


if __name__ == '__main__':
    unittest.main()
