"""Eight artificial cases plus one real-JVM artificial integration; no action execution."""
import hashlib
import inspect
import json
from pathlib import Path
import tempfile
import time
import unittest
from unittest.mock import patch

import fixed_witness_report_pipe as report
import fixed_witness_replay_admission as admission
from fixed_witness_replay_process import OutputBudget, run_process
from fixed_witness_replay_quota import ByteQuota, OutputLimitError


def encoded(value):
    return (json.dumps(value, separators=(',', ':')) + '\n').encode()


def fixture(snapshot):
    """Artificial one-path/one-step declaration with an empty saved prefix."""
    source = b'shape_feasibility_witness,seed,step,piece,actions\nshape_feasibility_witness,71,1,O,HARD_DROP\n'
    expected = dict(required=0, forbidden=0, rawHoles=0, requiredHoles=0, forbiddenHoles=0,
                    supportHoles=0, outsideHoles=0)
    declaration = encoded(dict(schema='fixed-witness-report-request-v1', inputSha256=report._hash(source),
                               journalSha256=report._hash(b''), paths=[dict(id='71', placements=1)],
                               expected={'71': expected}))
    snapshot.mkdir()
    (snapshot / 'request.json').write_bytes(declaration)
    (snapshot / 'input.csv').write_bytes(source)
    (snapshot / 'journal.jsonl').write_bytes(b'')
    return report.retain_request(snapshot, declaration, report._hash(declaration))


class ReportTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.base = Path(temporary.name).resolve()
        self.root, self.java, self.output, self.home, self.tmp = [self.base / name for name in
                                                               ('root', 'jdk', 'out', 'home', 'tmp')]
        for path in (self.root, self.java, self.output, self.home, self.tmp):
            path.mkdir()
        self.request = fixture(self.base / 'snapshot')
        compiled = {}
        for base, names in (('target/classes', admission.MAIN), ('target/test-classes', admission.REPORT_TEST)):
            for name in names:
                compiled[base + '/' + (admission.PREFIX + name).replace('.', '/') + '.class'] = 'c'*64
        for name in ('FixedWitnessReplay$JournalQuota', 'FixedWitnessReplayApplication$ArtifactQuota'):
            compiled['target/test-classes/xyz/xuminghai/tetris/ai/' + name + '.class'] = 'd'*64
        self.manifest = {'toolchain': {'java_home': str(self.java), 'java_version': '27.0.1',
                                       'jdk_files': {'bin/java': 'e'*64}},
                         'environment': {'HOME': str(self.home), 'TMPDIR': str(self.tmp)},
                         'classpath': {'classpath': [str(self.root / 'target/classes'),
                                                     str(self.root / 'target/test-classes')],
                                       'compiled_files': compiled}}
        self.receipt, self.nonce = {'sha256': 'a'*64}, 'b'*64
        self.hook = report.ReportHandshake(self.root, self.java, self.receipt, self.output, self.nonce,
                                           self.manifest, self.request, 100_000, 100_000)
        self.ready = dict(schema=report.SCHEMA, state='READY', nonce=self.nonce,
                          observation=dict(classes=admission._classes(self.manifest, self.root, report=True),
                                           java_runtime='27.0.1+9', pid=123,
                                           classpath=':'.join(self.manifest['classpath']['classpath'])),
                          handshake_bytes_limit=100_000, report_bytes_limit=100_000,
                          request_sha256=self.request.request_sha256,
                          diagnostic_started=False, provenance_authenticated=False,
                          eligible_for_runtime_validation=False)
        self.write_ready()
        self.prepare = self.enterContext(patch.object(report, 'verify_prepared', return_value=self.manifest))
        self.observe = self.enterContext(patch.object(report, 'observe_process', return_value={'pid': 123}))

    def write_ready(self):
        self.raw = encoded(self.ready)
        (self.output / 'ready.json').write_bytes(self.raw)

    def terminal(self, state='INCOMPLETE'):
        value = dict(schema='fixed-witness-report-v1', inputHash=json.loads(self.request.declaration)['inputSha256'],
                     journalState='INCOMPLETE', attempted=0, completed=0, structuralPrefixValid=True,
                     recordedRows=[], observations=[], terminals=[], provenanceAuthenticated=False,
                     qCertified=False, eligibleForRuntimeValidation=False,
                     requestIdentity=dict(json.loads(self.request.declaration), requestSha256=self.request.request_sha256))
        raw = encoded(value)
        (self.output / 'report.json').write_bytes(raw)
        (self.output / 'reported.json').write_bytes(encoded(dict(schema=report.SCHEMA, state=report.SUCCESS,
            ready_sha256=report._hash(self.raw), request_sha256=self.request.request_sha256,
            report_sha256=report._hash(raw), report_bytes=len(raw), journal_state=state,
            handshake_bytes_limit=100_000, report_bytes_limit=100_000, diagnostic_started=False,
            provenance_authenticated=False, eligible_for_runtime_validation=False)))

    def test_report_and_verify_only_inventories_include_named_writers(self):
        old = admission._classes(self.manifest, self.root)
        new = admission._classes(self.manifest, self.root, report=True)
        self.assertLess(set(old), set(new))
        self.assertIn(admission.PREFIX + 'ai.FixedWitnessReplay$JournalQuota', new)
        self.assertIn(admission.PREFIX + 'ai.FixedWitnessReplayApplication$ArtifactQuota', new)
        self.assertNotIn(admission.PREFIX + 'ai.FixedWitnessReport', old)
        admitted = self.hook.authenticate(123)
        self.assertEqual(b'ACK ' + self.nonce.encode() + b' ' + report._hash(self.raw).encode() + b'\n', admitted['ack'])
        self.terminal()
        verified = self.hook.finalize(admitted['evidence'])
        self.assertEqual((report.SUCCESS, 'INCOMPLETE'), (verified['state'], verified['journal_state']))

    def test_wrong_ready_request_hash_never_admitted(self):
        self.ready['request_sha256'] = 'f'*64
        self.write_ready()
        with self.assertRaisesRegex(ValueError, 'schema/request mismatch'):
            self.hook.authenticate(123)
        self.observe.assert_not_called()

    def test_build_receipt_drift_never_admitted(self):
        self.prepare.return_value = {'drift': True}
        with self.assertRaisesRegex(ValueError, 'build receipt drift'):
            self.hook.authenticate(123)
        self.observe.assert_not_called()

    def test_actual_argv_mismatch_never_admitted(self):
        self.observe.side_effect = ValueError('actual argv mismatch')
        with self.assertRaisesRegex(ValueError, 'actual argv mismatch'):
            self.hook.authenticate(123)
        self.assertEqual('--report-only', self.observe.call_args.args[1][8])

    def test_post_exit_input_drift_rejected(self):
        admitted = self.hook.authenticate(123)
        self.terminal()
        (self.request.directory / 'input.csv').write_bytes(b'changed')
        with self.assertRaisesRegex(ValueError, 'request receipt drift'):
            self.hook.finalize(admitted['evidence'])

    def test_insufficient_same_root_quota_rejects_before_spawn(self):
        budget = OutputBudget(quota=ByteQuota(31))
        with patch.object(report, 'run_process') as launch:
            with self.assertRaises(OutputLimitError):
                report.run_report_only(self.root, self.java, self.receipt, self.base / 'new', self.base, budget,
                    nonce=self.nonce, request=self.request, handshake_bytes=16, report_bytes=16,
                    total_deadline=time.monotonic()+10)
            launch.assert_not_called()
        self.assertEqual(0, budget.quota.charged)
        self.assertFalse((self.base / 'new').exists())

    def test_reported_complete_cannot_promote_incomplete_report(self):
        admitted = self.hook.authenticate(123)
        self.terminal('COMPLETE')
        with self.assertRaisesRegex(ValueError, 'journal state mismatch'):
            self.hook.finalize(admitted['evidence'])

    def test_closed_parent_success_selection_keeps_verify_default(self):
        self.assertEqual('VERIFIED_NO_REPLAY', inspect.signature(run_process).parameters['success_state'].default)
        with self.assertRaisesRegex(ValueError, 'closed handshake success state'):
            run_process(['unused'], self.root, self.base, 'invalid', OutputBudget(), seconds=1,
                        total_deadline=time.monotonic()+1, success_state='COMPLETE')


