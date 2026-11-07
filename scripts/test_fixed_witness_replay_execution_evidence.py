"""Exactly accepted cases 13/15/16: artificial saved bytes, no JVM/actions/history."""
import json
from pathlib import Path
import tempfile
import unittest

import fixed_witness_replay_execution_evidence as evidence
from fixed_witness_replay_quota import ByteQuota, OutputLimitError


def encoded(value):
    # Mock wire files only; the real declaration must come from Java canonicalBytes.
    return (json.dumps(value, separators=(',', ':')) + '\n').encode()


class ExecutionEvidenceTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.base = Path(temporary.name).resolve()
        snapshot = self.base / 'snapshot'
        snapshot.mkdir()
        self.output = self.base / 'output'
        self.output.mkdir()
        source = (b'shape_feasibility_witness,seed,step,piece,actions\n'
                  b'shape_feasibility_witness,71,1,O,HARD_DROP\n')
        expected = dict(required=0, forbidden=0, rawHoles=0, requiredHoles=0,
                        forbiddenHoles=0, supportHoles=0, outsideHoles=0)
        self.declaration = dict(schema='fixed-witness-replay-request-v1',
            inputSha256=evidence._hash(source), paths=[dict(id='71', placements=1)], expected={'71': expected})
        declaration = encoded(self.declaration)
        (snapshot / 'request.json').write_bytes(declaration)
        (snapshot / 'input.csv').write_bytes(source)
        self.request = evidence.retain_execution_request(snapshot, declaration, evidence._hash(declaration))
        self.allowances = dict(handshake_bytes=10_000, journal_bytes=10_000,
                               declaration_bytes=10_000, report_bytes=10_000)
        self.limits = evidence._limits(**self.allowances)
        self.nonce = 'a'*64
        self.ready = encoded(dict(schema=evidence.SCHEMA, state='READY', nonce=self.nonce,
            observation={'artificial': True}, request_sha256=self.request.request_sha256,
            actions_started=False, **self.limits, **{name: False for name in evidence.FLAGS}))
        (self.output / 'ready.json').write_bytes(self.ready)
        # A hand-authored INVALID prefix; neither a real action failure nor clean-build receipt.
        journal = encoded(dict(event='START', schema=1, inputHash=self.declaration['inputSha256'],
            paths=self.declaration['paths'], expected=self.declaration['expected']))
        journal += encoded(dict(event='ATTEMPT', attempt=1, pathId='71', step=1))
        journal += encoded(dict(event='END', status='INVALID', attempted=1, completed=0, reason='artificial'))
        (self.output / 'journal.jsonl').write_bytes(journal)
        derived = dict(self.declaration, schema='fixed-witness-report-request-v1',
                       journalSha256=evidence._hash(journal))
        raw_declaration = encoded(derived)
        (self.output / 'report-request.json').write_bytes(raw_declaration)
        report = encoded(dict(schema='fixed-witness-report-v1', inputHash=self.declaration['inputSha256'],
            journalState='INVALID', attempted=1, completed=0, structuralPrefixValid=True,
            recordedRows=[], observations=[], terminals=[dict(pathId='71', assessment='NOT_RECORDED')],
            provenanceAuthenticated=False, qCertified=False, eligibleForRuntimeValidation=False,
            requestIdentity=dict(derived, requestSha256=evidence._hash(raw_declaration))))
        (self.output / 'report.json').write_bytes(report)
        self.terminal = dict(schema=evidence.SCHEMA, state=evidence.SUCCESS,
            ready_sha256=evidence._hash(self.ready), request_sha256=self.request.request_sha256,
            journal_sha256=evidence._hash(journal), journal_bytes=len(journal),
            declaration_sha256=evidence._hash(raw_declaration), declaration_bytes=len(raw_declaration),
            report_sha256=evidence._hash(report), report_bytes=len(report), journal_state='INVALID',
            actions_started=True, **self.limits, **{name: False for name in evidence.FLAGS})
        self.write_terminal()

    def write_terminal(self):
        (self.output / 'replayed.json').write_bytes(encoded(self.terminal))

    def verify(self):
        return evidence.verify_execution_artifacts(self.output, self.request, ready_bytes=self.ready,
                                                    nonce=self.nonce, **self.allowances)

    def test_case13_insufficient_root_reservation_is_atomic(self):
        quota = ByteQuota(40_000)
        quota.charge(1)
        with self.assertRaises(OutputLimitError):
            evidence.reserve_execution(quota, **self.allowances)
        self.assertEqual((1, 39_999), (quota.charged, quota.remaining))

    def test_case15_post_exit_journal_drift_rejected(self):
        verified = self.verify()
        self.assertEqual((evidence.SUCCESS, 'INVALID'), (verified['state'], verified['journal_state']))
        self.assertFalse(verified['diagnostic_authorized'])
        self.assertFalse(verified['provenance_authenticated'])
        journal = self.output / 'journal.jsonl'
        journal.write_bytes(journal.read_bytes().replace(b'artificial', b'ARTIFICIAL'))
        with self.assertRaisesRegex(ValueError, 'artifact byte binding'):
            self.verify()

    def test_case16_terminal_cannot_promote_invalid_report(self):
        self.assertEqual('INVALID', self.verify()['journal_state'])
        self.terminal['journal_state'] = 'COMPLETE'
        self.write_terminal()
        with self.assertRaisesRegex(ValueError, 'terminal/report state conflict'):
            self.verify()


if __name__ == '__main__':
    unittest.main()
