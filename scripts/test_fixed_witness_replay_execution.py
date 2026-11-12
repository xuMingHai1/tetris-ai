"""Exactly accepted cases 5/6 and Linux cases 7/9; one artificial placement.

The Linux functions use existing compiled directories and a mock build receipt,
not clean-build/network provenance or a historical execution registration.
"""
import json
from pathlib import Path
import tempfile
import time
import unittest
from unittest.mock import patch

import fixed_witness_replay_admission as admission
import fixed_witness_replay_execution as execution
from fixed_witness_replay_execution_evidence import retain_execution_request
from fixed_witness_replay_process import OutputBudget, run_process
from fixed_witness_replay_quota import write_exclusive
from fixed_witness_report_pipe import _hash


ALLOWANCES = dict(handshake_bytes=300_000, journal_bytes=100_000,
                  declaration_bytes=16_384, report_bytes=100_000)


def fixture(snapshot, quota):
    source = (b'shape_feasibility_witness,seed,step,piece,actions\n'
              b'shape_feasibility_witness,71,1,O,HARD_DROP\n')
    # Literal Java canonical fixture, not a Python request serializer.
    raw = (b'{"schema":"fixed-witness-replay-request-v1","inputSha256":"' + _hash(source).encode()
           + b'","paths":[{"id":"71","placements":1}],"expected":{"71":{"required":0,'
             b'"forbidden":0,"rawHoles":0,"requiredHoles":0,"forbiddenHoles":0,'
             b'"supportHoles":0,"outsideHoles":0}}}\n')
    snapshot.mkdir()
    write_exclusive(snapshot / 'input.csv', source, quota)
    write_exclusive(snapshot / 'request.json', raw, quota)
    return retain_execution_request(snapshot, raw, _hash(raw))


class ExecutionTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.base = Path(temporary.name).resolve()
        self.root, self.java, self.home, self.tmp, self.output = [self.base / n for n in
                                                               ('root', 'java', 'home', 'tmp', 'out')]
        for path in (self.root, self.java, self.home, self.tmp, self.output):
            path.mkdir()
        self.budget = OutputBudget()
        self.request = fixture(self.base / 'snapshot', self.budget.quota)
        compiled = {}
        for base, names in (('target/classes', admission.MAIN),
                            ('target/test-classes', tuple(dict.fromkeys(admission.REPORT_TEST + admission.EXECUTION_TEST)))):
            for name in names:
                compiled[base + '/' + (admission.PREFIX + name).replace('.', '/') + '.class'] = 'c'*64
        for name in ('FixedWitnessReplayDescriptor$Snapshot', 'FixedWitnessReplayDescriptor$Identity',
                     'FixedWitnessReplay$JournalQuota',
                     'FixedWitnessReplayApplication$ArtifactQuota'):
            compiled['target/test-classes/xyz/xuminghai/tetris/ai/' + name + '.class'] = 'd'*64
        self.manifest = dict(toolchain=dict(java_home=str(self.java), java_version='27.0.1',
                                            jdk_files={'bin/java': 'e'*64}),
                             environment={'HOME': str(self.home), 'TMPDIR': str(self.tmp)},
                             classpath=dict(classpath=[str(self.root / 'target/classes'),
                                                       str(self.root / 'target/test-classes')], compiled_files=compiled))
        self.hook = execution.ExecutionHandshake(self.root, self.java, {'sha256': 'a'*64}, self.output,
                                                'b'*64, self.manifest, self.request, **ALLOWANCES)
        ready = dict(schema=execution.SCHEMA, state='READY', nonce='b'*64,
                     request_sha256=self.request.request_sha256, actions_started=False,
                     observation=dict(classes=admission._classes(self.manifest, self.root, execution=True),
                         java_runtime='27.0.1+9', pid=123, classpath=':'.join(self.manifest['classpath']['classpath'])),
                     **self.hook.limits, **{name: False for name in execution.FLAGS})
        self.raw = (json.dumps(ready, separators=(',', ':')) + '\n').encode()
        (self.output / 'ready.json').write_bytes(self.raw)
        self.enterContext(patch.object(execution, 'verify_prepared', return_value=self.manifest))
        self.enterContext(patch.object(execution, 'observe_process', return_value={'pid': 123}))

    def test_case5_execution_inventory_and_old_modes(self):
        old = admission._classes(self.manifest, self.root)
        report = admission._classes(self.manifest, self.root, report=True)
        current = admission._classes(self.manifest, self.root, execution=True)
        for name in ('FixedWitnessReplayRequest', 'FixedWitnessReplayDescriptor$Snapshot',
                     'FixedWitnessReplayDescriptor$Identity'):
            self.assertIn(admission.PREFIX + 'ai.' + name, current)
            self.assertNotIn(admission.PREFIX + 'ai.' + name, old)
            self.assertNotIn(admission.PREFIX + 'ai.' + name, report)
        self.assertNotIn(admission.PREFIX + 'ai.FixedWitnessReportDescriptor', current)
        self.assertIn(admission.PREFIX + 'ai.FixedWitnessReplay$JournalQuota', current)
        admitted = self.hook.authenticate(123)
        self.assertEqual(b'ACK ' + b'b'*64 + b' ' + _hash(self.raw).encode() + b'\n', admitted['ack'])
        self.assertEqual(self.raw, self.hook._ready_bytes)
        self.assertFalse(admitted['evidence']['actions_started'])

    def test_case6_ready_hash_drift_never_admitted(self):
        read = execution._read
        calls = 0

        def drift(path, limit):
            nonlocal calls
            raw, identity = read(path, limit)
            calls += 1
            return (raw if calls == 1 else raw + b'\n'), identity

        with patch.object(execution, '_read', side_effect=drift):
            with self.assertRaisesRegex(ValueError, 'READY drift'):
                self.hook.authenticate(123)
        self.assertIsNone(self.hook._ready_bytes)
        self.assertFalse((self.output / 'journal.jsonl').exists())


