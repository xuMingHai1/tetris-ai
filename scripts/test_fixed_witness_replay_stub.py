"""Synthetic only: never fork, load libc, stop a task or exec a command."""
import ctypes
import unittest
from unittest.mock import Mock, call, patch

from fixed_witness_replay_ptrace import PtraceAccess, PtraceBoundaryError
from fixed_witness_replay_stub import CoordinatorStub


class ChildExited(BaseException):
    pass


class StubTest(unittest.TestCase):
    def setUp(self):
        self.system = Mock(spec=['getpid', 'getppid', 'kill', 'chdir', 'execve', '_exit'])
        self.system.getpid.return_value = 11
        self.system.getppid.return_value = 10
        self.system._exit.side_effect = ChildExited
        self.access = Mock(spec=PtraceAccess)
        self.events = Mock()
        self.events.attach_mock(self.access, 'wire')
        self.events.attach_mock(self.system, 'os')
        self.argv = ['/trusted/python', '-B', '/trusted/coordinator.py']
        self.env = {'HOME': '/build/home', 'TMPDIR': '/build/tmp'}
        self.stub = self.make()

    def make(self, **changes):
        values = dict(command=self.argv, cwd='/build', environment=self.env,
                      parent_pid=10, access=self.access, system=self.system)
        values.update(changes)
        return CoordinatorStub(**values)

    def run_child(self):
        with self.assertRaises(ChildExited):
            self.stub.run()
        self.system._exit.assert_called_with(125)

    def test_exact_order_and_returned_exec_fails_without_retry(self):
        self.run_child()
        self.assertEqual([
            call.os.getpid(), call.os.getppid(), call.wire.trace_me(11),
            call.os.kill(11, 19), call.os.getppid(), call.os.chdir(b'/build'),
            call.os.execve(b'/trusted/python',
                          (b'/trusted/python', b'-B', b'/trusted/coordinator.py'),
                          {b'HOME': b'/build/home', b'TMPDIR': b'/build/tmp'}),
            call.os._exit(125)], self.events.mock_calls)

    def test_inputs_are_copied_before_fork(self):
        self.argv[0] = '/untrusted/changed'
        self.env['INJECTED'] = 'yes'
        self.run_child()
        args = self.system.execve.call_args.args
        self.assertEqual(b'/trusted/python', args[0])
        self.assertNotIn(b'INJECTED', args[2])

    def test_empty_environment_is_explicit_not_inherited(self):
        self.stub = self.make(environment={})
        self.run_child()
        self.assertEqual({}, self.system.execve.call_args.args[2])

    def test_invalid_inputs_reject_before_child_operations(self):
        cases = [dict(command=[]), dict(command='sh'), dict(command=['python']),
                 dict(command=['/bin/sh', '']), dict(command=['/x', 'a\0b']),
                 dict(cwd='relative'), dict(cwd='/a\0b'), dict(environment=None),
                 dict(environment={'A=B': 'x'}), dict(environment={'': 'x'}),
                 dict(environment={'X': 'a\0b'}), dict(environment={'X': 3}),
                 dict(parent_pid=True), dict(parent_pid=0), dict(parent_pid=2**31)]
        for values in cases:
            with self.subTest(values=values), self.assertRaises(ValueError):
                self.make(**values)
        self.assertEqual([], self.events.mock_calls)

    def test_wrong_child_or_parent_never_traces_or_executes(self):
        for pid, ppid in ((10, 1), (11, 1), (True, 10), (0, 10), (2**31, 10)):
            self.setUp()
            self.system.getpid.return_value = pid
            self.system.getppid.return_value = ppid
            self.run_child()
            self.access.trace_me.assert_not_called()
            self.system.kill.assert_not_called()
            self.system.execve.assert_not_called()

    def test_traceme_failure_never_stops_or_executes(self):
        self.access.trace_me.side_effect = PtraceBoundaryError('denied')
        self.run_child()
        self.system.kill.assert_not_called()
        self.system.execve.assert_not_called()

    def test_signal_failure_never_executes(self):
        self.system.kill.side_effect = OSError('denied')
        self.run_child()
        self.system.chdir.assert_not_called()
        self.system.execve.assert_not_called()

    def test_parent_changes_after_resume_never_executes(self):
        self.system.getppid.side_effect = (10, 1)
        self.run_child()
        self.access.trace_me.assert_called_once_with(11)
        self.system.chdir.assert_not_called()
        self.system.execve.assert_not_called()

    def test_chdir_exec_and_base_exception_fail_via_exit(self):
        for method, error in (('chdir', OSError('failure')), ('execve', OSError('failure')),
                              ('kill', KeyboardInterrupt()), ('kill', SystemExit(0))):
            self.setUp()
            getattr(self.system, method).side_effect = error
            self.run_child()
            if method != 'execve':
                self.system.execve.assert_not_called()

    def test_duplicate_run_never_retries_any_child_operation(self):
        self.run_child()
        calls = list(self.events.mock_calls)
        self.run_child()
        self.assertEqual(calls + [call.os._exit(125)], self.events.mock_calls)

    def test_real_wire_traceme_uses_only_request_zero_and_rejects_errno(self):
        requests = []
        def kernel(request, tid, address, data):
            requests.append((request, tid, address, data))
            return 0
        PtraceAccess(kernel).trace_me(11)
        self.assertEqual([(0, 11, 0, 0)], requests)
        def denied(*args):
            ctypes.set_errno(1)
            return -1
        with self.assertRaisesRegex(PtraceBoundaryError, 'errno 1'):
            PtraceAccess(denied).trace_me(11)
        with self.assertRaisesRegex(PtraceBoundaryError, 'TRACEME response'):
            PtraceAccess(lambda *args: 1).trace_me(11)

    def test_host_factory_only_prepares_transport_never_runs_child(self):
        with patch('fixed_witness_replay_stub.PtraceAccess.for_host', return_value=self.access):
            stub = CoordinatorStub.for_host(self.argv, '/build', self.env, 10)
        self.assertFalse(stub.attempted)
        self.assertEqual([], self.access.mock_calls)


if __name__ == '__main__':
    unittest.main()
