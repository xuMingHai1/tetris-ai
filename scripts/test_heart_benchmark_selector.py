"""Exercise the actual workflow shell selector for isolated measurement edits."""
import os
import subprocess
import tempfile
import textwrap
import unittest
from pathlib import Path


class HeartSelectorTest(unittest.TestCase):
    def test_isolated_paths_select_heart(self):
        workflow = Path('.github/workflows/ai-benchmark-pr.yml').read_text()
        body = workflow.split('        run: |\n', 1)[1].split('\n  run-benchmark:', 1)[0]
        # Stop at the next YAML job; execute the workflow's actual shell body.
        lines = []
        for line in body.splitlines():
            if line.strip() and not line.startswith('          '):
                break
            lines.append(line)
        shell = textwrap.dedent('\n'.join(lines))
        shell = shell.replace('changed_files="$(git diff --name-only "$PR_BASE_SHA...$PR_HEAD_SHA")"',
                              'changed_files="$TEST_CHANGED_FILES"')
        paths = [
            'docs/research/heart-acceptance-v1.md',
            'docs/research/heart-acceptance-v1.json',
            'scripts/analyze_heart_baseline.py',
            'scripts/verify_heart_production_base.py',
            'scripts/test_heart_benchmark_selector.py',
            '.github/workflows/ai-benchmark.yml',
            '.github/workflows/ai-benchmark-pr.yml',
        ]
        for name in ('BenchmarkApplication', 'HeadlessGameRunner', 'ShapeCompletionTelemetry'):
            paths += [f'src/main/java/xyz/xuminghai/tetris/ai/benchmark/{name}.java',
                      f'src/test/java/xyz/xuminghai/tetris/ai/benchmark/{name}Test.java']
        for path in paths:
            with self.subTest(path=path), tempfile.NamedTemporaryFile() as output:
                env = dict(os.environ, TEST_CHANGED_FILES=path, GITHUB_OUTPUT=output.name)
                subprocess.run(['bash', '-c', shell], env=env, check=True,
                               stdout=subprocess.PIPE, stderr=subprocess.PIPE)
                self.assertIn('benchmark=heart-baseline', Path(output.name).read_text())
