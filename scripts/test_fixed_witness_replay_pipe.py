"""Artificial pipe children and evidence only; never read historical inputs."""
import hashlib
import json
import os
from pathlib import Path
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

import fixed_witness_replay_pipe as pipe
from fixed_witness_replay_process import OutputBudget, run_process
from fixed_witness_replay_quota import ByteQuota, OutputLimitError


class EvidenceTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.output = self.root / 'evidence'
        self.output.mkdir()
        self.raw = b'{"state":"READY"}\n'
        self.hash = hashlib.sha256(self.raw).hexdigest()
        (self.output / 'ready.json').write_bytes(self.raw)
        self.verified = dict(schema='fixed-witness-jvm-v1', state='VERIFIED_NO_REPLAY',
                             artifact_bytes_limit=pipe.JVM_ARTIFACT_BYTES,
                             ready_sha256=self.hash, diagnostic_started=False,
                             provenance_authenticated=False, eligible_for_runtime_validation=False)
        self.manifest = {'artificial': True}
        self.hook = pipe.VerifyOnlyHandshake(self.root, self.root, {'sha256': 'a'*64},
                                              self.output, 'b'*64, self.manifest)
        self.prepare = self.enterContext(patch.object(pipe, 'verify_prepared', return_value=self.manifest))
        self.write()

    def write(self):
        (self.output / 'verified.json').write_text(json.dumps(self.verified) + '\n', encoding='utf-8')

    def test_post_exit_checks_and_authority(self):
        self.assertTrue(self.hook.ready())
        result = self.hook.finalize({'ready_sha256': self.hash})
        self.assertEqual('VERIFIED_NO_REPLAY', result['state'])
        self.assertFalse(result['diagnostic_started'])
        self.assertFalse(result['diagnostic_authorized'])
        self.assertFalse(result['network_isolation_verified'])
        self.prepare.assert_called_once()

    def test_partial_ready_waits_then_oversize_rejected(self):
        (self.output / 'ready.json').write_bytes(b'{')
        self.assertFalse(self.hook.ready())
        (self.output / 'ready.json').write_bytes(b'x' * (1024*1024 + 1))
        with self.assertRaises(ValueError):
            self.hook.ready()

    def test_post_exit_receipt_and_ready_drift_rejected(self):
        self.prepare.return_value = {'changed': True}
        with self.assertRaisesRegex(ValueError, 'receipt drift'):
            self.hook.finalize({'ready_sha256': self.hash})
        self.prepare.return_value = self.manifest
        with self.assertRaisesRegex(ValueError, 'READY drift'):
            self.hook.finalize({'ready_sha256': 'c'*64})

    def test_verified_hash_state_authority_fields_rejected(self):
        for key, value in [('ready_sha256', 'c'*64), ('state', 'COMPLETE'),
                           ('diagnostic_started', 0), ('provenance_authenticated', True),
                           ('unexpected', False)]:
            original = dict(self.verified)
            self.verified[key] = value
            self.write()
            with self.subTest(key=key), self.assertRaises(ValueError):
                self.hook.finalize({'ready_sha256': self.hash})
            self.verified = original

    def test_extra_or_duplicate_artifact_rejected(self):
        (self.output / 'extra').write_bytes(b'x')
        with self.assertRaisesRegex(ValueError, 'unexpected verify-only'):
            self.hook.finalize({'ready_sha256': self.hash})
        (self.output / 'verified.json').write_bytes(b'{"state":"a","state":"b"}\n')
        with self.assertRaisesRegex(ValueError, 'duplicate'):
            self.hook.finalize({'ready_sha256': self.hash})

    def test_cancelled_inventory_never_launches(self):
        from threading import Event
        from fixed_witness_replay_identity import evidence_control, _git
        cancelled = Event()
        cancelled.set()
        with evidence_control(time.monotonic()+5, cancelled), patch('subprocess.Popen') as launch:
            with self.assertRaisesRegex(ValueError, 'before launch'):
                _git(self.root, 'status')
            launch.assert_not_called()

    def test_post_exit_quota_mismatch_or_combined_overflow_rejected(self):
        self.verified['artifact_bytes_limit'] = True
        self.write()
        with self.assertRaisesRegex(ValueError, 'allowance mismatch'):
            self.hook.finalize({'ready_sha256': self.hash})
        self.hook.artifact_bytes = len(self.raw)
        self.verified['artifact_bytes_limit'] = self.hook.artifact_bytes
        self.write()
        with self.assertRaisesRegex(ValueError, 'allowance exceeded'):
            self.hook.finalize({'ready_sha256': self.hash})

    def test_prelaunch_reservation_denies_insufficient_quota_without_launch(self):
        quota = ByteQuota(15)
        with patch.object(pipe, 'command_for', return_value=['artificial']), \
                patch.object(pipe, 'run_process') as launch:
            with self.assertRaises(OutputLimitError):
                pipe.run_verify_only(self.root, self.root, {}, self.root / 'new', self.root,
                                     OutputBudget(quota=quota), nonce='b'*64,
                                     total_deadline=time.monotonic()+10, artifact_bytes=16)
            launch.assert_not_called()
        self.assertEqual(0, quota.charged)
        self.assertFalse((self.root / 'new').exists())

    def test_prelaunch_reservation_is_permanent_and_not_double_charged(self):
        self.manifest['environment'] = {}
        quota = ByteQuota(20)
        budget = OutputBudget(quota=quota)

        def launch(*args, **kwargs):
            self.assertEqual((16, 4), (quota.charged, quota.remaining))
            self.assertEqual(16, kwargs['handshake'].artifact_bytes)
            return {'state': 'ARTIFICIAL'}

        with patch.object(pipe, 'command_for', return_value=['artificial']), \
                patch.object(pipe, 'run_process', side_effect=launch):
            pipe.run_verify_only(self.root, self.root, {}, self.root / 'new', self.root,
                                 budget, nonce='b'*64, total_deadline=time.monotonic()+10, artifact_bytes=16)
        self.assertEqual((16, 0), (quota.charged, budget.written))
        self.assertEqual(4, quota.take_prefix(10))


