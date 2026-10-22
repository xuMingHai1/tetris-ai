"""Execute workflow boundary/driver/gate commands without starting a JVM or population."""
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import textwrap
import unittest


WORKFLOW = Path('.github/workflows/ai-benchmark.yml')
PR_WORKFLOW = Path('.github/workflows/ai-benchmark-pr.yml')


def step_shell(workflow, name):
    step = workflow.split('      - name: ' + name + '\n', 1)[1].split('\n      - name:', 1)[0]
    body = step.split('        run: |\n', 1)[1]
    return textwrap.dedent(body)


def run_shell(shell, **environment):
    return subprocess.run([shutil.which('bash') or 'bash'], input=shell.encode(),
                          env=dict(os.environ, **environment), stdout=subprocess.PIPE, stderr=subprocess.PIPE)


class GoalWindowWorkflowTest(unittest.TestCase):
    def test_collection_requires_explicit_default_branch_dispatch(self):
        shell = step_shell(WORKFLOW.read_text(), 'Validate GoalWindow collection boundary')
        for event, ref, collect, success in (
                ('pull_request', 'refs/pull/137/merge', 'false', True),
                ('pull_request', 'refs/pull/137/merge', 'true', False),
                ('workflow_dispatch', 'refs/heads/topic', 'true', False),
                ('workflow_dispatch', 'refs/heads/trunk', 'true', True),
                ('push', 'refs/heads/trunk', 'true', False)):
            with self.subTest(event=event, ref=ref, collect=collect):
                result = run_shell(shell, EVENT_NAME=event, REF=ref, COLLECT=collect, DEFAULT_BRANCH='trunk')
                self.assertEqual(success, result.returncode == 0, result.stderr)

    def test_driver_default_and_collection_are_single_calls_and_preserve_exit(self):
        shell = step_shell(WORKFLOW.read_text(), 'Run supervised GoalWindow session')
        with tempfile.TemporaryDirectory() as directory:
            for collect in ('false', 'true'):
                for exit_code in ('0', '1', '2', '130'):
                    log = Path(directory) / (collect + exit_code)
                    stub = 'python3() { printf "%s\\n" "$*" >> "$CALL_LOG"; return "$DRIVER_EXIT"; };\n'
                    result = run_shell(stub + shell, COLLECT=collect, CALL_LOG=str(log), DRIVER_EXIT=exit_code,
                                       GITHUB_WORKSPACE='/checkout', RUNNER_TEMP='/evidence')
                    self.assertEqual(int(exit_code), result.returncode, result.stderr)
                    expected = ('scripts/drive_goal_window.py --root /checkout '
                                '--output /evidence/goal-window-session' + (' --collect' if collect == 'true' else ''))
                    self.assertEqual([expected], log.read_text().splitlines())

    def test_gate_requires_paired_success(self):
        shell = step_shell(PR_WORKFLOW.read_text(), 'Require selected benchmark')
        for selected, result_name in (('heart-terminal-paired', 'GOAL_WINDOW_RESULT'),
                                      ('heart-baseline', 'HEART_RESULT')):
            for status in ('success', 'failure', 'cancelled', 'skipped', ''):
                result = run_shell(shell, SELECT_RESULT='success', SELECTED=selected, REASON='test',
                                   **{result_name: status})
                self.assertEqual(status == 'success', result.returncode == 0, result.stderr)

    def test_pr_scope_concurrency_and_only_one_selected_job(self):
        workflow = PR_WORKFLOW.read_text()
        select = workflow.split('  select-benchmark:', 1)[1].split('    runs-on:', 1)[0]
        gate = workflow.split('  benchmark-gate:', 1)[1].split('    needs:', 1)[0]
        for body in (select, gate):
            self.assertIn('github.event.pull_request.head.repo.full_name == github.repository', body)
            self.assertIn('github.event.pull_request.base.ref == github.event.repository.default_branch', body)
        self.assertIn('group: ai-benchmark-pr-${{ github.event.pull_request.number }}\n  cancel-in-progress: true', workflow)
        paired = workflow.split('  heart-terminal-paired:\n', 1)[1].split('\n  heart-baseline:', 1)[0]
        self.assertIn("if: ${{ needs.select-benchmark.outputs.benchmark == 'heart-terminal-paired' }}", paired)
        self.assertIn('goal_window_collect: false', paired)
        self.assertNotIn('secrets:', paired)
        self.assertIn('      - heart-terminal-paired\n', workflow.split('  benchmark-gate:', 1)[1])

    def test_fixed_protocol_and_both_suites_precede_driver(self):
        workflow = WORKFLOW.read_text()
        validate = step_shell(workflow, 'Validate fixed HEART protocol and summary tests')
        self.assertIn('test_*heart*.py', validate)
        self.assertIn('test_*goal_window*.py', validate)
        self.assertIn('git fetch --no-tags origin "$production_base"', validate)
        self.assertIn('python3 scripts/verify_heart_production_base.py', validate)
        self.assertIn("if actual != expected", validate)
        self.assertLess(workflow.index('Validate fixed HEART'), workflow.index('Run supervised GoalWindow'))
        # A failed driver skips ordinary CSV handling but always retains its external
        # report/log/raw/bundle directory, including INCOMPLETE and failure prefixes.
        upload = workflow.split('      - name: Upload GoalWindow session evidence\n', 1)[1]
        self.assertIn("if: ${{ always() && inputs.agent == 'heart-terminal-paired' }}", upload)
        self.assertIn('path: ${{ runner.temp }}/goal-window-session/', upload)
        for name in ('Collect benchmark results', 'Publish benchmark summary'):
            step = workflow.split('      - name: ' + name + '\n', 1)[1].split('\n      - name:', 1)[0]
            self.assertIn("if: ${{ inputs.agent != 'heart-terminal-paired' }}", step)

    def test_population_parameters_cannot_override_frozen_protocol(self):
        shell = step_shell(WORKFLOW.read_text(), 'Validate fixed HEART protocol and summary tests')
        code = shell.split("python3 - <<'PY'\n", 1)[1].split('\nPY', 1)[0]
        with tempfile.TemporaryDirectory() as directory:
            for games, pieces, seed, success in (('80', '1000', '20000', True),
                                                ('1', '1000', '20000', False),
                                                ('80', '999', '20000', False),
                                                ('80', '1000', '30000', False)):
                output = Path(directory) / (games + pieces + seed)
                result = subprocess.run([sys.executable, '-c', code],
                                        env=dict(os.environ, GAMES=games, MAX_PIECES=pieces,
                                                 SEED=seed, GITHUB_ENV=str(output)),
                                        stdout=subprocess.PIPE, stderr=subprocess.PIPE)
                self.assertEqual(success, result.returncode == 0, result.stderr)
                self.assertEqual(success, output.exists())


if __name__ == '__main__':
    unittest.main()
