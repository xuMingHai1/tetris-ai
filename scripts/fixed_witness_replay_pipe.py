"""Verify-only parent pipe component. No historical input, CLI or replay mode.

Receipt/READY verification is read-only work; only run_process can release ACK.
Network isolation, cumulative artifact quota and journal gates remain separate.
"""
import hashlib
from pathlib import Path
import time

from fixed_witness_replay_admission import JVM_ARTIFACT_BYTES, _ready, authenticate_ready, command_for
from fixed_witness_replay_identity import canonical, require
from fixed_witness_replay_prepare import verify_prepared
from fixed_witness_replay_process import (
    JVM_SECONDS, PARENT_SECONDS, TOTAL_SECONDS, _cancellation, _seconds, _work, _stop_work, run_process,
)


class VerifyOnlyHandshake:
    """Read-only callbacks; deliberately has no reference to the child's stdin."""

    def __init__(self, root, java_home, receipt, output, nonce, manifest, artifact_bytes=JVM_ARTIFACT_BYTES):
        self.root, self.java_home = root, java_home
        self.receipt, self.output, self.nonce = dict(receipt), Path(output), nonce
        self.manifest = manifest
        self.artifact_bytes = artifact_bytes

    def ready(self):
        path = self.output / 'ready.json'
        if not path.exists():
            return False
        # CREATE_NEW precedes the JVM's write: existence alone is not READY.
        # An oversized or linked file is an error, never an endless wait.
        require(canonical(path).is_file(), 'regular READY required')
        with path.open('rb') as stream:
            data = stream.read(1024 * 1024 + 1)
        require(len(data) <= min(1024 * 1024, self.artifact_bytes), 'READY exceeds ceiling')
        return data.endswith(b'\n')

    def authenticate(self, pid):
        return authenticate_ready(self.root, self.java_home, self.receipt,
                                  self.output, self.nonce, pid, self.artifact_bytes)

    def finalize(self, admitted):
        require(verify_prepared(self.root, self.java_home, self.receipt) == self.manifest,
                'post-exit receipt drift')
        raw, _ = _ready(self.output / 'ready.json')
        digest = hashlib.sha256(raw).hexdigest()
        require(digest == admitted['ready_sha256'], 'post-exit READY drift')
        verified_raw, verified = _ready(self.output / 'verified.json')
        require(len(raw) + len(verified_raw) <= self.artifact_bytes,
                'JVM artifact allowance exceeded')
        require(set(verified) == {'schema', 'state', 'ready_sha256', 'diagnostic_started',
                                  'provenance_authenticated', 'eligible_for_runtime_validation',
                                  'artifact_bytes_limit'},
                'unexpected verified fields')
        require(type(verified['artifact_bytes_limit']) is int
                and verified['artifact_bytes_limit'] == self.artifact_bytes,
                'verified artifact allowance mismatch')
        require(verified['schema'] == 'fixed-witness-jvm-v1'
                and verified['state'] == 'VERIFIED_NO_REPLAY'
                and verified['ready_sha256'] == digest, 'verified binding mismatch')
        require(all(verified[name] is False for name in ('diagnostic_started',
                    'provenance_authenticated', 'eligible_for_runtime_validation')),
                'unexpected verified authority')
        require({path.name for path in self.output.iterdir()} == {'ready.json', 'verified.json'},
                'unexpected verify-only artifact')
        return {'state': 'VERIFIED_NO_REPLAY', 'ready_sha256': digest,
                'manifest_sha256': self.receipt['sha256'], 'diagnostic_started': False,
                'network_isolation_verified': False, 'diagnostic_authorized': False,
                'artifact_bytes_limit': self.artifact_bytes, 'artifact_bytes': len(raw) + len(verified_raw)}


def run_verify_only(root, java_home, receipt, output, logs, budget, *, nonce, total_deadline,
                    artifact_bytes=JVM_ARTIFACT_BYTES):
    """One held JVM, <=120s, within <=180s parent and the caller's <=900s total.

    Reserve three seconds for group cleanup and return. Preparation/build is a
    previous stage using the same budget and absolute total_deadline. Even on
    success this is only verify-only pipe evidence, not a whole-session result.
    A slow read-only daemon may finish after timeout but cannot send an ACK.
    """
    started = time.monotonic()
    require(not isinstance(total_deadline, bool), 'absolute total deadline required')
    _seconds(total_deadline - started)
    require(total_deadline <= started + TOTAL_SECONDS, 'total exceeds frozen ceiling')
    deadline = min(started + PARENT_SECONDS, total_deadline) - 3
    require(deadline > started, 'cleanup reserve exhausted')
    receipt = dict(receipt)  # retain independently of the child evidence directory
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
        command = command_for(manifest, root, java_home, output, nonce, artifact_bytes)
        require(not Path(output).exists() and not Path(output).is_symlink(), 'new JVM output required')
        require(time.monotonic() < deadline and cancellation['signal'] is None,
                'prelaunch authentication expired/cancelled')
        # Permanent same-root reservation: unused or failed child writes never refund credit.
        # The Java writer owns this allowance; parent reads do not charge it again.
        budget.quota.reserve(artifact_bytes)
        return run_process(command, root, logs, 'verify-only', budget,
                           seconds=min(JVM_SECONDS, deadline - time.monotonic()),
                           total_deadline=deadline, environment=manifest['environment'], cancellation=cancellation,
                           handshake=VerifyOnlyHandshake(root, java_home, receipt, output, nonce, manifest,
                                                         artifact_bytes))