@unittest.skipUnless(sys.platform == 'linux', 'actual Linux pipe/process supervision required')
class PipeTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.ack = b'ACK artificial\n'
        self.hook = self
        self.delay = 0
        self.reject = False
        self.final_delay = 0

    def ready(self):
        return (self.root / 'ready').exists()

    def authenticate(self, pid):
        time.sleep(self.delay)
        if self.reject:
            raise ValueError('artificial drift')
        return {'ack': self.ack, 'evidence': {'pid': pid}}

    def finalize(self, admitted):
        time.sleep(self.final_delay)
        self.assertEqual(self.ack, (self.root / 'received').read_bytes())
        return {'artificial': True}

    def run_child(self, *, suffix='', prefix='', seconds=2, limit=4096):
        code = ("from pathlib import Path; import sys,time,os; " + prefix
                + "Path('ready').write_text('ready'); " + suffix
                + "data=sys.stdin.buffer.read(); Path('received').write_bytes(data)")
        return run_process([sys.executable, '-c', code], self.root, self.root, 'pipe',
                           OutputBudget(limit), seconds=seconds,
                           total_deadline=time.monotonic()+5, environment={'PATH': '/usr/bin:/bin'},
                           handshake=self.hook)

    def test_ack_and_eof_then_post_exit_verification(self):
        result = self.run_child()
        self.assertEqual(('VERIFIED_NO_REPLAY', True, 0),
                         (result['state'], result['ack_sent'], result['returncode']))

    def test_slow_authentication_times_out_without_ack(self):
        self.delay = .6
        result = self.run_child(seconds=.2)
        self.assertEqual(('TIMEOUT', False), (result['state'], result['ack_sent']))
        self.assertLess(result['elapsed_seconds'], 2)
        time.sleep(.7)  # completed late worker still has no stdin capability
        self.assertFalse((self.root / 'received').exists())

    def test_early_exit_during_authentication_has_no_ack(self):
        self.delay = .4
        result = self.run_child(suffix='time.sleep(.1); sys.exit(0); ')
        self.assertEqual(('EXIT_BEFORE_ACK', False), (result['state'], result['ack_sent']))

    def test_authentication_error_has_no_ack(self):
        self.reject = True
        result = self.run_child()
        self.assertEqual(('EVIDENCE_ERROR', False), (result['state'], result['ack_sent']))
        self.assertIn('artificial drift', result['error'])

    def test_output_flood_during_authentication_has_no_ack(self):
        self.delay = .4
        result = self.run_child(suffix="time.sleep(.05); os.write(1,b'x'*8192); ", limit=64)
        self.assertEqual(('OUTPUT_LIMIT', False, 64),
                         (result['state'], result['ack_sent'], result['stdout_stderr_bytes']))

    def test_post_exit_verification_shares_deadline(self):
        self.final_delay = .8
        result = self.run_child(seconds=.3)
        self.assertEqual(('TIMEOUT', True), (result['state'], result['ack_sent']))
        self.assertLess(result['elapsed_seconds'], 2)

    def test_nonzero_exit_after_ack_not_success(self):
        result = self.run_child(suffix="data=sys.stdin.buffer.read(); sys.exit(7); ")
        self.assertEqual(('CHILD_FAILED', True, 7),
                         (result['state'], result['ack_sent'], result['returncode']))

    def test_cancellation_at_wrapper_transition_is_preserved(self):
        import signal
        real_run = run_process

        def transition(*args, **kwargs):
            kwargs['cancellation']['signal'] = signal.SIGTERM
            return real_run(*args, **kwargs)

        with patch.object(pipe, 'verify_prepared', return_value={'environment': {'PATH': '/usr/bin:/bin'}}), \
                patch.object(pipe, 'command_for', return_value=[sys.executable, '-c', 'pass']), \
                patch.object(pipe, 'run_process', side_effect=transition):
            result = pipe.run_verify_only(self.root, self.root, {}, self.root / 'unused', self.root,
                                         OutputBudget(), nonce='b'*64, total_deadline=time.monotonic()+10)
        self.assertEqual(('INTERRUPTED', False, None),
                         (result['state'], result['ack_sent'], result['pid']))

    def test_inventory_subprocess_is_cancelled_without_ack(self):
        from fixed_witness_replay_identity import _git
        binary = self.root / 'bin'
        binary.mkdir()
        fake = binary / 'git'
        fake.write_text('#!' + sys.executable + '\nimport os,time\n'
                        'open("inventory-pid","w").write(str(os.getpid()))\ntime.sleep(30)\n')
        fake.chmod(0o700)

        def authenticate(pid):
            _git(self.root, 'status', environment={'PATH': str(binary)})
            self.fail('slow inventory unexpectedly returned')

        self.authenticate = authenticate
        result = self.run_child(seconds=.3)
        self.assertFalse(result['ack_sent'])
        self.assertIn(result['state'], ('TIMEOUT', 'EVIDENCE_ERROR'))
        pid = int((self.root / 'inventory-pid').read_text())
        with self.assertRaises(ProcessLookupError):
            os.kill(pid, 0)
        self.assertLess(result['elapsed_seconds'], 2)

    def test_limit_kills_jvm_before_waiting_for_worker(self):
        import fixed_witness_replay_process as supervisor
        self.delay = .5
        events = []
        kill, stop = supervisor.os.killpg, supervisor._stop_work

        def record_kill(*args):
            events.append('kill')
            return kill(*args)

        def record_stop(*args):
            events.append('stop')
            return stop(*args)

        with patch.object(supervisor.os, 'killpg', side_effect=record_kill), \
                patch.object(supervisor, '_stop_work', side_effect=record_stop):
            result = self.run_child(seconds=.2)
        self.assertEqual('TIMEOUT', result['state'])
        self.assertEqual(['kill', 'stop'], events)


