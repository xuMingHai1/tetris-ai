"""Authenticate a held verify-only JVM; return ACK bytes, never send or launch.

The caller owns stdin, deadlines, network restriction and cumulative artifacts.
Even an authenticated READY does not authorize historical replay. Keep the
prepare receipt outside child-writable evidence and recheck after child exit.
"""
import hashlib
import json
from pathlib import Path
import re

from fixed_witness_replay_identity import canonical, require
from fixed_witness_replay_observation import observe_process
from fixed_witness_replay_prepare import verify_prepared


READY_BYTES = 1024 * 1024
PREFIX = 'xyz.xuminghai.tetris.'
MAIN = (
    'ai.ActionPlanSimulator', 'ai.ActionStateSearch', 'ai.AiAction', 'ai.AiPlan',
    'ai.AiPlanValidator', 'ai.BoardOccupancyMetrics', 'ai.BoardSimulator',
    'ai.GameSnapshot', 'ai.PlacementCandidate', 'ai.ShapeProgress', 'ai.ShapeTarget',
    'ai.ShapeWitnessConstraintAudit', 'core.Abstract2StateBlock', 'core.Abstract4StateBlock',
    'core.AbstractBlock', 'core.BoardPosition', 'core.BoardRules', 'core.Cell',
    'core.IBlock', 'core.JBlock', 'core.LBlock', 'core.OBlock', 'core.SBlock',
    'core.TBlock', 'core.Tetris', 'core.TetrisFactory', 'core.TetrominoType', 'core.ZBlock',
)
TEST = (
    'ai.FixedWitnessInput', 'ai.FixedWitnessReplay', 'ai.FixedWitnessReportChecker',
    'ai.FixedWitnessJournalReader', 'ai.FixedWitnessReportCodec',
    'ai.FixedWitnessJvmObservation', 'ai.FixedWitnessReplayApplication',
)


def _object(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, 'duplicate READY JSON key')
        result[key] = value
    return result


def _ready(path):
    path = canonical(path)
    require(path.is_file(), 'regular READY required')
    with path.open('rb') as stream:
        data = stream.read(READY_BYTES + 1)
    require(len(data) <= READY_BYTES and data.endswith(b'\n'), 'oversized/incomplete READY')
    value = json.loads(data.decode('utf-8', errors='strict'), object_pairs_hook=_object)
    require(type(value) is dict, 'READY object required')
    return data, value


def command_for(manifest, root, java_home, output, nonce):
    """Construct the exact ordinary JVM command from an already verified receipt."""
    require(isinstance(nonce, str) and re.fullmatch('[0-9a-f]{64}', nonce), '64-hex nonce required')
    root, java_home = canonical(root), canonical(java_home)
    output = Path(output)
    require(output.is_absolute() and output == output.absolute()
            and output.parent == canonical(output.parent)
            and not output.is_relative_to(root) and not root.is_relative_to(output),
            'external canonical output required')
    require(not any(part in ('.', '..') for part in output.parts), 'normalized output required')
    require(str(java_home) == manifest['toolchain']['java_home'], 'prepared JDK mismatch')
    env = manifest['environment']
    return [str(java_home / 'bin/java'), '-Xmx256m', '-XX:MaxMetaspaceSize=128m',
            '-Duser.home=' + env['HOME'], '-Djava.io.tmpdir=' + env['TMPDIR'],
            '-cp', ':'.join(manifest['classpath']['classpath']),
            PREFIX + 'ai.FixedWitnessReplayApplication', '--verify-only', str(root), str(output), nonce]


def _classes(manifest, root):
    expected = {}
    compiled = manifest['classpath']['compiled_files']
    for base, names in (('target/classes', MAIN), ('target/test-classes', TEST)):
        for name in names:
            resource = (PREFIX + name).replace('.', '/')
            primary = base + '/' + resource + '.class'
            require(primary in compiled, 'required class absent from retained build')
            for path, digest in compiled.items():
                # getDeclaredClasses observes named member classes, not javac's
                # numeric anonymous/local/switch helpers (for example $1).
                member = (path.startswith(base + '/' + resource + '$') and path.endswith('.class')
                          and not re.search(r'\$[0-9]', path[len(base) + 1:]))
                if path == primary or member:
                    fqn = path[len(base) + 1:-6].replace('/', '.')
                    expected[fqn] = {'origin': str(root / base), 'resource': str(root / path), 'sha256': digest}
    return expected


def authenticate_ready(root, java_home, receipt, output, nonce, pid):
    """Return a bound ACK only after receipt, proc and class observations agree.

    No file is written and no stdin is touched, including on failure. Repeated
    proc/receipt/READY checks bracket authentication while the JVM waits for EOF
    on its ACK pipe. These are cooperative observations, not hostile-process or
    in-memory bytecode attestation. The caller must never treat this as a session
    success, network isolation or authorization to run the historical fixture.
    """
    require(type(pid) is int and pid > 0, 'positive child pid required')
    root, java_home, output = map(canonical, (root, java_home, output))
    manifest = verify_prepared(root, java_home, receipt)
    command = command_for(manifest, root, java_home, output, nonce)
    expected = _classes(manifest, root)
    data, ready = _ready(output / 'ready.json')
    require(set(ready) == {'schema', 'state', 'nonce', 'observation', 'diagnostic_started',
                           'provenance_authenticated', 'eligible_for_runtime_validation'}, 'unexpected READY fields')
    require(ready['schema'] == 'fixed-witness-jvm-v1' and ready['state'] == 'READY'
            and ready['nonce'] == nonce, 'READY schema/state/nonce mismatch')
    require(all(ready[name] is False for name in ('diagnostic_started', 'provenance_authenticated',
                                                 'eligible_for_runtime_validation')), 'unexpected READY authority')
    observed = ready['observation']
    require(type(observed) is dict and set(observed) == {'classes', 'java_runtime', 'classpath', 'pid'},
            'unexpected JVM observation fields')
    require(type(observed['pid']) is int and observed['pid'] == pid, 'READY pid mismatch')
    require(observed['classpath'] == command[6] and observed['classes'] == expected,
            'loaded class/classpath mismatch')
    # JAVA_VERSION binds the release version, whereas java.runtime.version may
    # include a build/vendor suffix. Full JDK bytes are independently rechecked.
    runtime = observed['java_runtime']
    version = manifest['toolchain']['java_version']
    require(isinstance(runtime, str) and (runtime == version or runtime.startswith(version + '+')),
            'observed runtime version mismatch')
    digest = manifest['toolchain']['jdk_files']['bin/java']
    process = observe_process(pid, command, manifest['environment'], root, digest)
    require(verify_prepared(root, java_home, receipt) == manifest, 'prepared evidence drift')
    require(_ready(output / 'ready.json')[0] == data, 'READY changed during authentication')
    require(observe_process(pid, command, manifest['environment'], root, digest) == process,
            'process changed during authentication')
    ready_hash = hashlib.sha256(data).hexdigest()
    return {'ack': ('ACK ' + nonce + ' ' + ready_hash + '\n').encode('ascii'),
            'evidence': {'state': 'READY_AUTHENTICATED_NO_REPLAY', 'ready_sha256': ready_hash,
                         'manifest_sha256': receipt['sha256'], 'process': process,
                         'loaded_classes_verified': True, 'diagnostic_started': False,
                         'network_isolation_verified': False, 'diagnostic_authorized': False}}
