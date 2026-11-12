"""Byte pipes/files and injected native transports only; no native probe."""
import io
import hashlib
import json
import os
from pathlib import Path
import sys
import tempfile
import time
import unittest
from unittest.mock import Mock, patch

import fixed_witness_replay_coordinator_artifacts as artifacts
from fixed_witness_replay_quota import ByteQuota, OUTPUT_BYTES, OutputLimitError, write_exclusive
from fixed_witness_replay_stdio import CoordinatorOutput
from fixed_witness_replay_supervisor import TracerSupervisor
from fixed_witness_replay_writers import reserve_writers, COORDINATOR_BYTES
import test_fixed_witness_replay_supervisor as supervisor_fixture


class CoordinatorArtifactsTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.directory = Path(temporary.name).resolve()
        self.deadline = time.monotonic() + 20
        self.allowance = 1024 * 1024
        self.root = ByteQuota()
        self.writers = reserve_writers(self.root)

    def pipe(self, raw, *, eof=True):
        read, write = os.pipe()
        self.addCleanup(os.close, read)
        os.write(write, raw)
        if eof:
            os.close(write)
        else:
            self.addCleanup(os.close, write)
        return read

    def raw(self, parent=None, pid=None, allowance=None, nanos=10_000_000_000):
        return ('FIXED_ARTIFACTS_V1 %d %d %d %d\n' % (
            os.getppid() if parent is None else parent, os.getpid() if pid is None else pid,
            self.allowance if allowance is None else allowance, nanos)).encode('ascii')

    def receive(self, raw=None):
        return artifacts.CoordinatorArtifacts.receive(
            self.pipe(self.raw() if raw is None else raw), self.directory, self.deadline)

    def grant(self):
        grant = artifacts.CoordinatorArtifactGrant(
            self.writers.coordinator, self.directory, self.allowance, self.deadline)
        self.addCleanup(grant.close)
        return grant

    def test_reservation_removes_child_credit_before_any_pipe_write(self):
        grant = self.grant()
        self.assertEqual(self.allowance, self.writers.coordinator.charged)
        self.assertEqual(OUTPUT_BYTES, self.root.charged)
        self.assertFalse(grant.sent)
        self.assertFalse(hasattr(grant, 'quota'))
        self.assertEqual([], list(self.directory.iterdir()))

    def test_second_transfer_and_failed_reservation_never_retry(self):
        self.writers.coordinator.charge(COORDINATOR_BYTES - 1)
        with self.assertRaises(OutputLimitError):
            self.grant()
        with self.assertRaisesRegex(ValueError, 'attempted'):
            self.grant()

    def test_pipe_creation_failure_keeps_permanent_debit(self):
        with patch.object(artifacts.os, 'pipe', side_effect=OSError('pipe failed')):
            with self.assertRaises(OSError):
                self.grant()
        self.assertEqual(self.allowance, self.writers.coordinator.charged)
        with self.assertRaises(ValueError):
            self.grant()

    def test_grant_binds_exact_launch_pid_and_original_deadline(self):
        grant = self.grant()
        launch = Mock(attempted=False, owner=os.getpid(), deadline=self.deadline,
                      stdio=(grant.reader, 20, 21))
        grant.claim(launch)
        launch.attempted, launch.cleanup_tid, launch.stopped = True, os.getpid() + 1, None
        launch.clock.return_value = self.deadline - 2
        grant.send(launch)
        reader = os.dup(grant.reader)
        self.addCleanup(os.close, reader)
        grant.close()
        expected = ('FIXED_ARTIFACTS_V1 %d %d %d 2000000000\n' % (
            os.getpid(), launch.cleanup_tid, self.allowance)).encode('ascii')
        self.assertEqual(expected, os.read(reader, 200))
        self.assertEqual(b'', os.read(reader, 1))
        launch._owner_and_deadline.assert_called_once()
        with self.assertRaises(ValueError):
            grant.send(launch)

    def test_wrong_launch_and_failed_send_cannot_reissue(self):
        grant = self.grant()
        launch = Mock(attempted=False, owner=os.getpid(), deadline=self.deadline,
                      stdio=(grant.reader, 20, 21))
        grant.claim(launch)
        launch.attempted, launch.cleanup_tid, launch.stopped = True, os.getpid() + 1, None
        launch.clock.return_value = time.monotonic()
        with patch.object(artifacts.os, 'write', side_effect=BlockingIOError()):
            with self.assertRaises(BlockingIOError):
                grant.send(launch)
        with self.assertRaises(ValueError):
            grant.send(launch)
        self.assertEqual(self.allowance, self.writers.coordinator.charged)

    def test_received_writer_claim_and_terminal_reserve_are_same_allowance(self):
        child = self.receive()
        self.assertEqual(artifacts.TERMINAL_BYTES + len(self.raw()), child.budget.quota.charged)
        self.assertEqual(self.raw(), (self.directory / 'coordinator-artifact-claim.txt').read_bytes())
        self.assertLessEqual(child.deadline, self.deadline)
        with self.assertRaises(FileExistsError):
            self.receive()

    def test_each_grant_identity_and_bounds_rejected_before_writing(self):
        for raw in (self.raw(parent=0), self.raw(pid=os.getpid()+1),
                    self.raw(allowance=4096), self.raw(allowance=COORDINATOR_BYTES),
                    self.raw(nanos=0), self.raw(nanos=900_000_000_001)):
            with self.subTest(raw=raw), self.assertRaises(ValueError):
                self.receive(raw)
        self.assertEqual([], list(self.directory.iterdir()))

    def test_noncanonical_oversized_truncated_or_extra_data_rejected(self):
        for raw in (b'', self.raw()[:-1], self.raw()+b'\n', b'x'*161,
                    self.raw().replace(b'FIXED_ARTIFACTS_V1', b'OTHER'),
                    self.raw().replace(b' 10000000000', b' 010000000000')):
            with self.subTest(raw=raw), self.assertRaises(ValueError):
                self.receive(raw)

    def test_original_deadline_requires_eof_and_cannot_extend(self):
        clock = Mock(side_effect=[1., 1., 2.])
        with self.assertRaisesRegex(ValueError, 'deadline'):
            artifacts.CoordinatorArtifacts.receive(
                self.pipe(self.raw(), eof=False), self.directory, 2., clock=clock)
        self.assertEqual([], list(self.directory.iterdir()))

    def test_regular_file_and_invalid_deadlines_rejected(self):
        for deadline in (True, float('nan'), float('inf'), 0, time.monotonic()+901):
            with self.subTest(deadline=deadline), self.assertRaises(ValueError):
                artifacts.CoordinatorArtifacts.receive(self.pipe(self.raw()), self.directory, deadline)
        path = self.directory / 'file'
        path.write_bytes(self.raw())
        with path.open('rb') as stream, self.assertRaisesRegex(ValueError, 'pipe'):
            artifacts.CoordinatorArtifacts.receive(stream.fileno(), self.directory, self.deadline)

    def test_original_owner_checked_before_build_or_jvm(self):
        child = self.receive()
        with patch.object(artifacts.os, 'getppid', return_value=0), \
                patch('fixed_witness_replay_prepare.prepare') as build:
            with self.assertRaisesRegex(ValueError, 'owner'):
                child.prepare('source', 'jdk', 'evidence', build_storage='build')
            build.assert_not_called()
        with self.assertRaises(ValueError):
            child.prepare('source', 'jdk', 'evidence', build_storage='build')

    def test_existing_prepare_and_verify_share_exact_budget_and_earlier_deadline(self):
        child = self.receive()
        receipt = {'manifest': 'retained', 'sha256': 'a'*64}
        def build(*args, **kwargs):
            self.assertIs(child.budget, args[3])
            self.assertEqual(child.deadline, kwargs['total_deadline'])
            write_exclusive(self.directory / 'manifest', b'build', args[3].quota)
            return receipt
        def verify(*args, **kwargs):
            self.assertIs(child.budget, args[5])
            self.assertEqual(child.deadline, kwargs['total_deadline'])
            args[5].quota.reserve(kwargs['artifact_bytes'])
            return {'state': 'VERIFIED_NO_REPLAY'}
        with patch('fixed_witness_replay_prepare.prepare', side_effect=build), \
                patch('fixed_witness_replay_pipe.run_verify_only', side_effect=verify):
            self.assertIs(receipt, child.prepare('s', 'j', 'e', build_storage='b'))
            result = child.verify_only('s', 'j', receipt, 'o', 'l', nonce='b'*64, artifact_bytes=100)
        self.assertEqual('VERIFIED_NO_REPLAY', result['state'])
        self.assertEqual(4096 + len(self.raw()) + 5 + 100, child.budget.quota.charged)
        self.assertFalse(json.loads((self.directory / 'coordinator-artifact-terminal.json').read_bytes())[
            'diagnostic_started'])
        with self.assertRaises(ValueError):
            child.verify_only('s', 'j', receipt, 'o', 'l', nonce='b'*64, artifact_bytes=100)

    def test_build_failure_retains_terminal_and_original_no_retry(self):
        child = self.receive()
        failure = OSError('build failed')
        with patch('fixed_witness_replay_prepare.prepare', side_effect=failure):
            with self.assertRaises(OSError) as raised:
                child.prepare('s', 'j', 'e', build_storage='b')
        self.assertIs(failure, raised.exception)
        self.assertEqual('PREPARE_FAILED', json.loads((
            self.directory / 'coordinator-artifact-terminal.json').read_bytes())['state'])
        with self.assertRaises(ValueError):
            child.verify_only('s', 'j', {}, 'o', 'l', nonce='b'*64, artifact_bytes=1)

    def test_actual_verify_prelaunch_jvm_reservation_denies_after_build_consumption(self):
        child = self.receive()
        child.phase = 'PREPARED'
        child.budget.quota.charge(child.budget.quota.remaining - 15)
        with patch('fixed_witness_replay_pipe.verify_prepared', return_value={'environment': {}}), \
                patch('fixed_witness_replay_pipe.command_for', return_value=['artificial']), \
                patch('fixed_witness_replay_pipe.run_process') as launch:
            with self.assertRaises(OutputLimitError):
                child.verify_only(self.directory, self.directory, {}, self.directory/'new',
                                  self.directory, nonce='a'*64, artifact_bytes=16)
            launch.assert_not_called()
        self.assertEqual(15, child.budget.quota.remaining)
        self.assertEqual('VERIFY_FAILED', json.loads((
            self.directory / 'coordinator-artifact-terminal.json').read_bytes())['state'])

    def test_parent_pipe_credit_and_child_artifacts_are_disjoint(self):
        self.grant()
        child = self.receive()
        pipe = io.BytesIO()
        remaining = self.writers.coordinator.remaining
        self.writers.coordinator.charge(remaining - 3)
        from fixed_witness_replay_process import OutputBudget
        self.assertTrue(OutputBudget(quota=self.writers.coordinator).consume(b'abcdef', pipe))
        write_exclusive(self.directory / 'child', b'still child credit', child.budget.quota)
        self.assertEqual(b'abc', pipe.getvalue())
        self.assertEqual(0, self.writers.coordinator.remaining)
        self.assertEqual(OUTPUT_BYTES, self.root.charged)

    def test_real_supervisor_sends_after_original_launch_before_wait_and_closes(self):
        f = supervisor_fixture.SupervisorTest()
        f.setUp()
        f.launch.owner = os.getpid()
        f.f.stub.parent_pid = os.getpid()
        f.f.system.getpid.return_value = os.getpid()
        f.f.system.listdir.return_value = [str(os.getpid())]
        f.ledger._tasks[0].tid = os.getpid()
        f.launch.deadline, f.launch.clock = self.deadline, time.monotonic
        output = CoordinatorOutput(self.writers, self.directory)
        reader = output.reserve_artifacts(self.directory, self.allowance, self.deadline)
        child_stdin = os.dup(reader)
        self.addCleanup(os.close, child_stdin)
        f.launch.stdio = (reader, *output.writers)
        owner = f.owner(output=output)
        original = owner.waitpid
        def wait(tid, flags):
            if not hasattr(wait, 'received'):
                wait.received = os.read(child_stdin, MAX_READ)
                self.assertTrue(wait.received.startswith(b'FIXED_ARTIFACTS_V1 '))
                self.assertEqual(b'', os.read(child_stdin, 1))
            return original(tid, flags)
        MAX_READ = 200
        owner.waitpid = wait
        owner.run()
        self.assertTrue(owner.drained)
        self.assertTrue(output.artifacts.sent and output.artifacts.closed)
        self.assertEqual(self.allowance, self.writers.coordinator.charged)

    def test_factory_passes_exclusive_stdin_and_source_handoff_configuration(self):
        import test_fixed_witness_replay_writer_handoff as host_fixture
        read = self.pipe(host_fixture.grant())
        from fixed_witness_replay_writer_handoff import HostWriterHandoff
        host = HostWriterHandoff.receive(read, self.directory, self.deadline)
        with patch('fixed_witness_replay_supervisor.TracerSupervisor.for_host', return_value='owner') as factory:
            self.assertEqual('owner', host.prepare(Mock(), ['fixed'], '/fixed', {}, None,
                             prepared_probes=(1, 2, 3, 4), artifact_bytes=self.allowance))
        self.assertIsNone(factory.call_args.args[4])
        self.assertEqual(self.allowance, factory.call_args.kwargs['artifact_bytes'])

    def test_terminal_disk_failure_keeps_original_build_failure_and_charge(self):
        child = self.receive()
        failure = OSError('original build error')
        with patch('fixed_witness_replay_prepare.prepare', side_effect=failure), \
                patch.object(artifacts, 'write_exclusive', side_effect=OSError('disk full')):
            with self.assertRaises(OSError) as raised:
                child.prepare('s', 'j', 'o', build_storage='b')
        self.assertIs(failure, raised.exception)
        self.assertIsNotNone(child.terminal_error)
        self.assertEqual('FINISHED', child.phase)

    @unittest.skipUnless(sys.platform == 'linux', 'artificial Linux build processes required')
    def test_actual_prepare_uses_received_allowance_without_new_root(self):
        import test_fixed_witness_replay_prepare as preparation_fixture
        fixture = preparation_fixture.PreparationTest()
        fixture.setUp()
        self.addCleanup(fixture.doCleanups)
        self.addCleanup(fixture.directory.cleanup)
        child = self.receive()
        receipt = child.prepare(fixture.root, fixture.jdk, fixture.out, build_storage=fixture.build)
        self.assertEqual('PREPARED', child.phase)
        manifest = json.loads(Path(receipt['manifest']).read_bytes())
        self.assertFalse(manifest['diagnostic_started'])
        self.assertEqual(4096 + len(self.raw()) + 4096 + sum(
            path.stat().st_size for path in fixture.out.iterdir()), child.budget.quota.charged)