def artificial_jvm(spec):
    """JUnit supplies compiled classes; mock receipt ONLY, use real JVM/proc/ACK.

    This does not run prepare, prove clean-build provenance or open replay mode.
    The Java entrypoint only inspects classes and exchanges READY/ACK/verified.
    """
    import fixed_witness_replay_admission as admission
    from fixed_witness_replay_identity import classpath_identity, sha256
    from fixed_witness_replay_environment import controlled_environment
    root, java, out = (Path(spec[key]).resolve() for key in ('root', 'java', 'out'))
    home, tmp = out / 'home', out / 'tmp'
    home.mkdir()
    tmp.mkdir()
    version = next(json.loads(line.split('=', 1)[1]) for line in
                   (java / 'release').read_text().splitlines() if line.startswith('JAVA_VERSION='))
    manifest = {'toolchain': {'java_home': str(java), 'java_version': version,
                              'jdk_files': {'bin/java': sha256(java / 'bin/java')}},
                'environment': controlled_environment(java, home, tmp),
                'classpath': classpath_identity(root, spec['dependencies'])}
    with patch.object(pipe, 'verify_prepared', return_value=manifest), \
            patch.object(admission, 'verify_prepared', return_value=manifest):
        result = pipe.run_verify_only(root, java, {'sha256': 'a'*64}, out / 'jvm', out,
                                     OutputBudget(), nonce='b'*64,
                                     total_deadline=time.monotonic()+60)
    assert result['state'] == 'VERIFIED_NO_REPLAY', result
    assert result['returncode'] == 0 and result['ack_sent'], result
    assert not result['verification']['diagnostic_started'], result
    assert not result['verification']['network_isolation_verified'], result
    assert not result['verification']['diagnostic_authorized'], result
    assert result['verification']['artifact_bytes_limit'] == pipe.JVM_ARTIFACT_BYTES, result
    assert result['verification']['artifact_bytes'] == sum(
        (out / 'jvm' / name).stat().st_size for name in ('ready.json', 'verified.json')), result
    (out / 'artificial-result.json').write_text(json.dumps(result) + '\n')


if __name__ == '__main__':
    unittest.main()
