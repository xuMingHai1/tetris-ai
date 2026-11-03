"""Bounded artificial kernel probes only. No historical fixture or public endpoint."""
import ctypes
import errno
import json
import os
from pathlib import Path
import signal
import socket
import sys
import tempfile
import time
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parent))
import fixed_witness_replay_network as network


class ContractTest(unittest.TestCase):
    def test_status_missing_duplicate_or_wrong_value_is_rejected(self):
        for raw in (b'NoNewPrivs:\t1\n', b'NoNewPrivs: 1\nNoNewPrivs: 1\n',
                    b'NoNewPrivs: true\nSeccomp: 2\nSeccomp_filters: 1\n'):
            from io import BytesIO
            with patch.object(Path, 'open', return_value=BytesIO(raw)), self.assertRaises(ValueError):
                network.status(1)

    def test_forged_mode_or_missing_filter_increment_denies_admission(self):
        for value in ({'NoNewPrivs': 0, 'Seccomp': 2, 'Seccomp_filters': 2},
                      {'NoNewPrivs': 1, 'Seccomp': 0, 'Seccomp_filters': 2},
                      {'NoNewPrivs': 1, 'Seccomp': 2, 'Seccomp_filters': 1}):
            with patch.object(network, 'validate'), patch.object(network, 'status', return_value=value), \
                    self.assertRaisesRegex(ValueError, 'status mismatch'):
                network.observe(123, {'inherited_filters': 1})

    def test_installation_failures_never_exec(self):
        for stage in ('privileges', 'abi', 'attribute', 'syscall', 'rule', 'load'):
            lib = Mock()
            lib.seccomp_init.return_value = 1
            lib.seccomp_arch_exist.side_effect = [0, -errno.EEXIST, -errno.EEXIST]
            lib.seccomp_attr_set.return_value = 0
            lib.seccomp_syscall_resolve_name.side_effect = lambda name: network.DENY[name.decode()]
            lib.seccomp_rule_add.return_value = 0
            lib.seccomp_load.return_value = 0
            libc = Mock()
            libc.prctl.side_effect = [0, 1]
            if stage == 'privileges': libc.prctl.side_effect = [-1]
            if stage == 'abi': lib.seccomp_arch_exist.side_effect = [-1]
            if stage == 'attribute': lib.seccomp_attr_set.return_value = -1
            if stage == 'syscall': lib.seccomp_syscall_resolve_name.side_effect = lambda name: -1
            if stage == 'rule': lib.seccomp_rule_add.return_value = -1
            if stage == 'load': lib.seccomp_load.return_value = -1
            with self.subTest(stage=stage), patch.object(network, 'validate', return_value=lib), \
                    patch.object(network, 'no_extra_fds'), \
                    patch.object(network, 'disable_core'), \
                    patch.object(network, 'status', return_value={'Seccomp_filters': 0}), \
                    patch.object(network.ctypes, 'CDLL', return_value=libc), \
                    patch.object(network.os, 'execv') as execute, self.assertRaises(ValueError):
                network.execute({'inherited_filters': 0})
            execute.assert_not_called()

    def test_network_status_failure_prevents_original_authentication(self):
        import fixed_witness_replay_pipe as pipe
        hook = pipe.VerifyOnlyHandshake(None, None, {}, '.', '', {}, network={'artificial': True})
        with patch.object(network, 'observe', side_effect=ValueError('status drift')), \
                patch.object(pipe, 'authenticate_ready') as authenticate, self.assertRaises(ValueError):
            hook.authenticate(123)
        authenticate.assert_not_called()


