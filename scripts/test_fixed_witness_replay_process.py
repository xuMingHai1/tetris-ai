"""Artificial subprocesses only: no Java, historical CSV, seed or search."""
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

from fixed_witness_replay_process import OutputBudget, run_process


def stopped_process(pid):
    # Read once: init may reap a killed orphan between exists() and read_text().
    try:
        state = (Path('/proc') / str(pid) / 'stat').read_text()
    except FileNotFoundError:
        return True
    return state.rsplit(') ', 1)[1].startswith('Z')


class BudgetTest(unittest.TestCase):
    def test_proc_reap_race_and_live_or_zombie_state(self):
        with patch.object(Path, 'read_text', side_effect=FileNotFoundError):
            self.assertTrue(stopped_process(123))
        for state, expected in [('123 (child) S rest', False), ('123 (child) Z rest', True)]:
            with patch.object(Path, 'read_text', return_value=state):
                self.assertEqual(stopped_process(123), expected)

    def test_reject_invalid_budget(self):
        for limit in (0, -1, True, 10 * 1024 * 1024 + 1):
            with self.subTest(limit=limit), self.assertRaises(ValueError):
                OutputBudget(limit)

    def test_combined_prefix_cap(self):
        from io import BytesIO
        budget = OutputBudget(5)
        first, second = BytesIO(), BytesIO()
        self.assertFalse(budget.consume(b'abc', first))
        self.assertTrue(budget.consume(b'defg', second))
        self.assertEqual((first.getvalue(), second.getvalue(), budget.written, budget.observed),
                         (b'abc', b'de', 5, 7))


@unittest.skipUnless(sys.platform == 'linux', 'real Linux process groups required')
class ProcessTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def run_child(self, code, *, name='child', budget=None, seconds=3, total=None):
        return run_process([sys.executable, '-c', code], self.root, self.root, name,
                           budget if budget is not None else OutputBudget(), seconds=seconds,
                           total_deadline=total if total is not None else time.monotonic() + 10)

    def test_exit_and_binary_partial_prefix(self):
        result = self.run_child("import os; os.write(1,b'partial\\xff'); os.write(2,b'error'); raise SystemExit(7)")
        self.assertEqual((result['state'], result['returncode']), ('EXITED', 7))
        self.assertEqual((self.root / 'child-stdout.log').read_bytes(), b'partial\xff')
        self.assertEqual((self.root / 'child-stderr.log').read_bytes(), b'error')

    def test_timeout_retains_prefix(self):
        result = self.run_child("import os,time; os.write(1,b'prefix'); time.sleep(30)", seconds=.3)
        self.assertEqual(result['state'], 'TIMEOUT')
        self.assertLess(result['elapsed_seconds'], 3)
        self.assertEqual((self.root / 'child-stdout.log').read_bytes(), b'prefix')

    def test_combined_flood_is_bounded_and_shared_between_stages(self):
        budget = OutputBudget(256)
        first = self.run_child("import os; os.write(2,b'x'*64)", budget=budget, name='first')
        self.assertEqual(first['state'], 'EXITED')
        result = self.run_child("import os; os.write(1,b'a'*1024); os.write(2,b'b'*1024)", budget=budget)
        self.assertEqual(result['state'], 'OUTPUT_LIMIT')
        self.assertEqual(sum(p.stat().st_size for p in self.root.glob('*.log')), 256)
        self.assertGreater(budget.observed, budget.written)
        with self.assertRaises(ValueError):
            self.run_child("raise Exception('must not launch')", budget=budget, name='third')
        self.assertFalse((self.root / 'third-stdout.log').exists())

    def test_absolute_deadline_and_invalid_durations(self):
        for seconds in (0, -1, float('nan'), float('inf'), True, 601):
            with self.subTest(seconds=seconds), self.assertRaises(ValueError):
                self.run_child('pass', seconds=seconds)
        with self.assertRaises(ValueError):
            self.run_child('pass', total=time.monotonic() - 1)
        self.assertEqual(list(self.root.iterdir()), [])
        result = self.run_child('import time; time.sleep(30)', total=time.monotonic() + .2)
        self.assertEqual(result['state'], 'TIMEOUT')
        self.assertLess(result['elapsed_seconds'], 3)

    def test_exclusive_logs_and_launch_failure(self):
        self.run_child('pass')
        before = (self.root / 'child-stdout.log').read_bytes()
        with self.assertRaises(FileExistsError):
            self.run_child("print('overwrite')")
        self.assertEqual((self.root / 'child-stdout.log').read_bytes(), before)
        result = run_process(['/nonexistent-fixed-witness-test-executable'], self.root, self.root,
                             'bad', OutputBudget(), seconds=1, total_deadline=time.monotonic() + 2)
        self.assertEqual(result['state'], 'PROCESS_ERROR')
        self.assertIsNone(result['pid'])

    def test_leader_exit_does_not_orphan_pipe_holding_child(self):
        code = """import os,signal,time
pid=os.fork()
if pid == 0:
    signal.signal(signal.SIGTERM,signal.SIG_IGN)
    time.sleep(30)
else:
    os.write(1,str(pid).encode())
"""
        result = self.run_child(code, seconds=.3)
        self.assertEqual(result['state'], 'TIMEOUT')
        pid = int((self.root / 'child-stdout.log').read_text())
        # A killed orphan can remain a zombie until init reaps it; neither is running.
        for _ in range(100):
            if stopped_process(pid):
                break
            time.sleep(.01)
        else:
            self.fail('descendant still running after cleanup')

    def test_repeated_parent_sigterm_preserves_logs_and_kills_child(self):
        wrapper = self.root / 'wrapper.py'
        scripts = str(Path(__file__).resolve().parent)
        wrapper.write_text('import sys,time\nsys.path.insert(0,' + repr(scripts) + ')\n'
                           'from fixed_witness_replay_process import *\n'
                           'real_killpg=os.killpg\n'
                           'def repeated_cancel(pid,sig):\n'
                           '    os.kill(os.getpid(),signal.SIGTERM)\n'
                           '    os.kill(os.getpid(),signal.SIGINT)\n'
                           '    real_killpg(pid,sig)\n'
                           'os.killpg=repeated_cancel\n'
                           'r=run_process([sys.executable,"-c","import os,time; print(os.getpid(),flush=True); time.sleep(30)"],'
                           + repr(str(self.root)) + ',' + repr(str(self.root)) + ',"signal",OutputBudget(),'
                           'seconds=10,total_deadline=time.monotonic()+15)\n'
                           'print(r["state"],flush=True)\n', encoding='utf-8')
        parent = subprocess.Popen([sys.executable, str(wrapper)], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        try:
            log = self.root / 'signal-stdout.log'
            for _ in range(300):
                if log.exists() and log.stat().st_size:
                    break
                if parent.poll() is not None:
                    self.fail('supervisor exited before signal')
                time.sleep(.01)
            else:
                self.fail('child never started')
            pid = int(log.read_text())
            parent.send_signal(signal.SIGTERM)
            stdout, stderr = parent.communicate(timeout=5)
            self.assertEqual((parent.returncode, stdout.strip(), stderr), (0, b'INTERRUPTED', b''))
            with self.assertRaises(ProcessLookupError):
                os.kill(pid, 0)
        finally:
            if parent.poll() is None:
                parent.kill()
            parent.communicate()


if __name__ == '__main__':
    unittest.main()
