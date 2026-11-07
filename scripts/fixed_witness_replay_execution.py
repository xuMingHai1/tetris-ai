"""One authenticated fixed-action JVM transport, without historical authorization.

The caller owns independent canonical request bytes, an exclusive no-writer
snapshot and the build receipt. Cooperative checks provide neither a network
sandbox nor permission to run the historical fixture.
"""
import copy
from pathlib import Path
import time

from fixed_witness_replay_admission import _classes, command_for as verify_command
from fixed_witness_replay_execution_evidence import (
    SCHEMA, SUCCESS, FLAGS, LIMIT_KEYS, ExecutionRequestReceipt, _limits, _transport,
    reserve_execution, verify_execution_artifacts,
)
from fixed_witness_replay_identity import canonical, require
from fixed_witness_replay_observation import observe_process
from fixed_witness_replay_prepare import verify_prepared
from fixed_witness_replay_process import (
    JVM_SECONDS, PARENT_SECONDS, TOTAL_SECONDS, _cancellation, _seconds, _work, _stop_work, run_process,
)
from fixed_witness_report_pipe import ReportHandshake, _read, _json, _hash, _same


def command_for(manifest, root, java_home, output, nonce, request, *, handshake_bytes,
                journal_bytes, declaration_bytes, report_bytes):
    """Exact explicit execution argv; the two older mode commands stay unchanged."""
    require(isinstance(request, ExecutionRequestReceipt), 'parent execution receipt required')
    limits = _limits(handshake_bytes, journal_bytes, declaration_bytes, report_bytes)
    command = verify_command(manifest, root, java_home, output, nonce, handshake_bytes)
    paths = [canonical(root), Path(output), request.directory,
             canonical(manifest['environment']['HOME']), canonical(manifest['environment']['TMPDIR'])]
    for i, first in enumerate(paths):
        for second in paths[i+1:]:
            require(not first.is_relative_to(second) and not second.is_relative_to(first),
                    'disjoint checkout/output/snapshot/HOME/TMP required')
    command[7:] = ['xyz.xuminghai.tetris.ai.FixedWitnessReplayExecutionApplication', '--replay-fixed',
                   str(paths[0]), str(output), nonce, *(str(n) for n in limits.values()),
                   str(request.directory), request.request_sha256]
    return command