@unittest.skipUnless(sys.platform == 'linux', 'actual Linux seccomp required')
class KernelTest(unittest.TestCase):
    """Twelve launched probe leaders plus two controlled forks; no retry.

    This component suite excludes preparation/JVM and cannot certify the full
    design acceptance suite. All probe pipes share one permanent byte budget.
    """
    @classmethod
    def setUpClass(cls):
        from fixed_witness_replay_process import OutputBudget
        if not hasattr(cls, 'budget'):
            cls.budget = OutputBudget(65536)
            cls.deadline = time.monotonic() + 50

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()

    def run_probe(self, mode, *, change=None, seconds=5, budget=None):
        from fixed_witness_replay_process import run_process
        target = [str(Path(sys.executable).resolve()), '-I', '-B', str(Path(__file__).resolve()), mode]
        record = network.chain(target)
        if change:
            change(record)
        # Invalid launcher records are deliberately serialized without parent validation.
        command = [record['python'], '-I', '-B', record['launcher'], network.encoded(record)]
        return run_process(command, self.root, self.root, mode, self.budget if budget is None else budget,
                           seconds=seconds, total_deadline=self.deadline,
                           environment={'PATH': '/usr/bin:/bin', 'LANG': 'C.UTF-8'})

    def test_native_denials_threads_and_descendant(self):
        left, right = socket.socketpair(socket.AF_UNIX)
        left.close()
        right.close()
        result = self.run_probe('native')
        self.assertEqual(('EXITED', 0), (result['state'], result['returncode']), result)
        self.assertIn('ALL_NATIVE_EPERM_THREAD_FORK_OK', (self.root / 'native-stdout.log').read_text())

    def test_x32_cannot_bypass_filter(self):
        result = self.run_probe('x32')
        self.assertEqual(-signal.SIGSYS, result['returncode'], result)

    def test_compat_cannot_bypass_filter(self):
        result = self.run_probe('compat')
        self.assertEqual(-signal.SIGSYS, result['returncode'], result)

    def test_tampered_policy_never_executes_marker(self):
        result = self.run_probe('marker', change=lambda r: r.update(policy_sha256='0'*64))
        self.assertEqual(78, result['returncode'], result)
        self.assertFalse((self.root / 'marker').exists())

    def test_missing_library_never_executes_marker(self):
        result = self.run_probe('missing', change=lambda r: r['library'].update(path='/no-such-libseccomp'))
        self.assertEqual(78, result['returncode'], result)
        self.assertFalse((self.root / 'marker').exists())

    def test_wrong_library_and_hash_never_execute_marker(self):
        def wrong_library(record):
            # A real canonical file with a matching digest, but not libseccomp.
            record['library'].update(path=record['python'], sha256=record['python_sha256'])
        for mode, change in (
                ('wrong-library', wrong_library),
                ('wrong-hash', lambda r: r['library'].update(sha256='0' * 64))):
            with self.subTest(mode=mode):
                result = self.run_probe(mode, change=change)
                self.assertEqual(78, result['returncode'], result)
                self.assertFalse((self.root / 'marker').exists())

    def test_isolated_leader_exit_cleans_pipe_holding_descendant(self):
        from test_fixed_witness_replay_process import stopped_process
        result = self.run_probe('leader-exit', seconds=1)
        self.assertEqual('TIMEOUT', result['state'], result)
        pid = int((self.root / 'leader-exit-stdout.log').read_text())
        deadline = min(self.deadline, time.monotonic() + 1)
        while not stopped_process(pid) and time.monotonic() < deadline:
            time.sleep(.01)
        self.assertTrue(stopped_process(pid), 'isolated descendant still running')

    def test_isolated_output_limit_retains_exact_prefix_and_reservation(self):
        from fixed_witness_replay_process import OutputBudget
        child = self.budget.quota.reserve(256)
        budget = OutputBudget(256, quota=child)
        charged = self.budget.quota.charged
        result = self.run_probe('quota', budget=budget)
        self.assertEqual('OUTPUT_LIMIT', result['state'], result)
        self.assertEqual(b'x' * 256, (self.root / 'quota-stdout.log').read_bytes())
        self.assertEqual(b'', (self.root / 'quota-stderr.log').read_bytes())
        self.assertEqual(256, budget.written)
        self.assertEqual(charged, self.budget.quota.charged)
        with self.assertRaises(ProcessLookupError): os.kill(result['pid'], 0)
        # An exhausted child cannot launch again or refund its permanent reservation.
        with self.assertRaisesRegex(ValueError, 'budget exhausted before launch'):
            self.run_probe('quota-again', budget=budget)
        self.assertFalse((self.root / 'quota-again-stdout.log').exists())
        self.assertEqual(charged, self.budget.quota.charged)

    def test_timeout_and_exec_failure_preserve_supervision(self):
        result = self.run_probe('timeout', seconds=.2)
        self.assertEqual('TIMEOUT', result['state'], result)
        with self.assertRaises(ProcessLookupError): os.kill(result['pid'], 0)
        result = self.run_probe('exec-failure', change=lambda r: r.update(command=['/no-such-java']))
        self.assertEqual(78, result['returncode'], result)

    def test_extra_open_descriptor_never_executes_marker(self):
        from fixed_witness_replay_process import run_process
        record = network.chain([str(Path(sys.executable).resolve()), '-c',
                                "from pathlib import Path; Path('marker').touch()"])
        code = ("import os,sys,json; sys.path.insert(0,sys.argv[1]); "
                "import fixed_witness_replay_network as n; "
                "fd=os.open('/dev/null',os.O_RDONLY); n.execute(json.loads(sys.argv[2]))")
        result = run_process([record['python'], '-I', '-B', '-c', code,
                              str(Path(network.__file__).parent), network.encoded(record)],
                             self.root, self.root, 'extra-fd', self.budget, seconds=5,
                             total_deadline=self.deadline, environment={'PATH': '/usr/bin:/bin'})
        self.assertEqual(1, result['returncode'], result)
        self.assertFalse((self.root / 'marker').exists())


