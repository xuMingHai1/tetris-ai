"""Linux clean-build preparation only; no witness input or diagnostic JVM.

The caller retains the returned manifest digest independently and revalidates
before and after later use. This binds local bytes, not compiler/vendor trust,
loaded classes, network isolation or the eventual whole-session artifact quota.
"""
import json
import math
from pathlib import Path
import re
import sys
import time
import xml.etree.ElementTree as ET

from fixed_witness_replay_environment import controlled_environment, toolchain_identity
from fixed_witness_replay_identity import (
    canonical, classpath_identity, require, sha256, source_identity, verify_identity,
)
from fixed_witness_replay_process import BUILD_SECONDS, TOTAL_SECONDS, OutputBudget, run_process
from fixed_witness_replay_quota import write_exclusive


# Reserved from the SAME root before any preparation output, never extra credit.
FAILURE_BYTES = 4096


def _write(path, value, quota):
    data = (json.dumps(value, sort_keys=True, separators=(',', ':'), allow_nan=False)
            + '\n').encode('utf-8')
    write_exclusive(path, data, quota)


def _dependencies(root):
    version = ET.parse(root / 'pom.xml').find(
        '{http://maven.apache.org/POM/4.0.0}properties/'
        '{http://maven.apache.org/POM/4.0.0}maven-dependency-plugin.version')
    require(version is not None and re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+', version.text or ''),
            'explicit dependency plugin version required')
    return ['clean', 'test-compile',
            'org.apache.maven.plugins:maven-dependency-plugin:' + version.text + ':build-classpath',
            '-DincludeScope=test', '-Dmdep.pathSeparator=:']


def _classpath(text, path, quota):
    """Retain the plugin's single complete stdout record through shared quota.

    build-classpath logs this header and the classpath on the following line
    when neither outputFile nor outputProperty is set. Read only stdout from
    a successful supervised process; never infer a path from a failure prefix.
    The log and extracted file are distinct retained bytes and each is charged.
    """
    marker = '[INFO] Dependencies classpath:'
    lines = text.split('\n')
    require(text.count('Dependencies classpath:') == 1 and lines.count(marker) == 1,
            'unique dependency classpath stdout record required')
    index = lines.index(marker) + 1
    require(index < len(lines) - 1, 'complete dependency classpath line required')
    value = lines[index]
    dependencies = value.split(':')
    require(value == value.strip() and not re.search(r'[\x00-\x1f\x7f]', value)
            and all(entry.startswith('/') for entry in dependencies),
            'empty/ambiguous dependency classpath')
    write_exclusive(path, (value + '\n').encode('utf-8'), quota)
    return dependencies


def _versions(java_text, maven_text, toolchain):
    require('\x1b' not in java_text + maven_text, 'unexpected version control sequence')
    java = re.search(r'^(?:openjdk|java) version "([^"\n]+)"', java_text, re.M)
    require(java is not None and java[1] == toolchain['java_version'],
            'observed Java/release version mismatch')
    require(re.search(r'^Apache Maven 3\.9\.16(?:\s|$)', maven_text, re.M),
            'observed Maven version mismatch')
    runtime = re.search(r'^Java version: ([^,\n]+),[^\n]*, runtime: ([^\r\n]+)$', maven_text, re.M)
    require(runtime is not None and runtime[1] == toolchain['java_version']
            and Path(runtime[2]) == Path(toolchain['java_home']),
            'observed Maven Java runtime mismatch')


