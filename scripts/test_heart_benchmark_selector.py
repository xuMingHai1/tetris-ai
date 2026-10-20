"""Exercise the actual workflow shell selector for isolated measurement edits."""
import os
import shutil
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
        for name in ('BenchmarkApplication', 'HeadlessGameRunner', 'ShapeCompletionTelemetry', 'GameBenchmarkResult'):
            paths += [f'src/main/java/xyz/xuminghai/tetris/ai/benchmark/{name}.java',
                      f'src/test/java/xyz/xuminghai/tetris/ai/benchmark/{name}Test.java']
        paths += [str(path).replace('\\', '/') for path in Path('scripts').glob('*goal_window*.py')]
        # Mixed changes must still select just the higher-priority HEART path.
        paths += ['scripts/check_goal_window_session_header.py\n'
                  'src/main/java/xyz/xuminghai/tetris/ai/PreviewRescueActionPlanningAgent.java']
        cases = [(path, 'heart-baseline') for path in paths]
        cases += [('README.md', 'none'), ('scripts/unrelated.py', 'none')]
        for path, expected in cases:
            with self.subTest(path=path), tempfile.TemporaryDirectory() as directory:
                output = Path(directory) / 'output'
                env = dict(os.environ, TEST_CHANGED_FILES=path, GITHUB_OUTPUT=str(output))
                # stdin avoids command-line length limits in Windows Bash launchers.
                result = subprocess.run([shutil.which('bash') or 'bash'], input=shell.encode(), env=env,
                                        stdout=subprocess.PIPE, stderr=subprocess.PIPE)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual(['benchmark=' + expected],
                                 [line for line in output.read_text().splitlines()
                                  if line.startswith('benchmark=')])

    def test_goal_window_discovery_is_required_before_collection(self):
        workflow = Path('.github/workflows/ai-benchmark.yml').read_text()
        step = workflow.split('      - name: Validate fixed HEART protocol and summary tests\n', 1)[1]
        body = step.split('        run: |\n', 1)[1].split('          production_base=', 1)[0]
        shell = textwrap.dedent(body)
        # Execute the actual pre-collection commands using a recording Python stub.
        # A failed GoalWindow suite must stop the shell before any collection.
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            stub = ('python3() { printf "%s\\n" "$*" >> "$DISCOVERY_LOG"; '
                    'case "$*" in *goal_window*) return "$GOAL_EXIT";; esac; };\n')
            for exit_code in ('0', '73'):
                log = root / ('log-' + exit_code)
                env = dict(os.environ, DISCOVERY_LOG=str(log), GOAL_EXIT=exit_code)
                result = subprocess.run([shutil.which('bash') or 'bash'], input=(stub + shell).encode(), env=env,
                                        stdout=subprocess.PIPE, stderr=subprocess.PIPE)
                self.assertEqual(int(exit_code), result.returncode, result.stderr)
                self.assertEqual(['-m unittest discover -s scripts -p test_*heart*.py',
                                  '-m unittest discover -s scripts -p test_*goal_window*.py'],
                                 log.read_text().splitlines())
