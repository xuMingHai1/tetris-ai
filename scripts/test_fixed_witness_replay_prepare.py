"""Artificial executables/classes only; no real build, Java or witness input."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

from fixed_witness_replay_environment import WRAPPER_PROPERTIES
from fixed_witness_replay_prepare import (
    FAILURE_BYTES, _classpath, _dependencies, _versions, _write, prepare, verify_prepared,
)
from fixed_witness_replay_process import OutputBudget, run_process
from fixed_witness_replay_quota import ByteQuota, OutputLimitError


class FormatTest(unittest.TestCase):
    def test_versions_bind_actual_major_release_and_runtime(self):
        toolchain = {'java_version': '27.0.1', 'java_home': '/jdk'}
        java = 'openjdk version "27.0.1" 2026\n'
        maven = 'Apache Maven 3.9.16 (hash)\nJava version: 27.0.1, vendor: X, runtime: /jdk\n'
        _versions(java, maven, toolchain)
        for j, m in ((java.replace('27.0.1', '26'), maven),
                     (java, maven.replace('3.9.16', '3.9.160')),
                     (java, maven.replace('runtime: /jdk', 'runtime: /other')),
                     (java, maven.replace('27.0.1', '27.0.2')),
                     (java, '\x1b[0m' + maven)):
            with self.subTest(java=j, maven=m), self.assertRaises(ValueError):
                _versions(j, m, toolchain)

    def test_manifest_digest_rejects_replacement_before_inventory(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory).resolve() / 'manifest.json'
            _write(path, {'state': 'PREPARED'}, ByteQuota())
            with self.assertRaisesRegex(ValueError, 'digest mismatch'):
                verify_prepared(directory, directory, {'manifest': str(path), 'sha256': '0' * 64})
            with self.assertRaises(FileExistsError):
                _write(path, {}, ByteQuota())

    def test_json_bytes_and_pipes_share_exact_charge(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'evidence.json'
            value = {'message': '\u4e2d\U0001f600'}
            expected = (json.dumps(value, sort_keys=True, separators=(',', ':'),
                                   allow_nan=False) + '\n').encode('utf-8')
            quota = ByteQuota(len(expected) + 3)
            _write(path, value, quota)
            self.assertEqual(expected, path.read_bytes())
            self.assertEqual(len(expected), quota.charged)
            with (Path(directory) / 'pipe.log').open('xb') as stream:
                self.assertTrue(OutputBudget(quota=quota).consume(b'four', stream))
            self.assertEqual(0, quota.remaining)
            self.assertEqual(b'fou', (Path(directory) / 'pipe.log').read_bytes())

    def test_json_over_limit_never_creates_file_or_spends_failure_reserve(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'manifest.json'
            quota = ByteQuota(FAILURE_BYTES + 1)
            failure = quota.reserve(FAILURE_BYTES)
            with self.assertRaises(OutputLimitError):
                _write(path, {'state': 'PREPARED'}, quota)
            self.assertFalse(path.exists())
            _write(Path(directory) / 'failure.json', {'state': 'INCOMPLETE'}, failure)
            self.assertEqual(FAILURE_BYTES, quota.charged)

    def test_invalid_json_does_not_open_or_charge(self):
        with tempfile.TemporaryDirectory() as directory:
            quota = ByteQuota()
            path = Path(directory) / 'invalid.json'
            with self.assertRaises(ValueError):
                _write(path, {'invalid': float('nan')}, quota)
            self.assertEqual(0, quota.charged)
            self.assertFalse(path.exists())

    def test_explicit_dependency_plugin_and_arguments(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            pom = root / 'pom.xml'
            pom.write_text('<project xmlns="http://maven.apache.org/POM/4.0.0"><properties>'
                           '<maven-dependency-plugin.version>3.11.0</maven-dependency-plugin.version>'
                           '</properties></project>')
            args = _dependencies(root)
            self.assertEqual(['clean', 'test-compile'], args[:2])
            self.assertIn('org.apache.maven.plugins:maven-dependency-plugin:3.11.0:build-classpath', args)
            self.assertFalse(any('outputFile' in arg or 'outputProperty' in arg for arg in args))
            pom.write_text('<project xmlns="http://maven.apache.org/POM/4.0.0"/>')
            with self.assertRaisesRegex(ValueError, 'plugin version'):
                _dependencies(root)

    def test_classpath_exact_utf8_bytes_share_pipe_quota_and_preserve_order(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'classpath.txt'
            value = '/z-last.jar:/\u4e2d-first.jar'
            text = '[INFO] Dependencies classpath:\n' + value + '\n[INFO] BUILD SUCCESS\n'
            data = (value + '\n').encode('utf-8')
            quota = ByteQuota(len(text.encode('utf-8')) + len(data))
            with (Path(directory) / 'stdout.log').open('xb') as stream:
                self.assertFalse(OutputBudget(quota=quota).consume(text.encode('utf-8'), stream))
            self.assertEqual(value.split(':'), _classpath(text, path, quota))
            self.assertEqual(data, path.read_bytes())
            self.assertEqual(0, quota.remaining)

    def test_missing_duplicate_truncated_or_ambiguous_classpath_never_creates_file(self):
        marker = '[INFO] Dependencies classpath:\n'
        cases = ('', marker, marker + '/a.jar', marker + '\n',
                 marker + '/a.jar\n' + marker + '/b.jar\n',
                 marker + '/a.jar\nDependencies classpath:\n/b.jar\n',
                 marker + '[INFO] /a.jar\n', marker + '/a.jar::/b.jar\n',
                 marker + '/a.jar:relative.jar\n', marker + ' /a.jar\n',
                 marker + '/a.jar\r\n', marker + '/a\x00.jar\n')
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'classpath.txt'
            quota = ByteQuota()
            for text in cases:
                with self.subTest(text=text), self.assertRaises(ValueError):
                    _classpath(text, path, quota)
                self.assertFalse(path.exists())
                self.assertEqual(0, quota.charged)

    def test_classpath_denial_precedes_creation_and_preserves_existing_file(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'classpath.txt'
            text = '[INFO] Dependencies classpath:\n/a.jar\n'
            quota = ByteQuota(len(b'/a.jar'))
            with self.assertRaises(OutputLimitError):
                _classpath(text, path, quota)
            self.assertFalse(path.exists())
            self.assertEqual(0, quota.charged)
            path.write_bytes(b'original')
            quota = ByteQuota()
            with self.assertRaises(FileExistsError):
                _classpath(text, path, quota)
            self.assertEqual(b'original', path.read_bytes())
            self.assertEqual(len(b'/a.jar\n'), quota.charged)


@unittest.skipUnless(sys.platform == 'linux', 'real Linux process preparation required')
class PreparationTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.base = Path(self.directory.name).resolve()
        self.root, self.jdk, self.out = [self.base / name for name in ('source', 'jdk', 'output')]
        for path in (self.root, self.jdk, self.out):
            path.mkdir()
        (self.root / '.mvn/wrapper').mkdir(parents=True)
        (self.root / '.mvn/wrapper/maven-wrapper.properties').write_text(
            ''.join(k + '=' + v + '\n' for k, v in WRAPPER_PROPERTIES.items()))
        (self.root / '.gitignore').write_text('target/\n')
        (self.root / 'pom.xml').write_text(
            '<project xmlns="http://maven.apache.org/POM/4.0.0"><properties>'
            '<maven-dependency-plugin.version>3.11.0</maven-dependency-plugin.version>'
            '</properties></project>')
        (self.jdk / 'release').write_text('JAVA_VERSION="27.0.1"\n')
        for name in ('bin/java', 'bin/javac', 'lib/modules', 'lib/server/libjvm.so'):
            path = self.jdk / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('artificial bytes')
        self.executable(self.jdk / 'bin/java', 'print(\'openjdk version "27.0.1"\')')
        self.wrapper('normal')

    def executable(self, path, code):
        path.write_text('#!' + sys.executable + '\n' + code + '\n')
        path.chmod(0o755)

    def wrapper(self, mode):
        self.executable(self.root / 'mvnw', '''import os, sys, pathlib, zipfile
root = pathlib.Path.cwd()
mode = ''' + repr(mode) + '''
assert 'PRIVATE_SENTINEL' not in os.environ
assert 'JAVA_TOOL_OPTIONS' not in os.environ
assert os.environ['MAVEN_SKIP_RC'] == '1'
if '--version' in sys.argv:
    print('Apache Maven 3.9.16 (artificial)')
    print('Java version: 27.0.1, vendor: Artificial, runtime: ' + os.environ['JAVA_HOME'])
    raise SystemExit(0)
if mode == 'fail':
    print('preserved failure prefix', flush=True)
    raise SystemExit(7)
if mode == 'flood':
    sys.stdout.write('x' * 100000)
    sys.stdout.flush()
    raise SystemExit(0)
if mode == 'source-drift':
    (root / 'pom.xml').write_text('changed')
if mode == 'toolchain-drift':
    (pathlib.Path(os.environ['JAVA_HOME']) / 'lib/modules').write_text('changed')
for base, name in [('target/classes', 'Production.class'), ('target/test-classes', 'Artificial.class')]:
    path = root / base
    path.mkdir(parents=True)
    (path / name).write_bytes(b'artificial class bytes, never loaded')
jars = [pathlib.Path(os.environ['HOME']) / name for name in ('z-last.jar', 'a-first.jar')]
for index, jar in enumerate(jars):
    with zipfile.ZipFile(jar, 'w') as archive:
        archive.writestr('Dependency' + str(index) + '.class', b'artificial')
assert not any('outputFile' in a or 'outputProperty' in a for a in sys.argv)
record = '[INFO] Dependencies classpath:\\n' + ':'.join(map(str, jars))
if mode == 'duplicate':
    print(record)
if mode != 'missing':
    print(record, file=sys.stderr if mode == 'stderr-only' else sys.stdout)
''')
        self.git('init', '-q')
        self.git('add', '.')
        self.git('-c', 'user.name=Artificial', '-c', 'user.email=artificial@example.invalid',
                 'commit', '-qm', 'artificial preparation fixture')

    def git(self, *args):
        subprocess.run(['git', *args], cwd=self.root, check=True, capture_output=True, timeout=10)

    def prepare(self, **kwargs):
        return prepare(self.root, self.jdk, self.out, kwargs.pop('budget', OutputBudget()),
                       total_deadline=time.monotonic() + 30, **kwargs)

    def test_real_process_environment_and_retained_source_classpath_toolchain(self):
        with patch.dict(os.environ, {'PRIVATE_SENTINEL': 'do-not-inherit',
                                     'JAVA_TOOL_OPTIONS': 'poison', 'GIT_INDEX_FILE': '/invalid'}):
            receipt = self.prepare()
            manifest = verify_prepared(self.root, self.jdk, receipt)
        self.assertFalse(manifest['diagnostic_started'])
        self.assertEqual(['z-last.jar', 'a-first.jar'],
                         [Path(p).name for p in manifest['classpath']['classpath'][2:]])
        self.assertEqual(3, len(manifest['processes']))
        self.assertTrue(all(p['state'] == 'EXITED' for p in manifest['processes']))
        (self.root / 'target/classes/Production.class').write_bytes(b'changed')
        with self.assertRaisesRegex(ValueError, 'drift'):
            verify_prepared(self.root, self.jdk, receipt)

    def test_existing_target_is_preserved_and_rejected(self):
        target = self.root / 'target'
        target.mkdir()
        (target / 'keep').write_text('keep')
        with self.assertRaisesRegex(ValueError, 'without target'):
            self.prepare()
        self.assertEqual('keep', (target / 'keep').read_text())
        self.assertFalse((self.out / 'manifest.json').exists())

    def test_nonzero_build_retains_prefix_and_failure(self):
        self.wrapper('fail')
        with self.assertRaisesRegex(ValueError, 'stage failed'):
            self.prepare()
        self.assertIn('preserved failure prefix', (self.out / 'clean-build-stdout.log').read_text())
        failure = json.loads((self.out / 'failure.json').read_text())
        self.assertEqual(3, failure['completed_processes'])
        result = json.loads((self.out / 'clean-build-result.json').read_text())
        self.assertEqual(7, result['returncode'])
        self.assertFalse((self.out / 'manifest.json').exists())

    def test_prepare_evidence_and_pipes_are_charged_once_with_failure_reserve(self):
        quota = ByteQuota()
        budget = OutputBudget(quota=quota)
        self.prepare(budget=budget)
        evidence = sum(path.stat().st_size for path in self.out.glob('*.json'))
        pipes = sum(path.stat().st_size for path in self.out.glob('*.log'))
        self.assertEqual(pipes, budget.written)
        classpath_bytes = (self.out / 'classpath.txt').stat().st_size
        self.assertEqual(FAILURE_BYTES + evidence + pipes + classpath_bytes, quota.charged)
        self.assertFalse((self.out / 'failure.json').exists())

    def rejected_classpath(self, mode):
        self.wrapper(mode)
        with self.assertRaisesRegex(ValueError, 'unique dependency classpath stdout'):
            self.prepare()
        failure = json.loads((self.out / 'failure.json').read_text())
        self.assertEqual(('INCOMPLETE', 3), (failure['state'], failure['completed_processes']))
        self.assertEqual(('EXITED', 0), (failure['last_process']['state'],
                                      failure['last_process']['returncode']))
        self.assertFalse((self.out / 'classpath.txt').exists())
        self.assertFalse((self.out / 'manifest.json').exists())

    def test_missing_stdout_classpath_is_incomplete(self):
        self.rejected_classpath('missing')

    def test_duplicate_stdout_classpath_is_incomplete(self):
        self.rejected_classpath('duplicate')

    def test_stderr_classpath_cannot_replace_stdout_record(self):
        self.rejected_classpath('stderr-only')

    def test_exhausted_normal_output_keeps_bounded_failure_and_no_manifest(self):
        self.wrapper('flood')
        quota = ByteQuota(50000)
        with self.assertRaises(OutputLimitError):
            self.prepare(budget=OutputBudget(quota=quota))
        failure_path = self.out / 'failure.json'
        failure = json.loads(failure_path.read_text())
        self.assertEqual('INCOMPLETE', failure['state'])
        self.assertEqual('OutputLimitError', failure['error_type'])
        self.assertEqual(3, failure['completed_processes'])
        last = failure['last_process']
        self.assertEqual('OUTPUT_LIMIT', last['state'])
        self.assertIsInstance(last['returncode'], int)
        self.assertGreater(last['observed_bytes_lower_bound'], last['stdout_stderr_bytes'])
        retained = sum(path.stat().st_size for path in self.out.glob('*.log'))
        self.assertEqual(retained, failure['stdout_stderr_bytes'])
        self.assertGreater(failure['observed_bytes_lower_bound'], retained)
        self.assertEqual((self.out / 'clean-build-stdout.log').stat().st_size,
                         last['stdout_stderr_bytes'])
        self.assertLessEqual(failure_path.stat().st_size, FAILURE_BYTES)
        self.assertEqual(0, quota.remaining)
        self.assertTrue((self.out / 'clean-build-stdout.log').stat().st_size > 0)
        self.assertFalse((self.out / 'manifest.json').exists())

    def test_source_change_during_build_never_creates_manifest(self):
        self.wrapper('source-drift')
        with self.assertRaisesRegex(ValueError, 'dirty checkout'):
            self.prepare()
        self.assertTrue((self.out / 'failure.json').exists())
        self.assertFalse((self.out / 'manifest.json').exists())

    def test_jdk_change_during_build_never_creates_manifest(self):
        self.wrapper('toolchain-drift')
        with self.assertRaisesRegex(ValueError, 'drift'):
            self.prepare()
        self.assertFalse((self.out / 'manifest.json').exists())

    def test_shared_deadline_and_explicit_environment_are_reused(self):
        deadlines, environments = [], []
        def observed(*args, **kwargs):
            deadlines.append(kwargs['total_deadline'])
            environments.append(kwargs['environment'])
            return run_process(*args, **kwargs)
        with patch('fixed_witness_replay_prepare.run_process', side_effect=observed):
            self.prepare()
        self.assertEqual(3, len(deadlines))
        self.assertEqual(1, len(set(deadlines)))
        self.assertTrue(all(env == environments[0] for env in environments))

    def test_process_explicit_environment_and_invalid_environment_rejection(self):
        result = run_process([sys.executable, '-c',
                              'import os; assert dict(os.environ) == {"ALLOWED": "yes", "LC_CTYPE": "C.UTF-8"}'],
                             self.root, self.out, 'explicit-env', OutputBudget(), seconds=3,
                             total_deadline=time.monotonic() + 5, environment={'ALLOWED': 'yes'})
        self.assertEqual(('EXITED', 0), (result['state'], result['returncode']))
        for env in ({'bad=name': 'x'}, {'X': 1}, {'X': 'null\0'}, []):
            with self.assertRaisesRegex(ValueError, 'string environment'):
                run_process([sys.executable], self.root, self.out, 'invalid-env', OutputBudget(),
                            seconds=3, total_deadline=time.monotonic() + 5, environment=env)


if __name__ == '__main__':
    unittest.main()
