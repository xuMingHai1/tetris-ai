import json
import subprocess
import tempfile
import unittest
from pathlib import Path
from verify_heart_production_base import verify


class ProductionBaseTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.git('init', '-q')
        self.git('config', 'user.email', 'test@example.invalid')
        self.git('config', 'user.name', 'Test')
        self.write('src/main/java/xyz/xuminghai/tetris/ai/Planner.java', 'original')
        self.write('pom.xml', 'original')
        self.git('add', '.')
        self.git('commit', '-qm', 'base')
        self.base = self.git('rev-parse', 'HEAD').strip()
        self.protocol = self.root / '.git' / 'protocol.json'
        self.protocol.write_text(json.dumps({'production_base_sha': self.base}))

    def git(self, *args):
        return subprocess.check_output(['git', '-C', str(self.root), *args]).decode()

    def write(self, path, content):
        target = self.root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(content)

    def test_measurement_and_documentation_are_allowed(self):
        self.write('src/main/java/xyz/xuminghai/tetris/ai/benchmark/HeadlessGameRunner.java', 'measurement')
        self.write('docs/note.md', 'docs')
        self.git('add', '.')
        self.git('commit', '-qm', 'measurement')
        result = verify(self.root, self.protocol)
        self.assertTrue(result['production_diff_verified'])
        self.assertEqual(self.base, result['production_base_sha'])
        self.assertNotEqual(self.base, result['measured_source_sha'])

    def test_production_changes_additions_deletions_and_build_drift_fail(self):
        for path in ('src/main/java/xyz/xuminghai/tetris/ai/Planner.java',
                     'src/main/java/xyz/xuminghai/tetris/core/NewRule.java',
                     'src/main/resources/settings.properties', 'pom.xml', '.mvn/jvm.config'):
            with self.subTest(path=path):
                self.write(path, 'changed')
                self.git('add', '.')
                with self.assertRaisesRegex(ValueError, 'production differs'):
                    verify(self.root, self.protocol)
                self.git('reset', '--hard', self.base)
        (self.root / 'src/main/java/xyz/xuminghai/tetris/ai/Planner.java').unlink()
        with self.assertRaisesRegex(ValueError, 'production differs'):
            verify(self.root, self.protocol)

    def test_invalid_or_missing_commit_fails(self):
        for sha in ('main', 'f' * 40):
            self.protocol.write_text(json.dumps({'production_base_sha': sha}))
            with self.assertRaises((ValueError, subprocess.CalledProcessError)):
                verify(self.root, self.protocol)