class ExecutionHandshake(ReportHandshake):
    """Reuse only the bounded READY-visibility probe; workers never receive stdin.

    Authentication retains the original READY bytes before ACK. Finalization
    compares those bytes and the parent receipt, never a child-provided authority.
    """
    def __init__(self, root, java_home, receipt, output, nonce, manifest, request, **allowances):
        self.root, self.java_home = canonical(root), canonical(java_home)
        self.receipt, self.manifest = copy.deepcopy(receipt), copy.deepcopy(manifest)
        self.output, self.nonce, self.request = Path(output), nonce, request
        self.allowances = dict(allowances)
        self.limits = _limits(**allowances)
        self.handshake_bytes = allowances['handshake_bytes']
        self._ready_bytes = None

    def _prepared(self):
        require(_same(verify_prepared(self.root, self.java_home, self.receipt), self.manifest),
                'execution build receipt drift')
        self.request.recheck()

    def authenticate(self, pid):
        self._prepared()
        require(self._ready_bytes is None, 'execution already admitted')
        require(type(pid) is int and pid > 0, 'positive pid required')
        command = command_for(self.manifest, self.root, self.java_home, self.output, self.nonce,
                              self.request, **self.allowances)
        raw, _ = _read(self.output / 'ready.json', min(1024*1024, self.handshake_bytes))
        ready = _json(raw)
        require(set(ready) == FLAGS | set(LIMIT_KEYS) | {'schema', 'state', 'nonce', 'observation',
                'request_sha256', 'actions_started'}, 'execution READY fields')
        _transport(ready, self.limits, self.request.request_sha256)
        require(ready['schema'] == SCHEMA and ready['state'] == 'READY'
                and ready['nonce'] == self.nonce and ready['actions_started'] is False,
                'execution READY identity/stage')
        observed = ready['observation']
        require(type(observed) is dict and set(observed) == {'classes', 'java_runtime', 'classpath', 'pid'},
                'execution observation fields')
        require(type(observed['pid']) is int and observed['pid'] == pid
                and observed['classpath'] == command[6]
                and _same(observed['classes'], _classes(self.manifest, self.root, execution=True)),
                'loaded execution identity')
        version, runtime = self.manifest['toolchain']['java_version'], observed['java_runtime']
        require(type(runtime) is str and (runtime == version or runtime.startswith(version + '+')),
                'execution runtime mismatch')
        digest = self.manifest['toolchain']['jdk_files']['bin/java']
        process = observe_process(pid, command, self.manifest['environment'], self.root, digest)
        self._prepared()
        require(_read(self.output / 'ready.json', min(1024*1024, self.handshake_bytes))[0] == raw,
                'execution READY drift')
        require(_same(observe_process(pid, command, self.manifest['environment'], self.root, digest), process),
                'execution process drift')
        self._ready_bytes = raw
        return {'ack': ('ACK ' + self.nonce + ' ' + _hash(raw) + '\n').encode('ascii'),
                'evidence': dict(ready_sha256=_hash(raw), request_sha256=self.request.request_sha256,
                    manifest_sha256=self.receipt['sha256'], process=process, loaded_classes_verified=True,
                    actions_started=False, **{name: False for name in FLAGS})}

    def finalize(self, admitted):
        self._prepared()
        require(self._ready_bytes is not None and admitted['ready_sha256'] == _hash(self._ready_bytes)
                and admitted['request_sha256'] == self.request.request_sha256
                and admitted['manifest_sha256'] == self.receipt['sha256'], 'execution admission drift')
        result = verify_execution_artifacts(self.output, self.request, ready_bytes=self._ready_bytes,
                    nonce=self.nonce, **self.allowances)
        self._prepared()
        return dict(result, manifest_sha256=self.receipt['sha256'])


def run_execution(root, java_home, receipt, output, logs, budget, *, nonce, request,
                  handshake_bytes, journal_bytes, declaration_bytes, report_bytes, total_deadline):
    """Predebit all four writers on one root, then supervise exactly one JVM.

    Existing inputs are not new output. Every new copy, receipt or failure/log
    persistence is separately charged to this root by its writer; no directory
    exemption or whole-build storage certification is implied.
    """
    started = time.monotonic()
    require(not isinstance(total_deadline, bool), 'absolute total deadline required')
    _seconds(total_deadline - started)
    require(total_deadline <= started + TOTAL_SECONDS, 'total exceeds frozen ceiling')
    deadline = min(started + PARENT_SECONDS, total_deadline) - 3
    require(deadline > started, 'cleanup reserve exhausted')
    receipt = copy.deepcopy(receipt)
    allowances = dict(handshake_bytes=handshake_bytes, journal_bytes=journal_bytes,
                      declaration_bytes=declaration_bytes, report_bytes=report_bytes)
    with _cancellation() as cancellation:
        pending = _work(verify_prepared, root, java_home, receipt, deadline=deadline)
        try:
            while pending[0].empty():
                require(time.monotonic() < deadline and cancellation['signal'] is None,
                        'prelaunch authentication expired/cancelled')
                time.sleep(.01)
            ok, manifest = pending[0].get()
        finally:
            _stop_work(pending)
        if not ok:
            raise manifest
        command = command_for(manifest, root, java_home, output, nonce, request, **allowances)
        request.recheck()
        require(not Path(output).exists() and not Path(output).is_symlink(), 'new execution output required')
        require(time.monotonic() < deadline and cancellation['signal'] is None, 'prelaunch expired/cancelled')
        reserve_execution(budget.quota, **allowances)
        return run_process(command, root, logs, 'replay-execution', budget,
                    seconds=min(JVM_SECONDS, deadline - time.monotonic()), total_deadline=deadline,
                    environment=manifest['environment'], cancellation=cancellation, success_state=SUCCESS,
                    handshake=ExecutionHandshake(root, java_home, receipt, output, nonce, manifest, request,
                                                 **allowances))
