"""Frozen launcher -> probe -> ENTRY -> real-shaped reap; entirely fake I/O."""
import hashlib
import unittest
from unittest.mock import Mock

from fixed_witness_replay_abi_observation import AbiObservationError
from fixed_witness_replay_exec_observation import OwnedExecObservations
from fixed_witness_replay_network import encoded, policy_hash
from fixed_witness_replay_probe_launch import ProbeLaunch
from fixed_witness_replay_ptrace import decode_syscall_info, SignalInfo
from fixed_witness_replay_stop_dispatch import PostHandshakeDispatcher
from fixed_witness_replay_wait_owner import BoundedWaitOwner
import test_fixed_witness_replay_exec_binding as fixture
from test_fixed_witness_replay_abi_observation import metadata
from test_fixed_witness_replay_ptrace import entry, exit_info


class FilterExecChainTest(unittest.TestCase):
    def setup_chain(self, mode='x32', thread=False):
        self.f = fixture.OwnedExecBindingTest()
        self.f.setup_binding(mode=mode, thread=thread)
        self.record, self.other = self.f.record, self.f.other
        self.files, self.wire = self.f.f.files, self.f.f.wire
        self.birth = self.f.f.birth
        self.net = {
            'schema': 'fixed-witness-network-v1', 'python': self.record.interpreter,
            'python_sha256': self.record.interpreter_sha256,
            'launcher': '/frozen/network.py',
            'launcher_sha256': hashlib.sha256(b'fake launcher').hexdigest(),
            'library': {'path': '/frozen/libseccomp.so.2',
                        'sha256': hashlib.sha256(b'fake library').hexdigest(), 'version': [2, 6, 0]},
            'policy_sha256': policy_hash(), 'command': list(self.record.argv), 'inherited_filters': 1,
        }
        self.plan = ProbeLaunch(self.record, encoded(self.net))
        other_net = dict(self.net, command=list(self.other.argv))
        self.config = OwnedExecObservations(
            ((self.birth, self.record), (99, self.other)), files=self.files, bind_abi=True,
            network_launches=((self.birth, self.plan), (99, ProbeLaunch(self.other, encoded(other_net)))))
        self.launch = self.f.f.launch
        self.launch._post_handshake_claimed = False
        self.dispatch = PostHandshakeDispatcher(self.launch, exec_observations=self.config)
        self.wait = Mock(return_value=(11, 0x4057f))
        self.owner = BoundedWaitOwner(self.dispatch, waitpid=self.wait, sleep=Mock())
        for path, data in ((self.net['launcher'], b'fake launcher'),
                           (self.net['library']['path'], b'fake library')):
            self.files.data[path] = data
            self.files.meta[path] = metadata(len(data), inode=10)
            self.files.canon[path] = path
        self.files.data['/proc/11/cmdline'] = b'\0'.join(a.encode() for a in self.plan.argv()) + b'\0'
        self.status(0, 1)

    def setUp(self):
        self.setup_chain()

    def status(self, nnp, filters):
        self.files.data['/proc/11/status'] = (
            b'State:\tt (tracing stop)\nPid:\t11\nTgid:\t11\nTracerPid:\t10\n'
            + f'NoNewPrivs:\t{nnp}\nSeccomp:\t2\nSeccomp_filters:\t{filters}\n'.encode())

    def step(self):
        return self.owner._round()

    def syscall(self, raw):
        self.wire.syscall_info.return_value = decode_syscall_info(*raw)
        self.wait.return_value = (11, 0x857f)
        return self.step()

    def prepare_probe(self):
        self.step()
        self.assertEqual('UNBOUND', self.config.abi_state(self.record.mode))
        self.syscall(exit_info(0, 0))
        self.syscall(entry(59))
        self.wire.reset_mock()
        self.wire.event_tid.return_value = 11
        self.wait.return_value = (11, 0x4057f)
        self.files.data['/proc/11/cmdline'] = b'\0'.join(a.encode() for a in self.record.argv) + b'\0'
        self.status(1, 2)

    def reject_probe(self):
        with self.assertRaises(ValueError):
            self.step()
        self.assertIsNotNone(self.config.stopped)
        self.assertIsNotNone(self.launch.stopped)
        self.wire.resume_syscall.assert_not_called()
        self.assertEqual('UNBOUND', self.config.abi_state(self.record.mode))
        self.assertTrue(self.launch.ledger.identity(self.birth).alive)
        self.assertFalse(self.files.fds)

    def test_complete_both_modes_thread_birth_delivery_and_original_reap(self):
        for mode in ('x32', 'compat'):
            for thread in (False, True):
                for delivery in (False, True):
                    self.setup_chain(mode, thread)
                    before = self.launch.ledger.totals
                    self.prepare_probe()
                    self.step()
                    self.assertEqual('BOUND', self.config.abi_state(mode))
                    observed, = self.config.observations
                    self.assertEqual(2, observed.identity.generation)
                    self.assertEqual(self.birth, observed.identity.birth)
                    live = self.launch.ledger.totals.live
                    self.syscall(exit_info(0, 0))
                    arch, number = ((0xc000003e, 0x40000029) if mode == 'x32' else (0x40000003, 102))
                    self.syscall(entry(number, arch))
                    if delivery:
                        self.wire.signal_info.return_value = SignalInfo(
                            31, 0, 1, call_address=123, syscall=number, arch=arch)
                        self.wait.return_value = (11, (31 << 8) | 0x7f)
                        self.step()
                    self.wait.return_value = (11, 31)
                    self.step()
                    self.assertEqual('TERMINAL_FACT_MATCHED', self.config.abi_state(mode))
                    self.assertEqual(1, len(self.owner.settled_reaps))
                    self.assertEqual(before.attempts, self.launch.ledger.totals.attempts)
                    self.assertEqual(live - 1, self.launch.ledger.totals.live)

    def test_direct_probe_cannot_skip_launcher_exec(self):
        self.files.data['/proc/11/cmdline'] = b'\0'.join(a.encode() for a in self.record.argv) + b'\0'
        self.reject_probe()

    def test_launcher_baseline_filter_drift_rejected(self):
        self.status(0, 2)
        self.reject_probe()

    def test_launcher_and_library_byte_drift_rejected(self):
        for path in (self.net['launcher'], self.net['library']['path']):
            self.setup_chain()
            self.files.data[path] = b'changed'
            self.reject_probe()

    def test_probe_requires_nnp_and_exact_added_filter(self):
        for nnp, count in ((0, 2), (1, 1), (1, 3)):
            self.setup_chain()
            self.prepare_probe()
            self.status(nnp, count)
            self.reject_probe()

    def test_missing_duplicate_or_malformed_security_fields_rejected(self):
        for suffix in (b'NoNewPrivs:\t1\n', b'Seccomp_filters:\t-1\n'):
            self.setup_chain()
            self.prepare_probe()
            self.files.data['/proc/11/status'] += suffix
            self.reject_probe()
        self.setup_chain()
        self.prepare_probe()
        self.files.data['/proc/11/status'] = self.files.data['/proc/11/status'].replace(b'NoNewPrivs:', b'Other:')
        self.reject_probe()

    def test_probe_rechecks_library_and_frozen_source(self):
        for path in (self.record.source, self.net['library']['path']):
            self.setup_chain()
            self.prepare_probe()
            self.files.data[path] = b'changed'
            self.reject_probe()

    def test_changed_lifetime_or_nonadjacent_exec_is_rejected(self):
        for lifetime in (False, True):
            self.setup_chain()
            self.prepare_probe()
            if lifetime:
                self.files.data['/proc/11/stat'] = self.files.data['/proc/11/stat'].replace(b'123', b'124')
            else:
                self.dispatch._exec_generations[self.birth] += 1
            self.reject_probe()

    def test_uncertain_launcher_resume_retained_and_never_retried(self):
        self.wire.resume_syscall.side_effect = OSError('uncertain')
        with self.assertRaises(OSError):
            self.step()
        self.assertIn(self.birth, self.config._launch_resume_attempts)
        self.assertNotIn(self.birth, self.config._launch_resumed)
        with self.assertRaises(ValueError):
            self.step()
        self.wire.resume_syscall.assert_called_once()

    def test_expiry_after_launcher_resume_retains_completion_and_revokes(self):
        self.wire.resume_syscall.side_effect = lambda *args: setattr(
            self.f.f.fixture.clock, 'return_value', 100.0)
        with self.assertRaises(ValueError):
            self.step()
        self.assertIn(self.birth, self.config._launch_resumed)
        self.assertIsNotNone(self.config.stopped)

    def test_third_exec_revokes_already_bound_probe(self):
        self.prepare_probe()
        self.step()
        self.syscall(exit_info(0, 0))
        self.syscall(entry(59))
        self.wire.reset_mock()
        self.wire.event_tid.return_value = 11
        self.wait.return_value = (11, 0x4057f)
        with self.assertRaises(AbiObservationError):
            self.step()
        self.assertIsNotNone(self.config._abi_model.stopped)
        self.wire.resume_syscall.assert_not_called()

    def test_frozen_plan_rejects_wrong_command_interpreter_policy_or_types(self):
        for key, value in (('command', list(self.other.argv)), ('python', '/other/python'),
                           ('policy_sha256', '0' * 64), ('inherited_filters', True)):
            with self.assertRaises(ValueError):
                ProbeLaunch(self.record, encoded(dict(self.net, **{key: value})))
        with self.assertRaises(ValueError):
            ProbeLaunch(self.record, self.plan.network_json + ' ')

    def test_config_requires_all_exact_births_and_records(self):
        for launches in (((self.birth, self.plan),), ((True, self.plan),),
                         ((self.birth, self.plan), (self.birth, self.plan))):
            with self.assertRaises(ValueError):
                OwnedExecObservations(((self.birth, self.record), (99, self.other)),
                                      network_launches=launches)

    def test_frozen_json_is_not_mutated_by_caller_copies(self):
        self.net['command'][0] = '/wrong'
        self.plan.network()['library']['sha256'] = '0' * 64
        self.prepare_probe()
        self.step()
        self.assertEqual('BOUND', self.config.abi_state(self.record.mode))


if __name__ == '__main__':
    unittest.main()