class CoordinatorModesTest(unittest.TestCase):
    setUp = CoordinatorArtifactsTest.setUp
    pipe = CoordinatorArtifactsTest.pipe
    raw = CoordinatorArtifactsTest.raw
    receive = CoordinatorArtifactsTest.receive
    def mode(self, child, execution):
        function = child.execute if execution else child.report_only
        limits = dict(handshake_bytes=100, report_bytes=200)
        if execution:
            limits.update(journal_bytes=300, declaration_bytes=400)
        return function, limits

    def prepared(self):
        child = self.receive()
        with patch('fixed_witness_replay_prepare.prepare', return_value={'sha256': 'a'*64}):
            child.prepare('s', 'j', 'e', build_storage='b')
        return child

    def call(self, child, execution):
        function, limits = self.mode(child, execution)
        return function(self.directory, self.directory, {}, self.directory/'new', self.directory,
                        nonce='a'*64, request=Mock(), **limits)

    def test_modes_share_original_budget_and_are_mutually_exclusive(self):
        for execution in (False, True):
            with self.subTest(execution=execution), tempfile.TemporaryDirectory() as name:
                self.directory = Path(name).resolve()
                child = self.prepared()
                before = child.budget.quota.charged
                target = ('fixed_witness_replay_execution.run_execution' if execution
                          else 'fixed_witness_report_pipe.run_report_only')
                def run(*args, **kwargs):
                    self.assertIs(child.budget, args[5])
                    self.assertEqual(child.deadline, kwargs['total_deadline'])
                    self.assertEqual('a'*64, kwargs['nonce'])
                    args[5].quota.reserve(sum(self.mode(child, execution)[1].values()))
                    return dict(state='REPLAY_REPORTED' if execution else 'REPORT_WRITTEN_NO_REPLAY',
                                verification={'actions_started': True})
                with patch(target, side_effect=run):
                    self.call(child, execution)
                self.assertEqual(before + (1000 if execution else 300), child.budget.quota.charged)
                record = json.loads((self.directory/'coordinator-artifact-terminal.json').read_bytes())
                if execution:
                    self.assertTrue(record['actions_started'])
                    self.assertNotIn('diagnostic_started', record)
                    self.assertFalse(record['diagnostic_authorized'])
                for other in (False, True):
                    with self.assertRaisesRegex(ValueError, 'stage'):
                        self.call(child, other)
                with self.assertRaises(ValueError):
                    child.verify_only('s', 'j', {}, 'o', 'l', nonce='a'*64, artifact_bytes=1)

    def test_real_mode_reservations_reject_before_spawn_after_prepare_consumption(self):
        for execution in (False, True):
            with self.subTest(execution=execution), tempfile.TemporaryDirectory() as name:
                self.directory = Path(name).resolve()
                child = self.prepared()
                need = 1000 if execution else 300
                child.budget.quota.charge(child.budget.quota.remaining - need + 1)
                module = 'fixed_witness_replay_execution' if execution else 'fixed_witness_report_pipe'
                with patch(module+'.verify_prepared', return_value={'environment': {}}), \
                        patch(module+'.command_for', return_value=['artificial']), \
                        patch(module+'.run_process') as spawn:
                    with self.assertRaises(OutputLimitError):
                        self.call(child, execution)
                    spawn.assert_not_called()
                self.assertEqual(need-1, child.budget.quota.remaining)
                with self.assertRaises(ValueError):
                    self.call(child, not execution)

    def test_real_mode_reservations_remain_spent_after_launch_failure(self):
        for execution in (False, True):
            with self.subTest(execution=execution), tempfile.TemporaryDirectory() as name:
                self.directory = Path(name).resolve()
                child = self.prepared()
                before = child.budget.quota.charged
                failure = OSError('after reservation')
                module = 'fixed_witness_replay_execution' if execution else 'fixed_witness_report_pipe'
                with patch(module+'.verify_prepared', return_value={'environment': {}}), \
                        patch(module+'.command_for', return_value=['artificial']), \
                        patch(module+'.run_process', side_effect=failure):
                    with self.assertRaises(OSError) as raised:
                        self.call(child, execution)
                self.assertIs(failure, raised.exception)
                self.assertEqual(before + (1000 if execution else 300), child.budget.quota.charged)
                record = json.loads((self.directory/'coordinator-artifact-terminal.json').read_bytes())
                self.assertEqual('EXECUTION_FAILED' if execution else 'REPORT_FAILED', record['state'])
                if execution:
                    self.assertIsNone(record['actions_started'])
                    self.assertNotIn('diagnostic_started', record)

    def test_owner_or_deadline_failure_consumes_mode_without_launch(self):
        for execution in (False, True):
            for invalid in ('owner', 'deadline'):
                with self.subTest(execution=execution, invalid=invalid), tempfile.TemporaryDirectory() as name:
                    self.directory = Path(name).resolve()
                    child = self.prepared()
                    if invalid == 'deadline':
                        child.deadline = child.clock()
                    with patch.object(artifacts.os, 'getpid', return_value=0 if invalid == 'owner' else child.pid):
                        with self.assertRaisesRegex(ValueError, 'owner/deadline'):
                            self.call(child, execution)
                    with self.assertRaisesRegex(ValueError, 'stage'):
                        self.call(child, not execution)

    def test_unprepared_receiver_cannot_start_either_mode(self):
        child = self.receive()
        for execution in (False, True):
            with self.assertRaisesRegex(ValueError, 'stage'):
                self.call(child, execution)
        self.assertEqual('RECEIVED', child.phase)

    def test_execution_failure_terminal_error_never_masks_original(self):
        child = self.prepared()
        failure = OSError('original execution error')
        with patch('fixed_witness_replay_execution.run_execution', side_effect=failure), \
                patch.object(artifacts, 'write_exclusive', side_effect=OSError('terminal error')):
            with self.assertRaises(OSError) as raised:
                self.call(child, True)
        self.assertIs(failure, raised.exception)
        self.assertIsNotNone(child.terminal_error)
        self.assertEqual('FINISHED', child.phase)

    def test_non_success_execution_result_does_not_claim_no_actions(self):
        child = self.prepared()
        with patch('fixed_witness_replay_execution.run_execution', return_value={'state': 'TIMED_OUT'}):
            self.call(child, True)
        record = json.loads((self.directory/'coordinator-artifact-terminal.json').read_bytes())
        self.assertEqual('TIMED_OUT', record['state'])
        self.assertIsNone(record['actions_started'])
        self.assertNotIn('diagnostic_started', record)