def prepare(root, java_home, output, budget, *, total_deadline):
    """Prepare once in a fresh external directory, sharing the caller's budget.

    Refuse any existing target instead of deleting caller data. All version
    observations and the Wrapper build share ONE 600s deadline; the supplied
    whole-command deadline is capped at 900s and is never reset by this helper.
    Parent JSON artifacts and pipes share budget.quota. A permanent 4096-byte
    failure reservation is allocated before output; failure.json deliberately
    contains bounded error text, counters and the last stage's numeric outcome,
    not repeated commands or inventories. This keeps the original stop evidence
    even when the normal stage-result artifact itself cannot fit.
    Classpath extraction is charged separately from its retained stdout bytes.
    HOME/TMP and other external writers are not yet constrained.
    """
    require(sys.platform == 'linux', 'Linux clean-build preparation required')
    started = time.monotonic()
    require(isinstance(total_deadline, (int, float)) and not isinstance(total_deadline, bool)
            and math.isfinite(total_deadline) and started < total_deadline <= started + TOTAL_SECONDS,
            'active frozen total deadline required')
    require(isinstance(budget, OutputBudget), 'shared output budget required')
    root, java_home, output = map(canonical, (root, java_home, output))
    require(output.is_dir() and not any(output.iterdir()), 'fresh empty output required')
    require(all(not a.is_relative_to(b) and not b.is_relative_to(a)
                for a, b in ((root, output), (root, java_home), (output, java_home))),
            'source/toolchain/output must be disjoint')
    build_deadline = min(started + BUILD_SECONDS, total_deadline)
    failure_quota = budget.quota.reserve(FAILURE_BYTES)
    records = []
    try:
        require(not (root / 'target').exists() and not (root / 'target').is_symlink(),
                'fresh checkout without target required')
        home, temporary = output / 'home', output / 'tmp'
        home.mkdir()
        temporary.mkdir()
        environment = controlled_environment(java_home, home, temporary)
        source = source_identity(root, environment=environment)
        toolchain = toolchain_identity(root, java_home)
        _write(output / 'before.json', {'source': source, 'toolchain': toolchain,
                                       'environment': environment}, budget.quota)

        def run(name, command):
            result = run_process(command, root, output, name, budget, seconds=BUILD_SECONDS,
                                 total_deadline=build_deadline, environment=environment)
            records.append(result)
            _write(output / (name + '-result.json'), result, budget.quota)
            require(result['state'] == 'EXITED' and result['returncode'] == 0,
                    'preparation stage failed: ' + name)
            return ''.join((output / (name + '-' + suffix + '.log')).read_text(
                encoding='utf-8', errors='strict') for suffix in ('stdout', 'stderr'))

        java_text = run('java-version', [str(java_home / 'bin/java'), '-version'])
        wrapper = [str(root / 'mvnw'), '--batch-mode', '--no-transfer-progress', '-Dstyle.color=never']
        maven_text = run('maven-version', wrapper + ['--version'])
        _versions(java_text, maven_text, toolchain)
        run('clean-build', wrapper + _dependencies(root))
        dependencies = _classpath((output / 'clean-build-stdout.log').read_text(
            encoding='utf-8', errors='strict'), output / 'classpath.txt', budget.quota)
        classpath = classpath_identity(root, dependencies)
        verify_identity(source, source_identity(root, environment=environment))
        verify_identity(toolchain, toolchain_identity(root, java_home))
        require(time.monotonic() < build_deadline, 'preparation deadline exhausted')
        manifest = {'state': 'PREPARED', 'source': source, 'toolchain': toolchain,
                    'environment': environment, 'classpath': classpath, 'processes': records,
                    'build_seconds': BUILD_SECONDS, 'total_deadline': total_deadline,
                    'elapsed_seconds': time.monotonic() - started,
                    'diagnostic_started': False}
        path = output / 'manifest.json'
        _write(path, manifest, budget.quota)
        return {'manifest': str(path), 'sha256': sha256(path)}
    except Exception as error:
        last = None if not records else {
            key: records[-1][key] for key in (
                'state', 'returncode', 'stdout_stderr_bytes', 'observed_bytes_lower_bound')}
        _write(output / 'failure.json', {'state': 'INCOMPLETE',
                                        'error': str(error)[:128],
                                        'error_type': type(error).__name__[:64],
                                        'completed_processes': len(records),
                                        'last_process': last,
                                        'stdout_stderr_bytes': budget.written,
                                        'observed_bytes_lower_bound': budget.observed,
                                        'diagnostic_started': False}, failure_quota)
        raise


def verify_prepared(root, java_home, receipt):
    """Reject manifest replacement and byte drift using an independent receipt.

    A receipt stored beside the manifest is not independent authentication.
    This does not launch Java or prove that these classes were actually loaded.
    """
    path = canonical(receipt['manifest'])
    require(sha256(path) == receipt['sha256'], 'retained manifest digest mismatch')
    manifest = json.loads(path.read_text(encoding='utf-8', errors='strict'))
    require(manifest['state'] == 'PREPARED' and manifest['diagnostic_started'] is False,
            'not a prepared manifest')
    environment = manifest['environment']
    verify_identity(manifest['source'], source_identity(root, environment=environment))
    verify_identity(manifest['toolchain'], toolchain_identity(root, java_home))
    verify_identity(manifest['classpath'], classpath_identity(
        root, manifest['classpath']['classpath'][2:]))
    return manifest
