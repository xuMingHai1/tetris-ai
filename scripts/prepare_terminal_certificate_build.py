"""Clean-build provenance for terminal-certificate verify-only; never starts the solver.

The caller must retain the returned manifest digest independently. This authenticates
an unmodified local build, not a malicious host, compiler or dependency publisher.
Process/job deadlines and registered diagnostic execution belong to the later supervisor.
"""
import argparse
import json
import os
import re
import sys
import zipfile
from pathlib import Path
import xml.etree.ElementTree as ET

# Only configuration-independent inventory/identity primitives are reused. The old
# GoalWindow prepare/verify/source-state functions and frozen protocol are untouched.
from prepare_goal_window_build import (PROTOCOL, PROTOCOL_SHA, class_inventory,
                                       clean_environment, command, dependency_inventory,
                                       digest, java_identity, read_json, require)
from verify_heart_production_base import verify as production_guard

BUILD = 'target/terminal-certificate'
DESIGN = 'docs/design/heart-terminal-certificate.md'
FLAGS = ['-Xmx512m']
MAIN = 'xyz.xuminghai.tetris.ai.TerminalCertificatePreflight'
OBSERVED = {
    **{name: 'target/classes' for name in (
        'ai.ActionPlanCandidates', 'ai.ActionStateSearch', 'ai.ActionPlanSimulator',
        'ai.BoardSimulator', 'ai.BoardOccupancyMetrics', 'ai.GameSnapshot',
        'ai.ShapeTarget', 'ai.AiPlan', 'core.BoardRules', 'core.TetrisFactory', 'core.TetrominoType')},
    **{name: 'target/test-classes' for name in (
        'ai.TerminalCertificatePreflight', 'ai.TerminalCertificateGeometry',
        'ai.TerminalCertificateOrder', 'ai.TerminalCertificateStep',
        'ai.TerminalCertificateChecker', 'ai.GoalWindowCandidate',
        'ai.TerminalCertificateApplication', 'ai.TerminalCertificateSession',
        'ai.TerminalCertificateOrder$StepFacts', 'ai.TerminalCertificateRecords',
        'ai.TerminalCertificateJournal', 'ai.TerminalCertificateJournalReader')},
}


def unlinked(path):
    require(path.absolute() == path.resolve(), 'linked/noncanonical path: ' + str(path))


def source_state(root):
    clean_environment()
    require(command(root, ['git', 'rev-parse', '--show-toplevel']).strip() == root.as_posix(),
            'checkout root must be Git top-level')
    sha = command(root, ['git', 'rev-parse', 'HEAD']).strip()
    require(re.fullmatch('[0-9a-f]{40}', sha), 'invalid source SHA')
    require(not command(root, ['git', 'status', '--porcelain', '--untracked-files=all']).strip(),
            'checkout must be clean including untracked files')
    entries = command(root, ['git', 'ls-files', '-v', '-z']).split('\0')
    require(all(entry.startswith('H ') for entry in entries if entry), 'hidden index flags')
    require(not command(root, ['git', 'ls-files', '--others', '--ignored', '--exclude-standard',
                               '--', 'src', '.mvn']).strip(), 'ignored source/build inputs')
    files = {}
    for entry in entries:
        if entry:
            name = entry[2:]
            path = root / name
            unlinked(path)
            require(path.is_file(), 'non-file tracked input')
            files[name] = digest(path)
    require(digest(root / PROTOCOL) == PROTOCOL_SHA, 'wrong frozen protocol')
    return {'source_sha': sha, 'source_files': files, 'protocol_sha256': PROTOCOL_SHA,
            'design_sha256': digest(root / DESIGN),
            'production_guard': production_guard(root, root / PROTOCOL)}


def compiled_inventory(root):
    # Resources participate in class loading too; bind every byte, not only .class.
    class_inventory(root)  # Reject missing classes, links and main/test FQN shadow.
    files = {}
    for base in ('target/classes', 'target/test-classes'):
        unlinked(root / base)
        for path in sorted((root / base).rglob('*')):
            unlinked(path)
            if path.is_file():
                files[path.relative_to(root).as_posix()] = digest(path)
    return files


def dependencies(paths):
    result = dependency_inventory(paths)
    for path in paths:
        unlinked(path)
        with zipfile.ZipFile(path) as jar:
            # A manifest Class-Path could silently extend the explicit classpath.
            if 'META-INF/MANIFEST.MF' in jar.namelist():
                manifest = jar.read('META-INF/MANIFEST.MF').decode('utf-8')
                require(not re.search(r'^Class-Path\s*:', manifest, re.I | re.M),
                        'implicit dependency Class-Path')
    return result


def build_command(root):
    ns = {'m': 'http://maven.apache.org/POM/4.0.0'}
    version = ET.parse(root / 'pom.xml').find('m:properties/m:maven-dependency-plugin.version', ns).text
    require(re.fullmatch(r'[0-9.]+', version), 'missing pinned dependency plugin')
    plugin = 'org.apache.maven.plugins:maven-dependency-plugin:' + version
    return ['./mvnw', '--batch-mode', '--no-transfer-progress', 'clean', 'test-compile',
            plugin + ':build-classpath', plugin + ':tree', '-DincludeScope=test',
            '-Dmdep.outputFile=' + BUILD + '/classpath.txt',
            '-DoutputFile=' + BUILD + '/dependency-tree.txt']