def component_suite():
    """Single bounded probe suite; deliberately no clean build or JVM launch."""
    from fixed_witness_replay_process import OutputBudget
    KernelTest.budget = OutputBudget(65536)
    KernelTest.deadline = time.monotonic() + 50
    class RetainedOutput:
        def write(self, text):
            if KernelTest.budget.consume(text.encode('utf-8'), sys.stdout.buffer):
                raise ValueError('component suite output exhausted')
        def flush(self):
            sys.stdout.flush()
    result = unittest.TextTestRunner(stream=RetainedOutput(), verbosity=2).run(
        unittest.defaultTestLoader.loadTestsFromModule(sys.modules[__name__]))
    require = network.require
    require(result.wasSuccessful() and not result.skipped, 'component suite did not pass on Linux')


def probe(mode):
    libc = ctypes.CDLL(None, use_errno=True)
    libc.syscall.restype = ctypes.c_long

    def denied():
        for number in network.DENY.values():
            ctypes.set_errno(0)
            result = libc.syscall(ctypes.c_long(number), *([ctypes.c_long(-1)] * 6))
            assert result == -1 and ctypes.get_errno() == errno.EPERM, (number, result, ctypes.get_errno())
        for family in (socket.AF_UNIX, socket.AF_INET, socket.AF_INET6, socket.AF_NETLINK):
            try:
                socket.socket(family, socket.SOCK_STREAM)
            except OSError as error:
                assert error.errno == errno.EPERM, error
            else:
                raise AssertionError('socket unexpectedly opened')

    if mode == 'native':
        import threading
        denied()
        errors = []
        def thread():
            try: denied()
            except BaseException as error: errors.append(str(error))
        worker = threading.Thread(target=thread)
        worker.start()
        worker.join(timeout=1)
        assert not worker.is_alive() and not errors, errors
        child = os.fork()
        if child == 0:
            try:
                denied()
                # A second ALLOW filter cannot undo the inherited deny rules.
                lib = network.library(network.binding()['path'])
                context = lib.seccomp_init(0x7fff0000)
                assert context and lib.seccomp_load(context) == 0
                lib.seccomp_release(context)
                denied()
                assert libc.prctl(38, 0, 0, 0, 0) == -1
                os._exit(0)
            except BaseException:
                os._exit(1)
        assert os.waitpid(child, 0)[1] == 0
        print('ALL_NATIVE_EPERM_THREAD_FORK_OK')
    elif mode == 'x32':
        libc.syscall(ctypes.c_long(0x40000029), 2, 1, 0)
        raise AssertionError('x32 was not killed')
    elif mode == 'compat':
        import mmap
        code = mmap.mmap(-1, 4096, prot=mmap.PROT_READ | mmap.PROT_WRITE | mmap.PROT_EXEC)
        code.write(b'\xb8\x66\x00\x00\x00\xcd\x80\xc3')  # i386 socketcall via int 0x80
        ctypes.CFUNCTYPE(ctypes.c_long)(ctypes.addressof(ctypes.c_char.from_buffer(code)))()
        raise AssertionError('compat ABI was not killed')
    elif mode == 'timeout':
        time.sleep(30)
    elif mode == 'leader-exit':
        child = os.fork()
        if child == 0:
            time.sleep(30)
            os._exit(0)
        os.write(1, str(child).encode('ascii'))
        os._exit(0)
    elif mode == 'quota':
        os.write(1, b'x' * 1024)
        time.sleep(30)
    else:
        Path('marker').write_text('EXECUTED')


if __name__ == '__main__':
    if len(sys.argv) == 2 and sys.argv[1] in ('native', 'x32', 'compat', 'marker', 'missing', 'timeout',
                                           'wrong-library', 'wrong-hash', 'leader-exit', 'quota'):
        probe(sys.argv[1])
    else:
        unittest.main()