class CoordinatorRequestTest(unittest.TestCase):
    setUp = CoordinatorArtifactsTest.setUp
    pipe = CoordinatorArtifactsTest.pipe
    raw = CoordinatorArtifactsTest.raw
    receive = CoordinatorArtifactsTest.receive
    prepared = CoordinatorModesTest.prepared

    def stage(self, child, execution=True):
        if execution:
            from test_fixed_witness_replay_execution import fixture
        else:
            from test_fixed_witness_report_pipe import fixture
        return fixture(self.directory/'snapshot', child.budget.quota, child)

    def test_original_copy_budget_and_receipt_reach_selected_transport(self):
        for execution in (False, True):
            with self.subTest(execution=execution), tempfile.TemporaryDirectory() as name:
                self.directory = Path(name).resolve()
                child = self.prepared()
                before = child.budget.quota.charged
                request = self.stage(child, execution)
                copied = sum(p.stat().st_size for p in request.directory.iterdir())
                self.assertEqual(before+copied, child.budget.quota.charged)
                request.recheck()
                target = ('fixed_witness_replay_execution.run_execution' if execution
                          else 'fixed_witness_report_pipe.run_report_only')
                with patch(target, return_value={'state': 'ARTIFICIAL'}) as run:
                    function, limits = CoordinatorModesTest.mode(self, child, execution)
                    function('s', 'j', {}, 'o', 'l', nonce='a'*64, request=request, **limits)
                self.assertIs(request, run.call_args.kwargs['request'])
                self.assertIs(child.budget, run.call_args.args[5])
                self.assertEqual(child.deadline, run.call_args.kwargs['total_deadline'])
                self.assertEqual(before+copied, child.budget.quota.charged)

    def test_exhausted_copy_does_not_create_directory_or_launch(self):
        child = self.prepared()
        child.budget.quota.charge(child.budget.quota.remaining-1)
        before = child.budget.quota.charged
        with self.assertRaises(OutputLimitError):
            self.stage(child)
        self.assertFalse((self.directory/'snapshot').exists())
        self.assertEqual(before, child.budget.quota.charged)
        self.assertEqual('FINISHED', child.phase)

    def test_partial_write_keeps_entire_copy_debit_and_original_error(self):
        child = self.prepared()
        before = child.budget.quota.charged
        original = OSError('copy failed')
        with patch.object(artifacts, 'write_exclusive', side_effect=original):
            with self.assertRaises(OSError) as raised:
                self.stage(child)
        self.assertIs(original, raised.exception)
        self.assertGreater(child.budget.quota.charged, before)
        self.assertIs(original, child.terminal_error)
        self.assertTrue((self.directory/'snapshot').is_dir())
        with self.assertRaisesRegex(ValueError, 'stage'):
            self.stage(child)

    def test_independent_declaration_mismatch_keeps_failed_prefix(self):
        child = self.prepared()
        from test_fixed_witness_replay_execution import fixture
        saved = fixture(self.directory/'original', ByteQuota())
        payload = {p.name: p.read_bytes() for p in saved.directory.iterdir()}
        with self.assertRaisesRegex(ValueError, 'differs from parent'):
            child.stage_request(self.directory/'snapshot', payload, mode='execute',
                                declaration=saved.declaration+b' ', expected_sha256=hashlib.sha256(
                                    saved.declaration+b' ').hexdigest())
        self.assertEqual(payload['request.json'], (self.directory/'snapshot'/'request.json').read_bytes())
        self.assertEqual('REQUEST_FAILED', json.loads(
            (self.directory/'coordinator-artifact-terminal.json').read_bytes())['state'])

    def test_retained_copy_drift_is_not_refrozen(self):
        child = self.prepared()
        request = self.stage(child)
        (request.directory/'input.csv').write_bytes(b'drift')
        with self.assertRaisesRegex(ValueError, 'drift'):
            request.recheck()
        with self.assertRaisesRegex(ValueError, 'already attempted'):
            self.stage(child)

    def test_wrong_mode_or_receipt_never_reaches_transport(self):
        for wrong in ('mode', 'receipt', 'verify'):
            with self.subTest(wrong=wrong), tempfile.TemporaryDirectory() as name:
                self.directory = Path(name).resolve()
                child = self.prepared()
                request = self.stage(child)
                with patch('fixed_witness_replay_execution.run_execution') as execute, \
                        patch('fixed_witness_report_pipe.run_report_only') as report, \
                        patch('fixed_witness_replay_pipe.run_verify_only') as verify:
                    with self.assertRaisesRegex(ValueError, 'staged request'):
                        if wrong == 'verify':
                            child.verify_only('s', 'j', {}, 'o', 'l', nonce='a'*64, artifact_bytes=10)
                        else:
                            function, limits = CoordinatorModesTest.mode(self, child, wrong != 'mode')
                            function('s', 'j', {}, 'o', 'l', nonce='a'*64,
                                     request=Mock() if wrong == 'receipt' else request, **limits)
                execute.assert_not_called()
                report.assert_not_called()
                verify.assert_not_called()

    def test_owner_and_expiry_rejected_before_copy(self):
        for invalid in ('owner', 'deadline'):
            with self.subTest(invalid=invalid), tempfile.TemporaryDirectory() as name:
                self.directory = Path(name).resolve()
                child = self.prepared()
                if invalid == 'deadline':
                    child.deadline = child.clock()
                with patch.object(artifacts.os, 'getpid', return_value=0 if invalid == 'owner' else child.pid):
                    with self.assertRaisesRegex(ValueError, 'owner/deadline'):
                        self.stage(child)
                self.assertFalse((self.directory/'snapshot').exists())

    def test_wrong_file_set_or_oversize_rejected_before_reservation(self):
        for payload in ({'extra': b'x'}, {'input.csv': b'x'*65537, 'request.json': b'x'}):
            with self.subTest(names=tuple(payload)), tempfile.TemporaryDirectory() as name:
                self.directory = Path(name).resolve()
                child = self.prepared()
                before = child.budget.quota.charged
                with self.assertRaises(ValueError):
                    child.stage_request(self.directory/'snapshot', payload, mode='execute',
                                        declaration=b'x', expected_sha256='a'*64)
                self.assertEqual(before, child.budget.quota.charged)
                self.assertFalse((self.directory/'snapshot').exists())

    def test_existing_directory_is_preserved_with_permanent_debit(self):
        child = self.prepared()
        path = self.directory/'snapshot'
        path.mkdir()
        (path/'user').write_bytes(b'preserve')
        before = child.budget.quota.charged
        with self.assertRaises(FileExistsError):
            self.stage(child)
        self.assertEqual(b'preserve', (path/'user').read_bytes())
        self.assertGreater(child.budget.quota.charged, before)
        self.assertEqual(['user'], [p.name for p in path.iterdir()])


