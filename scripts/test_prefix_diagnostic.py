"""Supervisor failure classification, without consuming a registered population."""
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import Mock, patch

from run_prefix_diagnostic import run_population


@patch('run_prefix_diagnostic.platform.platform', return_value='test host')
class SupervisorTest(unittest.TestCase):
    def test_timeout_kills_child_stops_population_and_preserves_incomplete(self, _platform):
        child = Mock()
        child.wait.side_effect = [subprocess.TimeoutExpired('java', 60), 0]
        with tempfile.TemporaryDirectory() as directory:
            with patch('run_prefix_diagnostic.subprocess.Popen', return_value=child) as popen:
                with patch('run_prefix_diagnostic.subprocess.run', return_value=Mock(stderr='test JVM')):
                    self.assertEqual(2, run_population(['java'], Path(directory), 'test-sha'))
            child.kill.assert_called_once()
            popen.assert_called_once()
            summary = json.loads((Path(directory) / 'prefix-summary.json').read_text())
            self.assertEqual('INCOMPLETE', summary['results'][0]['status'])
            self.assertEqual(19, len(summary['unrun']))
            self.assertFalse(summary['complete'])
            self.assertFalse(summary['runtime_permission'])

    def test_successful_exit_without_terminal_evidence_is_error(self, _platform):
        child = Mock()
        child.wait.return_value = 0
        with tempfile.TemporaryDirectory() as directory:
            with patch('run_prefix_diagnostic.subprocess.Popen', return_value=child):
                with patch('run_prefix_diagnostic.subprocess.run', return_value=Mock(stderr='test JVM')):
                    self.assertEqual(2, run_population(['java'], Path(directory), 'test-sha'))
            summary = json.loads((Path(directory) / 'prefix-summary.json').read_text())
            self.assertEqual('ERROR', summary['results'][0]['status'])


if __name__ == '__main__':
    unittest.main()