def prepare(root, output):
    require(sys.platform == 'linux', 'Linux execution host required')
    root = root.resolve(strict=True)
    output = output.absolute()
    unlinked(output)
    require(not output.is_relative_to(root), 'output must be outside checkout')
    # Never overwrite a previous attempt, including its failure prefix.
    output.mkdir()
    state = source_state(root)
    jdk = java_identity(root)
    version = command(root, ['./mvnw', '--version'])
    require('Apache Maven 3.9.16' in version, 'Wrapper Maven 3.9.16 required')
    unlinked(root / 'target')
    if (root / 'target').exists():
        require(not any(p.is_symlink() for p in (root / 'target').rglob('*')), 'linked old target')
    args = build_command(root)
    command(root, args, log_path=output / 'build.log')
    require(source_state(root) == state, 'source changed during build')
    require(java_identity(root) == jdk, 'JDK changed during build')
    directory = root / BUILD
    for name in ('classpath.txt', 'dependency-tree.txt'):
        unlinked(directory / name)
        (output / name).write_bytes((directory / name).read_bytes())
    jars = dependencies([Path(p) for p in (output / 'classpath.txt').read_text().strip().split(os.pathsep)])
    classpath = [str(root / 'target/classes'), str(root / 'target/test-classes'), *jars]
    manifest = {'schema': 'terminal-certificate-build-v1', 'root': str(root), **state,
                'jdk': jdk, 'jvm_args': FLAGS, 'maven_version': version, 'build_command': args,
                'classpath': classpath, 'compiled_files': compiled_inventory(root), 'dependencies': jars,
                'evidence_files': {name: digest(output / name) for name in
                                   ('build.log', 'classpath.txt', 'dependency-tree.txt')}}
    path = output / 'build-manifest.json'
    path.write_text(json.dumps(manifest, sort_keys=True, indent=2) + '\n', encoding='utf-8')
    return {'manifest': str(path), 'manifest_sha256': digest(path), 'source_sha': state['source_sha']}


def verify_build(path, expected_digest):
    require(re.fullmatch('[0-9a-f]{64}', expected_digest or ''), 'independently retained digest required')
    unlinked(path)
    require(digest(path) == expected_digest, 'manifest digest mismatch')
    manifest = read_json(path)
    require(manifest['schema'] == 'terminal-certificate-build-v1', 'wrong manifest schema')
    root = Path(manifest['root'])
    unlinked(root)
    require(not path.is_relative_to(root), 'manifest must be outside checkout')
    state = source_state(root)
    require(all(manifest.get(k) == v for k, v in state.items()), 'source/protocol/design/guard mismatch')
    require(manifest['build_command'] == build_command(root) and
            'Apache Maven 3.9.16' in manifest['maven_version'], 'clean build command/version mismatch')
    require(manifest['jdk'] == java_identity(root) and manifest['jvm_args'] == FLAGS, 'JDK/flags mismatch')
    require(manifest['compiled_files'] == compiled_inventory(root), 'stale/changed/extra compiled files')
    cp = manifest['classpath']
    require(cp[:2] == [str(root / 'target/classes'), str(root / 'target/test-classes')], 'classpath order')
    require(manifest['dependencies'] == dependencies([Path(p) for p in cp[2:]]), 'dependency bytes changed')
    require(set(manifest['evidence_files']) == {'build.log', 'classpath.txt', 'dependency-tree.txt'},
            'missing build evidence')
    for name, expected in manifest['evidence_files'].items():
        unlinked(path.parent / name)
        require(digest(path.parent / name) == expected, 'build evidence changed')
    require((path.parent / 'classpath.txt').read_text().strip().split(os.pathsep) == cp[2:],
            'resolved classpath mismatch')
    return manifest


def check_observation(manifest, observed):
    require(observed.get('schema') == 'terminal-certificate-preflight-v1' and
            observed.get('diagnostic_started') is False and
            observed.get('provenance_authenticated') is False and
            observed.get('eligible_for_runtime_validation') is False, 'wrong preflight status')
    require(observed.get('java_runtime') == manifest['jdk']['java.runtime.version'] and
            observed.get('jvm_args') == FLAGS, 'observed JDK/flags mismatch')
    expected = {}
    for suffix, base in OBSERVED.items():
        name = 'xyz.xuminghai.tetris.' + suffix
        key = base + '/' + name.replace('.', '/') + '.class'
        expected[name] = {'origin': str(Path(manifest['root']) / base),
                          'sha256': manifest['compiled_files'][key]}
    require(observed.get('loaded_classes') == expected, 'observed class inventory mismatch')


def preflight(path, expected_digest):
    require(sys.platform == 'linux', 'Linux execution host required')
    manifest = verify_build(path, expected_digest)
    root = Path(manifest['root'])
    args = [manifest['jdk']['executable'], *FLAGS, '-cp', os.pathsep.join(manifest['classpath']),
            MAIN, '--verify-only', str(root)]
    command(root, args, log_path=path.parent / 'preflight.json')
    check_observation(manifest, read_json(path.parent / 'preflight.json'))
    require(verify_build(path, expected_digest) == manifest, 'build changed during preflight')
    return {'status': 'VERIFIED', 'source_sha': manifest['source_sha'],
            'provenance_authenticated': True, 'diagnostic_started': False,
            'eligible_for_runtime_validation': False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path('.'))
    parser.add_argument('--output', type=Path)
    parser.add_argument('--verify', type=Path)
    parser.add_argument('--manifest-sha256')
    args = parser.parse_args()
    if args.verify:
        require(args.output is None, 'output is only valid for preparation')
        print(json.dumps(preflight(args.verify.absolute(), args.manifest_sha256)))
    else:
        require(args.output is not None and args.manifest_sha256 is None, 'new output directory required')
        print(json.dumps(prepare(args.root, args.output)))


if __name__ == '__main__':
    main()