def ordinary_jvm_fixture(spec):
    """Actual inherited grant -> one selected ordinary JVM; build receipt mocked.

    Ordinary CI subprocesses only. No native tracer/ptrace/fork probe, clean
    network build, historical fixture, or whole-source provenance is claimed.
    Verify/report do not execute actions; execution uses one artificial placement.
    """
    import subprocess
    root = Path(spec['root']).resolve()
    session = Path(spec['out']).resolve()
    writers = reserve_writers(ByteQuota())
    deadline = time.monotonic() + 90
    grant = artifacts.CoordinatorArtifactGrant(writers.coordinator, session, 4*1024*1024, deadline)
    code = ("import json,sys,time; sys.path.insert(0,'scripts'); "
            "from test_fixed_witness_replay_coordinator_artifacts import ordinary_jvm_child; "
            "ordinary_jvm_child(json.loads(sys.argv[1]))")
    child = None
    try:
        child = subprocess.Popen([sys.executable, '-B', '-c', code, json.dumps(spec)], cwd=root,
                                 stdin=grant.reader, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
        launch = Mock(attempted=False, owner=os.getpid(), deadline=deadline,
                      stdio=(grant.reader, 20, 21))
        grant.claim(launch)
        launch.attempted, launch.cleanup_tid, launch.stopped = True, child.pid, None
        launch.clock = time.monotonic
        grant.send(launch)
        grant.close()
        _, stderr = child.communicate(timeout=80)
        assert child.returncode == 0, stderr.decode('utf-8', errors='replace')
        assert writers.coordinator.charged == 4*1024*1024
        record = json.loads((session / 'coordinator-artifact-terminal.json').read_bytes())
        mode = spec.get('mode', 'verify')
        assert record['state'] == {'verify': 'VERIFIED_NO_REPLAY', 'report': 'REPORT_WRITTEN_NO_REPLAY',
                                   'execute': 'REPLAY_REPORTED'}[mode], record
        if mode == 'execute':
            assert record['actions_started'] is True and not record['diagnostic_authorized'], record
        else:
            assert not record['diagnostic_started'], record
    finally:
        grant.close()
        if child is not None:
            if child.poll() is None:
                child.kill()
                child.wait(timeout=5)
            if child.stderr is not None:
                child.stderr.close()


def ordinary_jvm_child(spec):
    from test_fixed_witness_replay_pipe import artificial_jvm
    child = artifacts.CoordinatorArtifacts.receive(0, spec['out'], time.monotonic()+90)
    # Match the existing artificial_jvm receipt limitation explicitly. Still
    # call the actual coordinator prepare stage and its exact budget writer.
    def build(root, java, output, budget, **kwargs):
        write_exclusive(Path(spec['out']) / 'fixture-build-receipt.json',
                        b'{"artificial_build":true}\n', budget.quota)
        return {'sha256': 'a'*64}
    with patch('fixed_witness_replay_prepare.prepare', side_effect=build):
        child.prepare(spec['root'], spec['java'], 'artificial', build_storage='artificial')
    mode = spec.get('mode', 'verify')
    if mode == 'verify':
        artificial_jvm(spec, child)
    elif mode == 'report':
        from test_fixed_witness_report_pipe import artificial_report_jvm
        artificial_report_jvm(spec, child)
    elif mode == 'execute':
        from test_fixed_witness_replay_execution import artificial_execution_jvm
        artificial_execution_jvm(spec, child)
    else:
        raise ValueError('unknown artificial mode')


if __name__ == '__main__':
    unittest.main()
