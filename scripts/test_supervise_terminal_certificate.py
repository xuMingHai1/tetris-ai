"""Synthetic process/protocol checks; never run the HEART solver or build Maven."""
import json
import contextlib
import io
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

import supervise_terminal_certificate as supervisor


class Fixture(unittest.TestCase):
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


class SupervisionTest(Fixture):
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

    def test_cli_rejects_budget_override_and_does_not_create_output(self):
        result = subprocess.run([sys.executable, str(Path(supervisor.__file__)), '--collect', '--seconds', '240',
                                 '--output', str(self.output)], capture_output=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(self.output.exists())


class CollectTest(Fixture):
    """Artificial worker/files only. No JVM, full HEART search or Maven invocation."""

    def fake_collect(self, args, root, output, timeout, *, log_prefix):
        self.calls.append(log_prefix)
        self.assertGreater(timeout, 0)
        if log_prefix == 'prepare-':
            data = {'manifest': str(output / 'build/build-manifest.json'),
                    'manifest_sha256': '', 'source_sha': 'b' * 40}
            directory = output / 'build'
            directory.mkdir()
            manifest = {'root': str(root), 'source_sha': 'b' * 40, 'jvm_args': ['-Xmx512m'],
                        'jdk': {'executable': '/fixture/java', 'java.runtime.version': '27+fixture'},
                        'classpath': ['/fixture/classes'], 'compiled_files': {}}
            for suffix, base in supervisor.build.OBSERVED.items():
                name = 'xyz.xuminghai.tetris.' + suffix
                manifest['compiled_files'][base + '/' + name.replace('.', '/') + '.class'] = 'c' * 64
            (directory / 'build-manifest.json').write_text(json.dumps(manifest))
            data['manifest_sha256'] = supervisor.build.digest(directory / 'build-manifest.json')
        elif log_prefix in ('before-', 'after-'):
            self.assertLessEqual(timeout, 120)
            self.assertEqual(args[-1], '--authenticate-only')
            self.assertEqual(args[-5:-3], ['--verify', str(output / 'build/build-manifest.json')])
            self.assertEqual(args[-2], supervisor.build.digest(output / 'build/build-manifest.json'))
            data = {'status': 'BUILD_AUTHENTICATED', 'source_sha': 'b' * 40,
                    'diagnostic_started': False, 'eligible_for_runtime_validation': False}
        else:
            self.assertEqual(log_prefix, 'collect-')
            self.assertLessEqual(timeout, 120)
            self.assertEqual(args, ['/fixture/java', '-Xmx512m', '-cp', '/fixture/classes',
                                   supervisor.APPLICATION, '--collect', str(root), str(output / 'raw')])
            raw = output / 'raw'
            raw.mkdir()
            observation = {'schema': 'terminal-certificate-preflight-v1', 'java_runtime': '27+fixture',
                           'jvm_args': ['-Xmx512m'], 'diagnostic_started': False,
                           'provenance_authenticated': False, 'eligible_for_runtime_validation': False,
                           'loaded_classes': {'xyz.xuminghai.tetris.' + suffix: {
                               'origin': str(root / base), 'sha256': 'c' * 64}
                               for suffix, base in supervisor.build.OBSERVED.items()}}
            (raw / 'observation.json').write_text(json.dumps(observation))
            (raw / 'started.json').write_text(json.dumps({
                'schema': 'terminal-certificate-cli-v1', 'status': 'INCOMPLETE', 'diagnostic_started': True,
                'provenance_authenticated': False, 'eligible_for_runtime_validation': False}))
            data = {'schema': 'terminal-certificate-cli-v1', 'status': 'SESSION_COMPLETE',
                    'search_status': 'BOUNDED_MISS', 'journal_state': 'COMPLETE',
                    'enumerations': 0, 'solver_replays': 0, 'checker_replays': 0,
                    'diagnostic_started': True, 'provenance_authenticated': False,
                    'eligible_for_runtime_validation': False}
            (raw / 'completion.json').write_text(json.dumps(data))
            def event(kind, body):
                return json.dumps({'schema': 'terminal-certificate-session-v1', 'event': kind, 'data': body,
                                   'provenance_authenticated': False, 'eligible_for_runtime_validation': False}) + '\n'
            (raw / 'session.jsonl').write_text(event('start', {'state': 'INCOMPLETE', 'limits': {
                'nodes': 200000, 'decompositions': 128, 'enumerations': 2000, 'steps': 16}}) +
                event('finish', {'state': 'SESSION_COMPLETE', 'search_status': 'BOUNDED_MISS',
                                'enumerations': 0, 'solver_replays': 0, 'checker_replays': 0,
                                'geometry': {'nodes': 200000, 'decompositions': 0, 'stop': 'NODE_LIMIT'}}))
        (output / (log_prefix + 'stdout.log')).write_text(json.dumps(data))
        return {'state': 'EXITED', 'returncode': 0, 'command': args, 'pid': 12345}

    def run_collect(self, mutation=None):
        self.calls = []
        def worker(*args, **kwargs):
            result = self.fake_collect(*args, **kwargs)
            return mutation(result, kwargs['log_prefix']) if mutation else result
        with patch.object(supervisor, 'run_process', side_effect=worker):
            return supervisor.supervise(self.root, self.output, collect=True)

    def test_collect_direct_command_before_after_and_raw_hashes(self):
        report = self.run_collect()
        self.assertEqual(report['status'], 'BOUNDED_MISS')
        self.assertTrue(report['diagnostic_started'])
        self.assertTrue(report['provenance_authenticated'])
        self.assertFalse(report['eligible_for_runtime_validation'])
        self.assertEqual(self.calls, ['prepare-', 'before-', 'collect-', 'after-'])
        self.assertEqual(report['evidence']['session_sha256'],
                         supervisor.build.digest(self.output / 'raw/session.jsonl'))
        self.assertIsNone(report['peak_rss'])
        original = (self.output / 'report.json').read_bytes()
        with self.assertRaises(FileExistsError):
            supervisor.supervise(self.root, self.output, collect=True)
        self.assertEqual((self.output / 'report.json').read_bytes(), original)

    def test_collect_timeout_preserves_prefix_checks_after_and_never_retries(self):
        def timeout(result, name):
            if name == 'collect-':
                result.update(state='TIMEOUT', returncode=-9)
            return result
        report = self.run_collect(timeout)
        self.assertEqual(report['status'], 'INCOMPLETE')
        self.assertFalse(report['provenance_authenticated'])
        self.assertTrue(report['diagnostic_started'])
        self.assertEqual(self.calls, ['prepare-', 'before-', 'collect-', 'after-'])
        self.assertTrue((self.output / 'raw/session.jsonl').is_file())

    def test_after_authentication_failure_cannot_be_a_miss(self):
        def failure(result, name):
            if name == 'after-':
                result['returncode'] = 1
            return result
        report = self.run_collect(failure)
        self.assertEqual(report['status'], 'INCOMPLETE')
        self.assertFalse(report['provenance_authenticated'])

    def test_missing_completion_rejected_after_guard(self):
        def missing(result, name):
            if name == 'collect-':
                (self.output / 'raw/completion.json').unlink()
            return result
        self.assertEqual(self.run_collect(missing)['status'], 'INCOMPLETE')
        self.assertEqual(self.calls[-1], 'after-')

    def test_wrong_class_hash_rejected(self):
        def shadow(result, name):
            if name == 'collect-':
                path = self.output / 'raw/observation.json'
                value = json.loads(path.read_text())
                next(iter(value['loaded_classes'].values()))['sha256'] = 'd' * 64
                path.write_text(json.dumps(value))
            return result
        self.assertEqual(self.run_collect(shadow)['status'], 'INCOMPLETE')

    def test_torn_raw_rejected(self):
        def torn(result, name):
            if name == 'collect-':
                path = self.output / 'raw/session.jsonl'
                path.write_bytes(path.read_bytes()[:-1])
            return result
        self.assertEqual(self.run_collect(torn)['status'], 'INCOMPLETE')

    def test_completion_stdout_mismatch_rejected(self):
        def mismatch(result, name):
            if name == 'collect-':
                path = self.output / 'collect-stdout.log'
                value = json.loads(path.read_text())
                value['enumerations'] = 1
                path.write_text(json.dumps(value))
            return result
        self.assertEqual(self.run_collect(mismatch)['status'], 'INCOMPLETE')

    def test_raw_budget_override_rejected(self):
        def enlarged(result, name):
            if name == 'collect-':
                path = self.output / 'raw/session.jsonl'
                path.write_text(path.read_text().replace('200000', '400000'))
            return result
        self.assertEqual(self.run_collect(enlarged)['status'], 'INCOMPLETE')

    def test_before_authentication_failure_never_launches_diagnostic(self):
        def failure(result, name):
            if name == 'before-':
                result['returncode'] = 1
            return result
        report = self.run_collect(failure)
        self.assertEqual(report['status'], 'INCOMPLETE')
        self.assertFalse(report['diagnostic_started'])
        self.assertEqual(self.calls, ['prepare-', 'before-'])

    def test_authenticate_only_worker_never_launches_jvm(self):
        args = ['prepare', '--verify', str(self.base / 'manifest.json'),
                '--manifest-sha256', 'a' * 64, '--authenticate-only']
        with patch.object(sys, 'argv', args), \
                patch.object(supervisor.build, 'verify_build', return_value={'source_sha': 'b' * 40}) as check, \
                patch.object(supervisor.build, 'preflight') as launch, \
                contextlib.redirect_stdout(io.StringIO()) as stdout:
            supervisor.build.main()
        check.assert_called_once_with(self.base / 'manifest.json', 'a' * 64)
        launch.assert_not_called()
        self.assertEqual(json.loads(stdout.getvalue()), {'status': 'BUILD_AUTHENTICATED',
                         'source_sha': 'b' * 40, 'diagnostic_started': False,
                         'eligible_for_runtime_validation': False})


if __name__ == '__main__':
    unittest.main()