def artificial_execution_jvm(spec, coordinator=None):
    """Cases 7 and 9, two direct JVMs, one actual placement in the second only."""
    from fixed_witness_replay_identity import classpath_identity, sha256
    from fixed_witness_replay_environment import controlled_environment
    root, java, out = (Path(spec[key]).resolve() for key in ('root', 'java', 'out'))
    base = out / 'execution-mode'
    base.mkdir()
    home, tmp = base / 'home', base / 'tmp'
    home.mkdir()
    tmp.mkdir()
    budget = OutputBudget() if coordinator is None else coordinator.budget
    request = fixture(base / 'snapshot', budget.quota)
    version = next(json.loads(line.split('=', 1)[1]) for line in
                   (java / 'release').read_text().splitlines() if line.startswith('JAVA_VERSION='))
    manifest = dict(toolchain=dict(java_home=str(java), java_version=version,
                                   jdk_files={'bin/java': sha256(java / 'bin/java')}),
                    environment=controlled_environment(java, home, tmp),
                    classpath=classpath_identity(root, spec['dependencies']))
    deadline = time.monotonic() + 100 if coordinator is None else coordinator.deadline
    # Case 7: real stdin EOF with no ACK. READY may exist; no action artifact may.
    if coordinator is None:
        rejected = base / 'no-ack'
        execution.reserve_execution(budget.quota, **ALLOWANCES)
        command = execution.command_for(manifest, root, java, rejected, 'b'*64, request, **ALLOWANCES)
        result = run_process(command, root, base, 'no-ack', budget, seconds=30,
                             total_deadline=deadline, environment=manifest['environment'])
        assert result['state'] == 'EXITED' and result['returncode'] != 0, result
        assert {p.name for p in rejected.iterdir()} == {'ready.json'}
    # Case 9: the real observation/proc/ACK/EOF/sole Replay.run/five-file path.
    with patch.object(execution, 'verify_prepared', return_value=manifest):
        if coordinator is None:
            result = execution.run_execution(root, java, {'sha256': 'a'*64}, base / 'jvm', base, budget,
                        nonce='b'*64, request=request, total_deadline=deadline, **ALLOWANCES)
        else:
            result = coordinator.execute(root, java, {'sha256': 'a'*64}, base / 'jvm', base,
                        nonce='b'*64, request=request, **ALLOWANCES)
    assert result['state'] == execution.SUCCESS and result['returncode'] == 0 and result['ack_sent'], result
    verified = result['verification']
    assert verified['journal_state'] == 'COMPLETE' and verified['actions_started'] is True, result
    assert all(verified[name] is False for name in execution.FLAGS), result
    assert {p.name for p in (base / 'jvm').iterdir()} == {
        'ready.json', 'journal.jsonl', 'report-request.json', 'report.json', 'replayed.json'}
    request.recheck()


if __name__ == '__main__':
    unittest.main()