def artificial_report_jvm(spec):
    """Ninth Python case: real Linux JVM/ACK with explicitly MOCK build receipt.

    Uses existing compiled classes; no prepare/clean-build/network acceptance or actions.
    """
    from fixed_witness_replay_identity import classpath_identity, sha256
    from fixed_witness_replay_environment import controlled_environment
    root, java, out = (Path(spec[key]).resolve() for key in ('root', 'java', 'out'))
    base = out / 'report-mode'
    base.mkdir()
    home, tmp = base / 'home', base / 'tmp'
    home.mkdir()
    tmp.mkdir()
    request = fixture(base / 'snapshot')
    version = next(json.loads(line.split('=', 1)[1]) for line in
                   (java / 'release').read_text().splitlines() if line.startswith('JAVA_VERSION='))
    manifest = {'toolchain': {'java_home': str(java), 'java_version': version,
                              'jdk_files': {'bin/java': sha256(java / 'bin/java')}},
                'environment': controlled_environment(java, home, tmp),
                'classpath': classpath_identity(root, spec['dependencies'])}
    budget = OutputBudget()
    # Artificial input creation is retained in this same root accounting as well.
    budget.quota.charge(sum((request.directory / name).stat().st_size for name in report.LIMITS))
    with patch.object(report, 'verify_prepared', return_value=manifest):
        result = report.run_report_only(root, java, {'sha256': 'a'*64}, base / 'jvm', base, budget,
            nonce='b'*64, request=request, handshake_bytes=300_000, report_bytes=100_000,
            total_deadline=time.monotonic()+60)
    assert result['state'] == report.SUCCESS, result
    assert result['returncode'] == 0 and result['ack_sent'], result
    assert result['verification']['journal_state'] == 'INCOMPLETE', result
    assert not result['verification']['diagnostic_authorized'], result
    assert not result['verification']['network_isolation_verified'], result
    assert {p.name for p in (base / 'jvm').iterdir()} == {'ready.json', 'report.json', 'reported.json'}
    request.recheck()


if __name__ == '__main__':
    unittest.main()
