"""Synthetic temp-directory provenance tests; no Maven build or population collection."""
import json
import os
import subprocess
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch

import prepare_goal_window_build as build


class GoalWindowBuildTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.git('init', '-q')
        self.git('config', 'user.email', 'test@example.invalid')
        self.git('config', 'user.name', 'Test')
        self.write('.gitignore', 'target/\n*.class\n')
        self.write(build.CONFIG, json.dumps(build.EXPECTED_CONFIG))
        protocol = Path(__file__).resolve().parents[1] / build.PROTOCOL
        self.write(build.PROTOCOL, protocol.read_text())
        self.write('pom.xml', '<project xmlns="http://maven.apache.org/POM/4.0.0"><properties>'
                   '<maven-dependency-plugin.version>3.11.0</maven-dependency-plugin.version>'
                   '</properties></project>')
        self.git('add', '.')
        self.git('commit', '-qm', 'synthetic source')
        self.guard = patch.object(build, 'production_guard', return_value={'production_diff_verified': True})
        self.guard.start()
        self.addCleanup(self.guard.stop)
        self.jdk = {'executable': '/synthetic/jdk/bin/java', 'java.home': '/synthetic/jdk',
                    'java.runtime.version': '27+fixture', 'java.vendor': 'fixture'}
        self.java = patch.object(build, 'java_identity', return_value=self.jdk)
        self.java.start()
        self.addCleanup(self.java.stop)
        self.write('target/classes/project/Main.class', 'synthetic main')
        self.write('target/test-classes/project/Test.class', 'synthetic test')
        self.jar = self.root / 'target/dependency.jar'
        with zipfile.ZipFile(self.jar, 'w') as jar:
            jar.writestr('external/Helper.class', 'synthetic dependency')
        self.write(build.BUILD + '/build.log', 'clean test-compile synthetic log')
        self.write(build.BUILD + '/dependency-tree.txt', 'synthetic:dependency:1.0')

    def git(self, *args):
        return subprocess.check_output(['git', '-C', str(self.root), *args], text=True)

    def write(self, relative, text):
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding='utf-8')
        return path

    def manifest(self):
        state = build.source_state(self.root)
        data = {'schema': 'goal-window-build-v1', 'root': str(self.root), **state,
                'jdk': self.jdk, 'jvm_args': [],
                'build_command': build.build_command(self.root), 'maven_version': 'Apache Maven 3.9.16 synthetic',
                'classpath': [str(self.root / 'target/classes'), str(self.root / 'target/test-classes'), str(self.jar)],
                'class_files': build.class_inventory(self.root),
                'dependencies': build.dependency_inventory([self.jar]),
                'build_log_sha256': build.digest(self.root / build.BUILD / 'build.log'),
                'dependency_tree_sha256': build.digest(self.root / build.BUILD / 'dependency-tree.txt')}
        path = self.write(build.MANIFEST, json.dumps(data))
        return path, build.digest(path)

    def test_exact_disk_inventory_passes_then_changed_or_extra_classes_fail(self):
        path, digest = self.manifest()
        self.assertEqual(self.jdk, build.verify_build(path, digest)['jdk'])
        self.write('target/classes/project/Main.class', 'old bytecode')
        with self.assertRaisesRegex(ValueError, 'stale/changed/extra'):
            build.verify_build(path, digest)
        self.write('target/classes/project/Main.class', 'synthetic main')
        self.write('target/test-classes/project/Extra.class', 'extra')
        with self.assertRaisesRegex(ValueError, 'stale/changed/extra'):
            build.verify_build(path, digest)

    def test_main_test_and_dependency_fqn_shadow_are_rejected(self):
        self.write('target/test-classes/project/Main.class', 'shadow')
        with self.assertRaisesRegex(ValueError, 'FQN shadow'):
            build.class_inventory(self.root)
        with zipfile.ZipFile(self.jar, 'w') as jar:
            jar.writestr('xyz/xuminghai/tetris/ai/GoalWindowCandidate.class', 'shadow')
        with self.assertRaisesRegex(ValueError, 'shadows project'):
            build.dependency_inventory([self.jar])

    def test_manifest_source_protocol_config_and_dependency_tampering_fail(self):
        path, digest = self.manifest()
        with self.assertRaisesRegex(ValueError, 'digest mismatch'):
            build.verify_build(path, '0' * 64)
        data = build.read_json(path)
        data['source_sha'] = '0' * 40
        path.write_text(json.dumps(data))
        with self.assertRaisesRegex(ValueError, 'source/protocol/config'):
            build.verify_build(path, build.digest(path))
        path, digest = self.manifest()
        data = build.read_json(path)
        data['build_command'].remove('clean')
        path.write_text(json.dumps(data))
        with self.assertRaisesRegex(ValueError, 'clean build command'):
            build.verify_build(path, build.digest(path))
        path, digest = self.manifest()
        with zipfile.ZipFile(self.jar, 'w') as jar:
            jar.writestr('external/Helper.class', 'changed bytes')
        with self.assertRaisesRegex(ValueError, 'changed dependency'):
            build.verify_build(path, digest)
        self.write(build.CONFIG, '{}')
        self.git('add', '.')
        self.git('commit', '-qm', 'wrong config')
        with self.assertRaisesRegex(ValueError, 'wrong frozen GoalWindow config'):
            build.source_state(self.root)
        self.git('reset', '--hard', 'HEAD~1')
        self.write(build.PROTOCOL, '{}')
        self.git('add', '.')
        self.git('commit', '-qm', 'wrong protocol')
        with self.assertRaisesRegex(ValueError, 'wrong frozen protocol'):
            build.source_state(self.root)

    def test_dirty_or_ignored_source_and_symlinked_classes_fail(self):
        self.write('src/test/java/New.java', 'untracked')
        with self.assertRaisesRegex(ValueError, 'checkout must be clean'):
            build.source_state(self.root)
        (self.root / 'src/test/java/New.java').unlink()
        self.write('src/test/java/Injected.class', 'ignored')
        with self.assertRaisesRegex(ValueError, 'ignored source/build'):
            build.source_state(self.root)
        (self.root / 'target/classes/linked').symlink_to(self.root / 'target/test-classes', target_is_directory=True)
        with self.assertRaisesRegex(ValueError, 'symlink'):
            build.class_inventory(self.root)

    def test_prepare_requires_clean_compile_and_invalidates_old_certificate_on_failure(self):
        old, _ = self.manifest()
        actual_command = build.command
        calls = []

        def execute(root, args):
            if args[0] != './mvnw':
                return actual_command(root, args)
            calls.append(args)
            if args[1:] == ['--version']:
                return 'Apache Maven 3.9.16'
            self.assertEqual(['clean', 'test-compile'], args[3:5])
            self.write(build.BUILD + '/classpath.txt', str(self.jar))
            return 'synthetic clean build; no Maven executed in this test'

        with patch.object(build, 'command', side_effect=execute):
            result = build.prepare(self.root)
        self.assertEqual(build.digest(old), result['manifest_sha256'])
        self.assertEqual(2, len(calls))
        self.write('dirty.java', 'untracked')
        with self.assertRaisesRegex(ValueError, 'checkout must be clean'):
            build.prepare(self.root)
        self.assertFalse(old.exists())

    def test_injected_flags_and_duplicate_json_are_rejected(self):
        with patch.dict(os.environ, {'JAVA_TOOL_OPTIONS': '-javaagent:injected.jar'}):
            with self.assertRaisesRegex(ValueError, 'injected environment'):
                build.clean_environment()
        with self.assertRaisesRegex(ValueError, 'duplicate JSON'):
            build.read_json(self.write('target/duplicate.json', '{"source_sha":"a","source_sha":"b"}'))


if __name__ == '__main__':
    unittest.main()
