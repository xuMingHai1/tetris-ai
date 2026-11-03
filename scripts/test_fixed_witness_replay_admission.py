"""Artificial parent admission evidence; never read the historical witness."""
import copy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import fixed_witness_replay_admission as admission


class AdmissionTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.base = Path(self.temporary.name).resolve()
        self.root, self.java, self.output = [self.base / name for name in ('root', 'jdk', 'out')]
        for path in (self.root, self.java, self.output):
            path.mkdir()
        self.nonce = 'a' * 64
        self.receipt = {'manifest': str(self.base / 'manifest.json'), 'sha256': 'b' * 64}
        compiled = {}
        for base, names in (('target/classes', admission.MAIN), ('target/test-classes', admission.TEST)):
            for name in names:
                compiled[base + '/' + (admission.PREFIX + name).replace('.', '/') + '.class'] = 'c' * 64
        self.nested = 'target/classes/xyz/xuminghai/tetris/ai/AiPlan$Step.class'
        compiled[self.nested] = 'd' * 64
        compiled['target/classes/xyz/xuminghai/tetris/ai/ActionStateSearch$1.class'] = 'f' * 64
        self.manifest = {'state': 'PREPARED', 'diagnostic_started': False,
                         'toolchain': {'java_home': str(self.java), 'java_version': '27.0.1',
                                       'jdk_files': {'bin/java': 'e' * 64}},
                         'environment': {'HOME': str(self.base / 'home'), 'TMPDIR': str(self.base / 'tmp')},
                         'classpath': {'classpath': [str(self.root / 'target/classes'),
                                                     str(self.root / 'target/test-classes'),
                                                     str(self.base / 'z.jar'), str(self.base / 'a.jar')],
                                       'compiled_files': compiled}}
        self.process = {'state': 'OBSERVED', 'pid': 123, 'start_ticks': 456}
        self.ready = {'schema': 'fixed-witness-jvm-v1', 'state': 'READY', 'nonce': self.nonce,
                      'artifact_bytes_limit': admission.JVM_ARTIFACT_BYTES,
                      'diagnostic_started': False, 'provenance_authenticated': False,
                      'eligible_for_runtime_validation': False,
                      'observation': {'classes': admission._classes(self.manifest, self.root),
                                      'java_runtime': '27.0.1+12', 'pid': 123,
                                      'classpath': ':'.join(self.manifest['classpath']['classpath'])}}
        self.write()
        self.prepare = self.enterContext(patch.object(admission, 'verify_prepared', return_value=self.manifest))
        self.observe = self.enterContext(patch.object(admission, 'observe_process', return_value=self.process))

    def write(self):
        self.bytes = (json.dumps(self.ready) + '\n').encode()
        (self.output / 'ready.json').write_bytes(self.bytes)

    def authenticate(self):
        return admission.authenticate_ready(self.root, self.java, self.receipt, self.output, self.nonce, 123)

    def rejects(self):
        self.write()
        with self.assertRaises((ValueError, TypeError, KeyError)):
            self.authenticate()

    def test_ack_is_bound_to_exact_bytes_and_independent_receipt(self):
        result = self.authenticate()
        self.assertEqual(b'ACK ' + self.nonce.encode() + b' ' + hashlib.sha256(self.bytes).hexdigest().encode()
                         + b'\n', result['ack'])
        self.assertEqual(2, self.prepare.call_count)
        self.assertEqual(2, self.observe.call_count)
        argv = self.observe.call_args.args[1]
        self.assertEqual(['-Xmx256m', '-XX:MaxMetaspaceSize=128m'], argv[1:3])
        self.assertEqual(self.ready['observation']['classpath'], argv[6])
        self.assertEqual(self.manifest['environment'], self.observe.call_args.args[2])
        self.assertFalse(result['evidence']['diagnostic_started'])
        self.assertFalse(result['evidence']['network_isolation_verified'])
        self.assertFalse(result['evidence']['diagnostic_authorized'])
        self.assertNotIn('xyz.xuminghai.tetris.ai.ActionStateSearch$1', self.ready['observation']['classes'])
        self.assertEqual(['ready.json'], [p.name for p in self.output.iterdir()])

    def test_wrong_nonce_rejected_before_process_observation(self):
        self.ready['nonce'] = 'f' * 64
        self.rejects()
        self.observe.assert_not_called()

    def test_boolean_pid_and_wrong_pid_rejected(self):
        for pid in (True, 124):
            self.ready['observation']['pid'] = pid
            self.rejects()

    def test_missing_or_extra_class_rejected(self):
        classes = self.ready['observation']['classes']
        original = copy.deepcopy(classes)
        classes.pop(next(iter(classes)))
        self.rejects()
        self.ready['observation']['classes'] = {**original, 'unexpected.Class': {}}
        self.rejects()

    def test_nested_class_hash_origin_and_resource_rejected(self):
        name = 'xyz.xuminghai.tetris.ai.AiPlan$Step'
        for field in ('sha256', 'origin', 'resource'):
            original = self.ready['observation']['classes'][name][field]
            self.ready['observation']['classes'][name][field] = 'wrong'
            self.rejects()
            self.ready['observation']['classes'][name][field] = original

    def test_reordered_classpath_rejected(self):
        self.ready['observation']['classpath'] = ':'.join(reversed(self.manifest['classpath']['classpath']))
        self.rejects()

    def test_unknown_fields_and_false_like_authority_rejected(self):
        for value in (0, None, 'false', True):
            self.ready['diagnostic_started'] = value
            self.rejects()
        self.ready['diagnostic_started'] = False
        self.ready['unexpected'] = False
        self.rejects()

    def test_duplicate_json_keys_and_oversized_prefix_rejected(self):
        for data in (b'{"state":"READY","state":"READY"}\n', b' ' * (admission.READY_BYTES + 1)):
            (self.output / 'ready.json').write_bytes(data)
            with self.assertRaises(ValueError):
                self.authenticate()

    def test_manifest_replacement_or_drift_never_returns_ack(self):
        for stage in (1, 2):
            self.prepare.side_effect = ([ValueError('digest mismatch')] if stage == 1
                                        else [self.manifest, ValueError('source drift')])
            with self.assertRaises(ValueError):
                self.authenticate()

    def test_process_mismatch_or_disappearance_never_returns_ack(self):
        for error in (ValueError('argv mismatch'), FileNotFoundError('process exited')):
            self.observe.side_effect = error
            with self.assertRaises(type(error)):
                self.authenticate()

    def test_second_process_observation_must_match(self):
        self.observe.side_effect = [self.process, {**self.process, 'start_ticks': 457}]
        with self.assertRaisesRegex(ValueError, 'process changed'):
            self.authenticate()

    def test_ready_replacement_during_authentication_rejected(self):
        def observe(*args):
            self.ready['nonce'] = 'f' * 64
            self.write()
            return self.process
        self.observe.side_effect = observe
        with self.assertRaisesRegex(ValueError, 'READY changed'):
            self.authenticate()

    def test_wrong_runtime_or_missing_retained_primary_class_rejected(self):
        self.ready['observation']['java_runtime'] = '27.0.10+1'
        self.rejects()
        self.ready['observation']['java_runtime'] = '27.0.1+12'
        self.manifest['classpath']['compiled_files'].pop(next(iter(self.manifest['classpath']['compiled_files'])))
        self.rejects()

    def test_invalid_nonce_output_and_jdk_rejected(self):
        with self.assertRaises(ValueError):
            admission.command_for(self.manifest, self.root, self.java, self.output, 'wrong')
        with self.assertRaises(ValueError):
            admission.command_for(self.manifest, self.root, self.java, self.root / 'nested', self.nonce)
        self.manifest['toolchain']['java_home'] = 'wrong'
        with self.assertRaises(ValueError):
            self.authenticate()

    def test_artifact_allowance_is_bound_to_ready_and_exact_command(self):
        self.authenticate()
        self.assertEqual(str(admission.JVM_ARTIFACT_BYTES), self.observe.call_args.args[1][-1])
        for invalid in (True, 0, -1, admission.JVM_ARTIFACT_BYTES + 1, '100'):
            with self.subTest(invalid=invalid), self.assertRaises(ValueError):
                admission.command_for(self.manifest, self.root, self.java, self.output, self.nonce, invalid)
        for invalid in (True, admission.JVM_ARTIFACT_BYTES - 1):
            self.ready['artifact_bytes_limit'] = invalid
            self.rejects()
        self.ready['artifact_bytes_limit'] = admission.JVM_ARTIFACT_BYTES
        self.write()
        with self.assertRaisesRegex(ValueError, 'allowance mismatch'):
            admission.authenticate_ready(self.root, self.java, self.receipt, self.output, self.nonce, 123, 1)


if __name__ == '__main__':
    unittest.main()
