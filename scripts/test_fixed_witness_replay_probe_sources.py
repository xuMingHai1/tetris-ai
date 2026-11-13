"""Prepared source -> original supervisor, all kernel and file I/O injected."""
import hashlib
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

from fixed_witness_replay_abi_observation import AbiObservationError
from fixed_witness_replay_network import policy_hash
from fixed_witness_replay_probe_sources import PreparedProbeSources, PROBE, LAUNCHER, SOURCE_ROOTS
from fixed_witness_replay_supervisor import TracerSupervisor, SupervisorError
from fixed_witness_replay_stdio import CoordinatorOutput
from fixed_witness_replay_tracer_evidence import TracerEvidence
from fixed_witness_replay_writers import reserve_writers
from fixed_witness_replay_quota import ByteQuota
from fixed_witness_replay_ptrace import PtraceAccess, SignalInfo, decode_syscall_info
import test_fixed_witness_replay_abi_observation as file_fixture
import test_fixed_witness_replay_supervisor as supervisor_fixture
import test_fixed_witness_replay_filter_exec_chain as chain_fixture
from test_fixed_witness_replay_ptrace import entry, exit_info


def digest(data):
    return hashlib.sha256(data).hexdigest()


class ProbeSourcesTest(unittest.TestCase):
    def setUp(self):
        fixture = file_fixture.AbiObservationTest()
        fixture.setUp()
        self.files = fixture.files
        self.root = '/frozen'
        self.source = self.root + '/' + PROBE
        self.launcher = self.root + '/' + LAUNCHER
        self.python = fixture.record.interpreter
        self.library = '/usr/lib/libseccomp.so.2'
        for path, data in ((self.source, b'# artificial probe bytes\n'),
                           (self.launcher, b'# artificial launcher bytes\n'),
                           (self.library, b'artificial library bytes')):
            self.put(path, data)
        self.files.canon[self.root] = self.root
        self.manifest = {'state': 'PREPARED', 'diagnostic_started': False,
                         'source': {'source_sha': 'a' * 40, 'source_files': {
                             PROBE: digest(self.files.data[self.source]),
                             LAUNCHER: digest(self.files.data[self.launcher])}},
                         'classpath': {'classpath': ['/frozen/target/classes', '/frozen/target/test-classes']}}
        for name in SOURCE_ROOTS:
            if name not in self.manifest['source']['source_files']:
                self.put(self.root + '/' + name, b'# artificial supervisor source\n')
                self.manifest['source']['source_files'][name] = digest(self.files.data[self.root + '/' + name])
        self.receipt = {'manifest': '/evidence/manifest.json', 'sha256': ''}
        self.retain_manifest()
        self.networks = tuple({
            'schema': 'fixed-witness-network-v1', 'python': self.python,
            'python_sha256': digest(self.files.data[self.python]),
            'launcher': self.launcher, 'launcher_sha256': digest(self.files.data[self.launcher]),
            'library': {'path': self.library, 'sha256': digest(self.files.data[self.library]),
                        'version': [2, 6, 0]},
            'policy_sha256': policy_hash(),
            'command': [self.python, '-I', '-B', self.source, mode], 'inherited_filters': 1,
        } for mode in ('x32', 'compat'))
        self.check = Mock()

    def put(self, path, data):
        self.files.data[path] = data
        self.files.meta[path] = file_fixture.metadata(len(data))
        self.files.canon[path] = path

    def retain_manifest(self):
        raw = json.dumps(self.manifest, sort_keys=True).encode()
        self.put(self.receipt['manifest'], raw)
        self.receipt['sha256'] = digest(raw)

    def sources(self, **kwargs):
        return PreparedProbeSources(self.root, self.receipt, kwargs.pop('births', (2, 3)),
                                    kwargs.pop('networks', self.networks), self.check,
                                    files=self.files, **kwargs)

    def python_source(self, name, raw):
        self.put(self.root + '/' + name, raw)
        self.manifest['source']['source_files'][name] = digest(raw)
        self.retain_manifest()

    def test_deferred_import_missing_from_original_manifest_is_rejected(self):
        self.python_source(SOURCE_ROOTS[-1], b'def later():\n    from fixed_witness_missing import writer\n')
        with self.assertRaisesRegex(AbiObservationError, 'import missing'):
            self.sources()

    def test_each_required_root_must_be_in_independent_inventory(self):
        original = dict(self.manifest['source']['source_files'])
        for name in SOURCE_ROOTS:
            with self.subTest(name=name):
                self.manifest['source']['source_files'] = dict(original)
                del self.manifest['source']['source_files'][name]
                self.retain_manifest()
                with self.assertRaisesRegex(AbiObservationError, 'source roots'):
                    self.sources()

    def test_indirect_import_and_cycle_are_checked_without_execution(self):
        first = 'scripts/fixed_witness_first.py'
        second = 'scripts/fixed_witness_second.py'
        self.python_source(first, b'import fixed_witness_second\nraise AssertionError("must not execute")\n')
        self.python_source(second, b'import fixed_witness_first\n')
        sources = self.sources()
        self.assertIn((self.root+'/'+second, self.manifest['source']['source_files'][second],
                       1024*1024), sources._expected)
        self.put(self.root+'/'+second, b'import fixed_witness_first\n# drift\n')
        with self.assertRaises(AbiObservationError):
            sources.revalidate()
        self.assertIsNotNone(sources.observations.stopped)

    def test_supervisor_source_drift_prevents_actual_fork(self):
        sources = self.sources()
        f, owner = self.owner(sources)
        self.put(self.root+'/'+SOURCE_ROOTS[-1], b'# drift\n')
        with self.assertRaises(AbiObservationError):
            owner.run()
        f.f.system.fork.assert_not_called()
        self.assertEqual(1, f.ledger.totals.attempts)

    def test_supervisor_source_drift_after_reap_denies_drained_evidence(self):
        sources = self.sources()
        f, owner, path = self.evidenced_owner(sources)
        original = f.io.wait.side_effect
        def waited(*args):
            result = next(original)
            if result == (11, 0):
                self.put(self.root+'/'+SOURCE_ROOTS[-1], b'# terminal drift\n')
            return result
        # The normal fixture terminates with (11, 0); use its exact wait stream.
        f.io.wait.side_effect = waited
        with self.assertRaises(SupervisorError):
            owner.run()
        self.assertEqual(1, f.ledger.totals.live)
        self.assertFalse(owner.drained)
        self.assertFalse(json.loads((path/'tracer-lifecycle.json').read_bytes())['suite_verified'])
        self.assertIsNotNone(owner.source_error)

    def test_malformed_python_or_relative_import_never_becomes_selection(self):
        for raw in (b'not python !!!', b'from . import fixed_witness_replay_tasks\n'):
            with self.subTest(raw=raw):
                self.python_source(SOURCE_ROOTS[-1], raw)
                with self.assertRaises((AbiObservationError, SyntaxError)):
                    self.sources()

    def test_current_repository_static_inventory_is_closed(self):
        # Source bytes only: no module import, Git, native call or historical input.
        scripts = Path(__file__).resolve().parent
        for path in scripts.glob('*fixed_witness*.py'):
            self.python_source('scripts/'+path.name, path.read_bytes())
        for network in self.networks:
            network['launcher_sha256'] = self.manifest['source']['source_files'][LAUNCHER]
        sources = self.sources()
        names = {name for name, _, _ in sources._expected}
        self.assertTrue(all(self.root+'/'+name in names for name in SOURCE_ROOTS))

    def owner(self, sources):
        f = supervisor_fixture.SupervisorTest()
        f.setUp()
        owner = f.owner(sources=sources, exec_observations=sources.observations)
        return f, owner

    def evidenced_owner(self, sources):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        path = Path(directory.name)
        writers = reserve_writers(ByteQuota())
        output = CoordinatorOutput(writers, path)
        self.addCleanup(output.close)
        evidence = TracerEvidence(writers, path)
        f = supervisor_fixture.SupervisorTest()
        f.setUp()
        f.launch.stdio = (999, *output.writers)
        owner = f.owner(sources=sources, exec_observations=sources.observations,
                        output=output, evidence=evidence)
        return f, owner, path

    def test_source_recheck_precedes_actual_drained_evidence(self):
        sources = self.sources()
        f, owner, path = self.evidenced_owner(sources)
        owner.run()
        record = json.loads((path / 'tracer-lifecycle.json').read_bytes())
        self.assertEqual('DRAINED_OBSERVATION', record['state'])
        self.assertTrue(record['output_closed'])
        self.assertIsNone(owner.source_error)
        self.assertFalse(record['suite_verified'])

    def test_postreap_source_failure_writes_incomplete_evidence_after_close(self):
        sources = self.sources()
        f, owner, path = self.evidenced_owner(sources)
        def wait(*args):
            if f.io.wait.call_count == 2:
                self.put(self.source, b'changed after reap')
                return (11, 0)
            return (11, 0x137f)
        f.io.wait.side_effect = wait
        with self.assertRaisesRegex(SupervisorError, 'continuity'):
            owner.run()
        record = json.loads((path / 'tracer-lifecycle.json').read_bytes())
        self.assertEqual(('INCOMPLETE', True, True, 1),
                         tuple(record[k] for k in ('state', 'failed', 'output_closed', 'live')))
        self.assertIsNotNone(sources.observations.stopped)
        self.assertFalse(owner.drained)

    def test_prefork_source_failure_keeps_actual_evidence_and_no_native_attempt(self):
        sources = self.sources()
        f, owner, path = self.evidenced_owner(sources)
        self.put(self.launcher, b'changed before fork')
        with self.assertRaises(AbiObservationError):
            owner.run()
        record = json.loads((path / 'tracer-lifecycle.json').read_bytes())
        self.assertEqual('INCOMPLETE', record['state'])
        self.assertTrue(record['output_closed'])
        self.assertFalse(record['launch_attempted'])
        f.f.system.fork.assert_not_called()
        f.io.wait.assert_not_called()

    def test_source_and_evidence_failure_preserve_original_execution_error(self):
        sources = self.sources()
        f, owner, path = self.evidenced_owner(sources)
        original = OSError('original wait failure')
        def wait(*args):
            if f.io.wait.call_count == 2:
                self.put(self.library, b'changed during failure')
                raise original
            return (11, 0x137f) if f.io.wait.call_count == 1 else (11, 9)
        f.io.wait.side_effect = wait
        (path / 'tracer-lifecycle.json').write_bytes(b'existing')
        with self.assertRaises(OSError) as raised:
            owner.run()
        self.assertIs(original, raised.exception)
        self.assertIs(original, owner.failure)
        self.assertIsNotNone(owner.source_error)
        self.assertIsInstance(owner.evidence_error, FileExistsError)
        self.assertTrue(owner.output.closed)
        self.assertIsNone(owner.cleanup_error)
        self.assertEqual(1, f.ledger.totals.live)

    def test_evidence_failure_does_not_replace_new_source_failure(self):
        sources = self.sources()
        f, owner, path = self.evidenced_owner(sources)
        def wait(*args):
            if f.io.wait.call_count == 2:
                self.put(self.source, b'changed after reap')
                return (11, 0)
            return (11, 0x137f)
        f.io.wait.side_effect = wait
        (path / 'tracer-lifecycle.json').write_bytes(b'existing')
        with self.assertRaisesRegex(SupervisorError, 'continuity') as raised:
            owner.run()
        self.assertIs(owner.failure, raised.exception)
        self.assertIsInstance(owner.evidence_error, FileExistsError)
        charged = owner.evidence.quota.charged
        with self.assertRaises(SupervisorError):
            owner.run()
        self.assertEqual(charged, owner.evidence.quota.charged)

    def test_original_manifest_builds_exact_two_slots_and_network_launches(self):
        sources = self.sources()
        records = sources.observations._records
        self.assertEqual([2, 3], list(records))
        self.assertEqual(['x32', 'compat'], [r.mode for r in records.values()])
        self.assertEqual(self.manifest['source']['source_files'][PROBE], records[2].source_sha256)
        self.assertEqual((self.python, '-I', '-B', self.source, 'compat'), records[3].argv)
        self.assertEqual('UNBOUND', sources.observations.abi_state('x32'))
        self.assertEqual({}, self.files.fds)

    def test_wrong_independent_receipt_rejects_before_probe_read(self):
        self.receipt['sha256'] = 'b' * 64
        with self.assertRaisesRegex(AbiObservationError, 'digest mismatch'):
            self.sources()
        self.assertEqual([(self.receipt['manifest'], False)], self.files.opens)

    def test_current_probe_bytes_cannot_replace_manifest_expectation(self):
        self.put(self.source, b'new source')
        with self.assertRaisesRegex(AbiObservationError, 'bytes changed'):
            self.sources()

    def test_network_launcher_must_come_from_prepared_checkout(self):
        self.networks[0]['launcher'] = '/somewhere/launcher.py'
        with self.assertRaisesRegex(AbiObservationError, 'prepared source'):
            self.sources()

    def test_wrong_network_command_rejected(self):
        self.networks[1]['command'][-1] = 'x32'
        with self.assertRaisesRegex(ValueError, 'frozen probe'):
            self.sources()

    def test_interpreter_and_library_bytes_are_checked(self):
        for path in (self.python, self.library):
            with self.subTest(path=path):
                original = self.files.data[path]
                self.put(path, b'changed')
                with self.assertRaisesRegex(AbiObservationError, 'bytes changed'):
                    self.sources()
                self.put(path, original)

    def test_canonical_and_nonregular_files_rejected(self):
        self.files.canon[self.source] = '/other/source.py'
        with self.assertRaisesRegex(AbiObservationError, 'path changed'):
            self.sources()
        self.files.canon[self.source] = self.source
        self.files.meta[self.source] = file_fixture.metadata(1, mode=0o010000)
        with self.assertRaisesRegex(AbiObservationError, 'nonregular'):
            self.sources()

    def test_midread_deadline_failure_closes_without_retry(self):
        def hook(operation, path):
            if operation == 'read' and path == self.source:
                self.check.side_effect = ValueError('deadline')
        self.files.hook = hook
        with self.assertRaisesRegex(ValueError, 'deadline'):
            self.sources()
        self.assertEqual({}, self.files.fds)
        self.assertEqual(len(self.files.opens), len(self.files.closed))

    def test_failed_continuity_permanently_revokes_original_slots(self):
        sources = self.sources()
        original = self.files.data[self.source]
        self.put(self.source, b'changed')
        with self.assertRaises(AbiObservationError):
            sources.revalidate()
        self.put(self.source, original)
        with self.assertRaisesRegex(AbiObservationError, 'stopped'):
            sources.revalidate()
        self.assertIsNotNone(sources.observations.stopped)

    def test_source_failure_before_actual_launch_prevents_fork(self):
        sources = self.sources()
        f, owner = self.owner(sources)
        self.put(self.launcher, b'changed')
        with self.assertRaisesRegex(AbiObservationError, 'bytes changed'):
            owner.run()
        f.f.system.fork.assert_not_called()
        self.assertEqual([], f.io.mock_calls)
        self.assertFalse(owner.drained)
        self.assertIsNotNone(owner.source_error)

    def test_original_wait_loop_claims_same_observations_and_checks_after_reap(self):
        sources = self.sources()
        f, owner = self.owner(sources)
        owner.run()
        self.assertTrue(owner.drained)
        self.assertIs(owner.dispatcher._exec_observations, sources.observations)
        self.assertIs(sources.owner, owner)
        self.assertEqual(1, f.ledger.totals.live)
        with self.assertRaises(SupervisorError):
            owner.run()

    def test_source_drift_after_terminal_prevents_success_return(self):
        sources = self.sources()
        f, owner = self.owner(sources)
        def wait(*args):
            if f.io.wait.call_count == 2:
                self.put(self.source, b'post terminal change')
                return (11, 0)
            return (11, 0x137f)
        f.io.wait.side_effect = wait
        with self.assertRaisesRegex(SupervisorError, 'continuity'):
            owner.run()
        self.assertEqual(1, f.ledger.totals.live)
        self.assertFalse(owner.drained)
        self.assertIsNotNone(sources.observations.stopped)

    def test_source_error_does_not_hide_original_execution_or_cleanup(self):
        sources = self.sources()
        f, owner = self.owner(sources)
        original = OSError('wait failure')
        def wait(*args):
            if f.io.wait.call_count == 2:
                self.put(self.library, b'changed')
                raise original
            return (11, 0x137f) if f.io.wait.call_count == 1 else (11, 9)
        f.io.wait.side_effect = wait
        with self.assertRaises(OSError) as raised:
            owner.run()
        self.assertIs(original, raised.exception)
        self.assertIsNone(owner.cleanup_error)
        self.assertIsNotNone(owner.source_error)
        self.assertEqual(1, f.ledger.totals.live)

    def test_final_read_uses_actual_owner_deadline_even_with_foreign_host_callback(self):
        sources = self.sources()
        f, owner = self.owner(sources)
        def hook(operation, path):
            if operation == 'read' and path == self.receipt['manifest'] and f.io.wait.call_count == 2:
                f.f.clock.return_value = 100.0
        self.files.hook = hook
        with self.assertRaisesRegex(SupervisorError, 'continuity'):
            owner.run()
        self.assertFalse(owner.drained)
        self.assertIsNotNone(owner.source_error)
        self.assertEqual({}, self.files.fds)

    def test_factory_wires_sources_and_preserves_original_pipe_partition(self):
        f = supervisor_fixture.SupervisorTest()
        f.setUp()
        output = Mock()
        output.writers = (4, 5)
        with patch('fixed_witness_replay_supervisor.TracerEvidence'), \
                patch('fixed_witness_replay_supervisor.CoordinatorOutput', return_value=output), \
                patch('fixed_witness_replay_supervisor.CoordinatorLaunch.for_host', return_value=f.launch), \
                patch('fixed_witness_replay_probe_sources.ProcFiles', return_value=self.files), \
                patch('fixed_witness_replay_supervisor.TracerSupervisor.__init__', return_value=None) as init:
            TracerSupervisor.for_host(f.ledger, ['command'], '/work', {}, 3, '/evidence', 100,
                                      reserve_writers(ByteQuota()),
                                      prepared_probes=(self.root, self.receipt, (2, 3), self.networks))
        sources = init.call_args.kwargs['sources']
        self.assertIs(init.call_args.kwargs['exec_observations'], sources.observations)
        self.assertIs(init.call_args.kwargs['output'], output)

    def test_prepared_records_reach_original_exec_entry_and_terminal_checker(self):
        chain = chain_fixture.FilterExecChainTest()
        chain.setUp()
        for name in ('data', 'meta', 'links', 'canon'):
            getattr(self.files, name).update({k: v for k, v in getattr(chain.files, name).items()
                                            if k.startswith('/proc/')})
        self.files.owner, self.files.tasks = 10, ['10']
        sources = self.sources(births=(1, 99))
        f, owner = self.owner(sources)
        f.f.access.event_tid.return_value = 11
        wire = PtraceAccess(chain_fixture.wire_kernel(f.f.access))
        f.launch.access = wire
        plan = sources.observations._network_launches[1]
        self.files.data['/proc/11/cmdline'] = b'\0'.join(a.encode() for a in plan.argv()) + b'\0'
        def waited(tid, options):
            index = f.io.wait.call_count
            if index == 1:
                f.f.access.signal_info.return_value = SignalInfo(19, 0, -6, 11, 1000)
                return (11, 0x137f)
            if index == 2:
                f.f.access.syscall_info.return_value = decode_syscall_info(*entry(59))
                return (11, 0x857f)
            index -= 1
            if index in (2, 5):
                if index == 5:
                    record = sources.observations._records[1]
                    self.files.data['/proc/11/cmdline'] = b'\0'.join(a.encode() for a in record.argv) + b'\0'
                    self.files.data['/proc/11/status'] = (b'State:\tt (tracing stop)\nPid:\t11\nTgid:\t11\n'
                        b'TracerPid:\t10\nNoNewPrivs:\t1\nSeccomp:\t2\nSeccomp_filters:\t2\n')
                return (11, 0x4057f)
            if index in (3, 6):
                f.f.access.syscall_info.return_value = decode_syscall_info(*exit_info(0, 0))
                return (11, 0x857f)
            if index in (4, 7):
                f.f.access.syscall_info.return_value = decode_syscall_info(*entry(59 if index == 4 else 0x40000029))
                return (11, 0x857f)
            if index == 8:
                return (11, 31)
            self.fail('unexpected wait')
        f.io.wait.side_effect = waited
        result = owner.run()
        self.assertEqual('TERMINAL_FACT_MATCHED', sources.observations.abi_state('x32'))
        self.assertEqual(1, result.live)
        self.assertTrue(owner.drained)
        self.assertEqual(1, len(owner.waiter.settled_reaps))
        self.assertFalse(wire._unscoped_mutation)


if __name__ == '__main__':
    unittest.main()
