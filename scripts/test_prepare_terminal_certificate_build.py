"""Artificial provenance counterexamples; no solver, games or provider calls."""
import copy
import json
import os
import shutil
import subprocess
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch

import prepare_terminal_certificate_build as build


class TerminalBuildTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name).resolve()
        self.root = self.base / 'checkout'
        self.root.mkdir()
        self.output = self.base / 'evidence'
        for args in [('init', '-q'), ('config', 'user.email', 'fixture@example.invalid'),
                     ('config', 'user.name', 'Fixture'), ('config', 'core.autocrlf', 'false')]:
            self.git(*args)
        self.write('.gitignore', 'target/\n*.class\n')
        for name in (build.PROTOCOL, build.DESIGN):
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(Path(__file__).resolve().parents[1] / name, path)
        self.write('pom.xml', '<project xmlns="http://maven.apache.org/POM/4.0.0"><properties>'
                   '<maven-dependency-plugin.version>3.11.0</maven-dependency-plugin.version>'
                   '</properties></project>')
        self.git('add', '.')
        self.git('commit', '-qm', 'synthetic source')
        self.jdk = {'executable': '/fixture/bin/java', 'java.runtime.version': '27+fixture'}
        for name, value in [('production_guard', {'production_diff_verified': True}),
                            ('java_identity', self.jdk)]:
            mock = patch.object(build, name, return_value=value)
            mock.start()
            self.addCleanup(mock.stop)
        self.platform = patch.object(build.sys, 'platform', 'linux')
        self.platform.start()
        self.addCleanup(self.platform.stop)
        self.jar = self.base / 'dependency.jar'
        with zipfile.ZipFile(self.jar, 'w') as jar:
            jar.writestr('external/Helper.class', b'fixture')

    def git(self, *args):
        return subprocess.check_output(['git', '-C', str(self.root), *args], text=True)

    def write(self, name, value):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(value, encoding='utf-8')
        return path

    def prepare(self, fail=False):
        original = build.command

        def run(root, args, *, log_path=None):
            if args[0] != './mvnw':
                return original(root, args, log_path=log_path)
            if args[1:] == ['--version']:
                return 'Apache Maven 3.9.16 fixture'
            self.assertEqual(['clean', 'test-compile'], args[3:5])
            # Emulate clean: old target bytes cannot enter the manifest.
            shutil.rmtree(root / 'target', ignore_errors=True)
            log_path.write_text('artificial clean build, not a real compiler\n')
            if fail:
                raise subprocess.CalledProcessError(9, args)
            for suffix, base in build.OBSERVED.items():
                self.write(base + '/xyz/xuminghai/tetris/' + suffix.replace('.', '/') + '.class', suffix)
            self.write(build.BUILD + '/classpath.txt', str(self.jar))
            self.write(build.BUILD + '/dependency-tree.txt', 'fixture dependency tree')
            return 'fixture'

        with patch.object(build, 'command', side_effect=run):
            return build.prepare(self.root, self.output)

    def test_prepare_cleans_stale_target_and_guard_runs_before_and_after_build(self):
        self.write('target/test-classes/Stale.class', 'old')
        result = self.prepare()
        path = Path(result['manifest'])
        manifest = build.verify_build(path, result['manifest_sha256'])
        self.assertNotIn('target/test-classes/Stale.class', manifest['compiled_files'])
        self.assertGreaterEqual(build.production_guard.call_count, 3)
        self.assertFalse((self.output / 'preflight.json').exists())
        with self.assertRaises(FileExistsError):
            self.prepare()

    def test_build_failure_retains_prefix_and_never_creates_manifest(self):
        with self.assertRaises(subprocess.CalledProcessError):
            self.prepare(fail=True)
        self.assertTrue((self.output / 'build.log').read_text().startswith('artificial'))
        self.assertFalse((self.output / 'build-manifest.json').exists())

    def test_manifest_and_compiled_resource_tampering_fail(self):
        result = self.prepare()
        path, sha = Path(result['manifest']), result['manifest_sha256']
        with self.assertRaisesRegex(ValueError, 'digest mismatch'):
            build.verify_build(path, '0' * 64)
        manifest = build.read_json(path)
        manifest['source_sha'] = '0' * 40
        path.write_text(json.dumps(manifest))
        with self.assertRaisesRegex(ValueError, 'digest mismatch'):
            build.verify_build(path, sha)
        # Even an independently supplied replacement digest cannot hide source drift.
        with self.assertRaisesRegex(ValueError, 'source/protocol/design/guard'):
            build.verify_build(path, build.digest(path))

    def test_extra_stale_classes_resources_and_reordered_classpath_fail(self):
        result = self.prepare()
        path, sha = Path(result['manifest']), result['manifest_sha256']
        extra = self.write('target/classes/injected.properties', 'changed resource')
        with self.assertRaisesRegex(ValueError, 'compiled files'):
            build.verify_build(path, sha)
        extra.unlink()
        extra = self.write('target/test-classes/Extra.class', 'stale')
        with self.assertRaisesRegex(ValueError, 'compiled files'):
            build.verify_build(path, sha)
        extra.unlink()
        data = build.read_json(path)
        data['classpath'][:2] = reversed(data['classpath'][:2])
        path.write_text(json.dumps(data))
        with self.assertRaisesRegex(ValueError, 'classpath order'):
            build.verify_build(path, build.digest(path))

    def test_dirty_ignored_and_hidden_source_fail(self):
        dirty = self.write('src/test/java/New.java', 'untracked')
        with self.assertRaisesRegex(ValueError, 'clean including'):
            build.source_state(self.root)
        dirty.unlink()
        ignored = self.write('src/test/java/Hidden.class', 'ignored')
        with self.assertRaisesRegex(ValueError, 'ignored source'):
            build.source_state(self.root)
        ignored.unlink()
        self.git('update-index', '--assume-unchanged', 'pom.xml')
        self.write('pom.xml', 'hidden changed build')
        with self.assertRaisesRegex(ValueError, 'hidden index'):
            build.source_state(self.root)

    def test_shadow_and_implicit_jar_classpath_fail(self):
        for entry in ('xyz/xuminghai/tetris/core/BoardRules.class',
                      'META-INF/versions/27/xyz/xuminghai/tetris/core/BoardRules.class'):
            with zipfile.ZipFile(self.jar, 'w') as jar:
                jar.writestr(entry, b'shadow')
            with self.assertRaisesRegex(ValueError, 'shadows project'):
                build.dependencies([self.jar])
        with zipfile.ZipFile(self.jar, 'w') as jar:
            jar.writestr('META-INF/MANIFEST.MF', 'Manifest-Version: 1.0\nClass-Path: hidden.jar\n')
        with self.assertRaisesRegex(ValueError, 'implicit dependency'):
            build.dependencies([self.jar])

    def test_observed_classes_all_bind_to_external_inventory(self):
        result = self.prepare()
        data = build.read_json(Path(result['manifest']))
        observed = {'schema': 'terminal-certificate-preflight-v1', 'diagnostic_started': False,
                    'provenance_authenticated': False, 'eligible_for_runtime_validation': False,
                    'java_runtime': self.jdk['java.runtime.version'], 'jvm_args': build.FLAGS,
                    'loaded_classes': {}}
        for suffix, base in build.OBSERVED.items():
            name = 'xyz.xuminghai.tetris.' + suffix
            key = base + '/' + name.replace('.', '/') + '.class'
            observed['loaded_classes'][name] = {'origin': str(self.root / base),
                                                'sha256': data['compiled_files'][key]}
        build.check_observation(data, observed)
        for mutation in ('missing', 'hash', 'origin', 'extra'):
            bad = copy.deepcopy(observed)
            name = next(iter(bad['loaded_classes']))
            if mutation == 'missing':
                del bad['loaded_classes'][name]
            elif mutation == 'extra':
                bad['loaded_classes']['unexpected'] = {}
            else:
                bad['loaded_classes'][name]['sha256' if mutation == 'hash' else 'origin'] = 'wrong'
            with self.assertRaisesRegex(ValueError, 'observed class inventory'):
                build.check_observation(data, bad)

    def test_symlinked_target_is_rejected(self):
        destination = self.base / 'other'
        destination.mkdir()
        try:
            (self.root / 'target').symlink_to(destination, target_is_directory=True)
        except OSError as error:
            if os.name == 'nt' and error.winerror == 1314:
                self.skipTest('Windows symbolic-link privilege unavailable')
            raise
        with self.assertRaisesRegex(ValueError, 'target symlink'):
            build.compiled_inventory(self.root)


if __name__ == '__main__':
    unittest.main()
