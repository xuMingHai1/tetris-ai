"""Real process lifecycle tests plus synthetic provenance fixtures; no population games."""
import json
from contextlib import contextmanager
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

import supervise_goal_window as supervisor


@unittest.skipUnless(sys.platform.startswith('linux'), 'process liveness tests use Linux procfs')
class GoalWindowSupervisorTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name).resolve()
        self.root = self.directory / 'checkout'
        self.root.mkdir()
        self.output = self.directory / 'evidence'

    def run_child(self, program, timeout=5, *, ready=None):
        self.output.mkdir()
        actual_popen = subprocess.Popen

        def launch(*args, **kwargs):
            process = actual_popen(*args, **kwargs)
            if ready is not None:
                try:
                    # Keep interpreter startup outside the short timeout under test.
                    deadline = time.monotonic() + 10
                    while not ready.exists():
                        self.assertIsNone(process.poll(), 'child exited before readiness')
                        self.assertLess(time.monotonic(), deadline, 'child readiness timed out')
                        time.sleep(0.01)
                except BaseException:
                    supervisor.stop_group(process)
                    raise
            return process

        with patch.object(supervisor.subprocess, 'Popen', side_effect=launch):
            return supervisor.run_process([sys.executable, '-u', '-c', program], self.root, self.output, timeout)

    def test_timeout_preserves_stdout_stderr_and_truncated_raw_without_retry(self):
        ready = self.root / 'ready'
        result = self.run_child(
            # Deliberately exceed the timeout before writing, to exercise cold startup.
            'from pathlib import Path; import sys,time; time.sleep(0.5); '
            'Path(sys.argv[1]).write_text("complete line\\npartial tail"); '
            'print("stdout prefix", flush=True); print("stderr prefix",file=sys.stderr,flush=True); '
            f'Path({str(ready)!r}).touch(); time.sleep(30)'
            .replace('sys.argv[1]', repr(str(self.output / 'raw.jsonl'))), timeout=0.3, ready=ready)
        self.assertEqual('TIMEOUT', result['state'])
        self.assertNotEqual(0, result['returncode'])
        self.assertEqual('stdout prefix\n', (self.output / 'stdout.log').read_text())
        self.assertEqual('stderr prefix\n', (self.output / 'stderr.log').read_text())
        self.assertEqual('complete line\npartial tail', (self.output / 'raw.jsonl').read_text())

    def test_nonzero_exit_is_a_process_result_and_never_successful_evidence(self):
        result = self.run_child('import sys; print("failure",flush=True); sys.exit(23)')
        self.assertEqual('EXITED', result['state'])
        self.assertEqual(23, result['returncode'])
        self.assertFalse((self.output / 'raw.jsonl').exists())

    def test_cancellation_during_popen_construction_still_owns_and_stops_the_child(self):
        actual_popen = subprocess.Popen
        children = []

        def launch(*args, **kwargs):
            process = actual_popen(*args, **kwargs)
            children.append(process)
            os.kill(os.getpid(), signal.SIGTERM)
            return process

        with patch.object(supervisor.subprocess, 'Popen', side_effect=launch):
            result = self.run_child('import time; time.sleep(30)')
        self.assertEqual('INTERRUPTED', result['state'])
        self.assertIn('during launch', result['interruption'])
        self.assertIsNotNone(children[0].poll())

    def test_timeout_kills_descendant_even_when_it_ignores_term_and_leader_exits(self):
        host_pid_file = self.root / 'host-pid'
        child = ('import signal,time; from pathlib import Path; signal.signal(signal.SIGTERM,signal.SIG_IGN); '
                 f'Path({str(host_pid_file)!r}).write_text(Path("/proc/self/stat").read_text().split()[0]); '
                 'time.sleep(30)')
        result = self.run_child(
            'import subprocess,sys,time; '
            f'p=subprocess.Popen([sys.executable,"-u","-c",{child!r}]); '
            'print(p.pid,flush=True); time.sleep(30)', timeout=0.5, ready=host_pid_file)
        self.assertEqual('TIMEOUT', result['state'])
        self.require_stopped(int(host_pid_file.read_text()))

    def require_stopped(self, pid):
        # A killed orphan can briefly remain a zombie until the host's init reaps it.
        for _ in range(100):
            state = Path(f'/proc/{pid}/stat')
            if not state.exists() or state.read_text().split(') ')[1].startswith('Z'):
                return
            time.sleep(0.01)
        self.fail('supervised descendant is still running')

    def test_sigterm_interrupt_retains_prefix_and_stops_direct_child(self):
        self.interrupt_child(repeated=False)

    def test_repeated_term_and_int_during_cleanup_cannot_leave_a_term_ignoring_child_alive(self):
        self.interrupt_child(repeated=True)

    def interrupt_child(self, *, repeated):
        self.output.mkdir()
        child = ('import os,signal,time,json; from pathlib import Path; '
                 + ('signal.signal(signal.SIGTERM,signal.SIG_IGN); ' if repeated else '')
                 + 'print(json.dumps({"pid":os.getpid(),"host_pid":int(Path("/proc/self/stat").read_text().split()[0])}),'
                   'flush=True); time.sleep(30)')
        script = (f'import sys,json; sys.path.insert(0,{str(Path(__file__).parent)!r}); '
                  'from pathlib import Path; import supervise_goal_window as s; '
                  f'r=s.run_process([sys.executable,"-u","-c",{child!r}],'
                  f'Path({str(self.root)!r}),Path({str(self.output)!r}),10); '
                  'print(json.dumps(r),flush=True)')
        parent = subprocess.Popen([sys.executable, '-u', '-c', script], stdout=subprocess.PIPE,
                                  stderr=subprocess.PIPE, text=True)
        self.addCleanup(lambda: parent.poll() is None and parent.kill())
        prefix = self.output / 'stdout.log'
        for _ in range(200):
            if prefix.exists() and prefix.stat().st_size:
                break
            if parent.poll() is not None:
                self.fail('supervisor exited before child started: ' + parent.stderr.read())
            time.sleep(0.01)
        else:
            self.fail('child did not start')
        identity = json.loads(prefix.read_text())
        pid = identity['pid']

        def cleanup_child():
            try:
                os.killpg(pid, signal.SIGKILL)
            except ProcessLookupError:
                pass

        self.addCleanup(cleanup_child)
        os.kill(parent.pid, signal.SIGTERM)
        if repeated:
            time.sleep(0.1)  # Enter TERM grace wait before the repeated cancellation signals.
            os.kill(parent.pid, signal.SIGTERM)
            os.kill(parent.pid, signal.SIGINT)
        stdout, stderr = parent.communicate(timeout=5)
        self.assertEqual(0, parent.returncode, stderr)
        result = json.loads(stdout)
        self.assertEqual('INTERRUPTED', result['state'])
        self.require_stopped(identity['host_pid'])

    def fixture(self):
        class_file = 'target/classes/xyz/xuminghai/tetris/Fixture.class'
        path = self.root / class_file
        path.parent.mkdir(parents=True)
        path.write_bytes(b'synthetic bytecode; not a JDK build')
        manifest = {'root': str(self.root), 'jdk': {'executable': '/fixture/java'},
                    'classpath': [str(self.root / 'target/classes'), str(self.root / 'target/test-classes')],
                    'class_files': {class_file: supervisor.build.digest(path)}}
        manifest_path = self.root / supervisor.build.MANIFEST
        manifest_path.parent.mkdir(parents=True)
        manifest_path.write_text(json.dumps(manifest))
        self.manifest = manifest
        self.manifest_path = manifest_path
        self.digest = supervisor.build.digest(manifest_path)
        self.events = [
            {'schema': 'goal-window-raw-v1', 'sequence': 1, 'type': 'provenance_verified',
             'data': {'manifest_sha256': self.digest, 'manifest': manifest,
                      'loaded_classes': {'xyz.xuminghai.tetris.Fixture': {
                          'origin': str(self.root / 'target/classes'), 'sha256': manifest['class_files'][class_file]}}}},
            {'schema': 'goal-window-raw-v1', 'sequence': 2, 'type': 'verification_complete',
             'data': {'population_started': False}},
        ]
        self.prepared = {'manifest': str(manifest_path), 'manifest_sha256': self.digest, 'source_sha': '0' * 40}
        return self.prepared

    def test_build_stage_cancellation_stops_prepare_and_maven_tree_and_retains_live_console(self):
        scripts = self.root / 'scripts'
        scripts.mkdir()
        # Synthetic Wrapper stand-in, but use the real streamed build.command and real process tree.
        child = ('import os,signal,time,json; from pathlib import Path; '
                 'signal.signal(signal.SIGTERM,signal.SIG_IGN); '
                 'print(json.dumps({"pid":os.getpid(),"host_pid":int(Path("/proc/self/stat").read_text().split()[0])}),'
                 'flush=True); print("live build prefix",flush=True); time.sleep(30)')
        (scripts / 'prepare_goal_window_build.py').write_text(
            f'import sys; sys.path.insert(0,{str(Path(__file__).parent)!r}); '
            'from pathlib import Path; import prepare_goal_window_build as b; '
            f'b.command(Path.cwd(),[sys.executable,"-u","-c",{child!r}],log_path=Path(sys.argv[-1]))')
        script = (f'import sys,json; sys.path.insert(0,{str(Path(__file__).parent)!r}); '
                  'from pathlib import Path; import supervise_goal_window as s; '
                  f'r=s.preflight(Path({str(self.root)!r}),Path({str(self.output)!r})); '
                  'print(json.dumps(r),flush=True)')
        parent = subprocess.Popen([sys.executable, '-u', '-c', script], stdout=subprocess.PIPE,
                                  stderr=subprocess.PIPE, text=True)
        self.addCleanup(lambda: parent.poll() is None and parent.kill())
        console = self.output / 'build-console.log'
        for _ in range(200):
            if console.exists() and 'live build prefix' in console.read_text():
                break
            if parent.poll() is not None:
                self.fail('build supervisor exited early: ' + parent.stderr.read())
            time.sleep(0.01)
        else:
            self.fail('build did not stream its prefix')
        identity = json.loads(console.read_text().splitlines()[0])

        def cleanup_child():
            try:
                os.kill(identity['pid'], signal.SIGKILL)
            except ProcessLookupError:
                pass

        self.addCleanup(cleanup_child)
        os.kill(parent.pid, signal.SIGTERM)
        stdout, stderr = parent.communicate(timeout=5)
        self.assertEqual(0, parent.returncode, stderr)
        report = json.loads(stdout)
        self.assertEqual('INCOMPLETE', report['status'])
        self.assertEqual('INTERRUPTED', report['build_process']['state'])
        self.assertEqual(300, report['build_process']['timeout_seconds'])
        self.assertIsNone(report['population_started'])
        self.assertNotIn('process', report)
        self.assertTrue((self.output / 'prepare-stdout.log').is_file())
        self.assertTrue((self.output / 'prepare-stderr.log').is_file())
        self.assertEqual(supervisor.build.digest(console), report['artifacts']['build-console.log']['sha256'])
        self.require_stopped(identity['host_pid'])

    def fake_build(self, root, output):
        (output / 'prepare-stdout.log').write_text(json.dumps(self.prepared))
        return {'state': 'EXITED', 'returncode': 0}

    def write_events(self, path):
        path.write_text(''.join(json.dumps(e) + '\n' for e in self.events))

    def test_valid_preflight_binds_this_build_and_rejects_missing_truncated_duplicate_or_game_events(self):
        self.fixture()
        path = self.directory / 'raw.jsonl'
        self.write_events(path)
        supervisor.verify_preflight(path, self.manifest, self.digest)
        original = path.read_text()
        for content in (original.rstrip(), original.splitlines()[0] + '\n',
                        original.replace('"sequence": 1', '"sequence": 1,"sequence": 1'),
                        original.replace('verification_complete', 'session_complete'),
                        original.replace('"population_started": false', '"population_started": 0')):
            path.write_text(content)
            with self.assertRaises((ValueError, KeyError)):
                supervisor.verify_preflight(path, self.manifest, self.digest)
        self.write_events(path)
        with self.assertRaisesRegex(ValueError, 'differs from this clean build'):
            supervisor.verify_preflight(path, self.manifest, 'f' * 64)
        self.events[0]['data']['loaded_classes'] = {}
        self.write_events(path)
        with self.assertRaisesRegex(ValueError, 'missing actual loaded'):
            supervisor.verify_preflight(path, self.manifest, self.digest)

    def fake_launch(self, path, digest, output, *, verify_only):
        self.assertTrue(verify_only)
        self.assertEqual(self.digest, digest)
        self.write_events(output / 'raw.jsonl')
        (output / 'stdout.log').write_text('synthetic stdout')
        (output / 'stderr.log').write_text('synthetic stderr')
        return {'state': 'EXITED', 'returncode': 0}

    def test_report_keeps_build_logs_hashes_and_does_not_dump_secrets_or_reuse_output(self):
        prepared = self.fixture()
        with patch.object(supervisor, 'launch_build', side_effect=self.fake_build), \
                patch.object(supervisor.build, 'verify_build', return_value=self.manifest), \
                patch.object(supervisor, 'launch_prepared', side_effect=self.fake_launch), \
                patch.dict(os.environ, {'TYPESAFE_API_KEY': 'secret-must-not-appear'}):
            report = supervisor.preflight(self.root, self.output)
        self.assertEqual('VERIFIED', report['status'])
        self.assertFalse(report['population_started'])
        self.assertFalse(report['runtime_permission'])
        saved = (self.output / 'report.json').read_text()
        self.assertNotIn('secret-must-not-appear', saved)
        self.assertEqual(self.digest, report['artifacts']['build-manifest.json']['sha256'])
        with self.assertRaises(FileExistsError):
            supervisor.preflight(self.root, self.output)
        self.assertEqual(saved, (self.output / 'report.json').read_text())
        with self.assertRaisesRegex(ValueError, 'outside the checkout'):
            supervisor.preflight(self.root, self.root / 'target' / 'deleted-by-clean')

    def test_exit_zero_cannot_hide_stale_build_or_missing_raw_and_build_failure_is_saved(self):
        prepared = self.fixture()
        with patch.object(supervisor, 'launch_build', side_effect=self.fake_build), \
                patch.object(supervisor.build, 'verify_build', side_effect=[self.manifest, ValueError('changed classes')]), \
                patch.object(supervisor, 'launch_prepared', side_effect=self.fake_launch):
            report = supervisor.preflight(self.root, self.output)
        self.assertEqual('INCOMPLETE', report['status'])
        self.assertIsNone(report['population_started'])
        self.assertIn('changed classes', report['failure']['message'])
        self.assertTrue((self.output / 'raw.jsonl').exists())
        with patch.object(supervisor, 'launch_build', side_effect=ValueError('JDK 27 required')):
            failed = supervisor.preflight(self.root, self.directory / 'build-failed')
        self.assertEqual('INCOMPLETE', failed['status'])
        self.assertNotIn('process', failed)
        self.assertTrue((self.directory / 'build-failed' / 'report.json').is_file())

    def test_missing_raw_nonzero_timeout_and_interruption_never_verify_even_with_exit_zero(self):
        prepared = self.fixture()
        for index, (state, code) in enumerate((('EXITED', 0), ('EXITED', 9), ('TIMEOUT', 0), ('INTERRUPTED', 0))):
            output = self.directory / ('failed-' + str(index))
            with patch.object(supervisor, 'launch_build', side_effect=self.fake_build), \
                    patch.object(supervisor.build, 'verify_build', return_value=self.manifest), \
                    patch.object(supervisor, 'launch_prepared', return_value={'state': state, 'returncode': code}):
                report = supervisor.preflight(self.root, output)
            self.assertEqual('INCOMPLETE', report['status'])
            self.assertIsNone(report['population_started'])
            self.assertTrue((output / 'report.json').is_file())

    def test_cancellation_as_verified_block_exits_cannot_retain_success_status(self):
        self.fixture()

        @contextmanager
        def late_cancellation():
            yield
            raise InterruptedError('cancelled as verified block exits')

        with patch.object(supervisor, 'launch_build', side_effect=self.fake_build), \
                patch.object(supervisor.build, 'verify_build', return_value=self.manifest), \
                patch.object(supervisor, 'launch_prepared', side_effect=self.fake_launch), \
                patch.object(supervisor, 'interruption_handler', side_effect=late_cancellation):
            report = supervisor.preflight(self.root, self.output)
        self.assertEqual('INCOMPLETE', report['status'])
        self.assertIsNone(report['population_started'])
        self.assertEqual('InterruptedError', report['failure']['type'])

    def test_launcher_pins_one_direct_jvm_and_frozen_total_deadline(self):
        self.fixture()
        with patch.object(supervisor.build, 'verify_build', return_value=self.manifest), \
                patch.object(supervisor, 'run_process') as run:
            for verify_only in (True, False):
                supervisor.launch_prepared(self.manifest_path, self.digest, self.output, verify_only=verify_only)
                args, kwargs = run.call_args
                command, root, output, deadline = args
                self.assertEqual('/fixture/java', command[0])
                self.assertEqual(['-cp', os.pathsep.join(self.manifest['classpath']), supervisor.APPLICATION], command[1:4])
                self.assertEqual(120 if verify_only else 2700, deadline)
                self.assertEqual(verify_only, command[-1] == '--verify-only')
                self.assertEqual((self.root, self.output, {}), (root, output, kwargs))
            self.assertEqual(2, run.call_count)  # One JVM per call, no per-seed restart/retry.


if __name__ == '__main__':
    unittest.main()
