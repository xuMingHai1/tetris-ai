"""Prepare/verify a clean GoalWindow test-classpath build; never starts population games."""
import argparse
import hashlib
import json
import os
import platform
import re
import shutil
import subprocess
import zipfile
from pathlib import Path
import xml.etree.ElementTree as ET

from verify_heart_production_base import verify as production_guard

PROTOCOL = 'docs/research/heart-acceptance-v1.json'
PROTOCOL_SHA = '23dea1ad0d74ddfda04d443266d3cca9583d790aaceba6bc3afd98cd36896280'
CONFIG = 'docs/research/heart-goal-window-config.json'
BUILD = 'target/goal-window'
MANIFEST = BUILD + '/build-manifest.json'
EXPECTED_CONFIG = {
    'schema': 'goal-window-config-v1', 'candidate': 'goal-window', 'baseline': 'build-shape',
    'rows': 20, 'cols': 10, 'target': 'heart', 'preview_rescue': False,
    'seed_start': 20000, 'games': 80, 'piece_limit': 1000,
    'warmup': {'seed_start': 19000, 'games': 10, 'piece_limit': 10},
    'setup_top_k': 5, 'max_enumerations': 7,
    'terminal': {'clean': True, 'max_height': 8, 'required_holes': 0,
                 'support_holes': 0, 'outside_holes': 0},
    'jvm_args': [], 'measurement_timeout_seconds': 2700,
}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, 'duplicate JSON field')
        result[key] = value
    return result


def read_json(path):
    return json.loads(path.read_text(encoding='utf-8'), object_pairs_hook=unique_object)


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def command(root, args, *, log_path=None):
    if log_path is None:
        return subprocess.check_output(args, cwd=root, stderr=subprocess.STDOUT, text=True)
    # The supervisor keeps this file outside target so clean/termination cannot erase its prefix.
    with log_path.open('x', encoding='utf-8') as log:
        result = subprocess.run(args, cwd=root, stdout=log, stderr=subprocess.STDOUT)
    output = log_path.read_text(encoding='utf-8')
    if result.returncode:
        raise subprocess.CalledProcessError(result.returncode, args, output=output)
    return output


def source_state(root):
    sha = command(root, ['git', 'rev-parse', 'HEAD']).strip()
    require(re.fullmatch('[0-9a-f]{40}', sha), 'invalid checkout SHA')
    require(not command(root, ['git', 'status', '--porcelain', '--untracked-files=all']).strip(),
            'checkout must be clean, including untracked source')
    require(not command(root, ['git', 'ls-files', '--others', '--ignored', '--exclude-standard',
                               '--', 'src', '.mvn']).strip(), 'ignored source/build inputs present')
    tracked = command(root, ['git', 'ls-files', '-z', '--', 'src', '.mvn', 'pom.xml', 'mvnw', 'mvnw.cmd'])
    require(not any((root / p).is_symlink() for p in tracked.split('\0') if p), 'source/build input symlink')
    require(digest(root / PROTOCOL) == PROTOCOL_SHA, 'wrong frozen protocol')
    # Compare JSON types too: bools/floats are not substitutes for frozen integer parameters.
    require(json.dumps(read_json(root / CONFIG), sort_keys=True) ==
            json.dumps(EXPECTED_CONFIG, sort_keys=True), 'wrong frozen GoalWindow config')
    return {'source_sha': sha, 'clean_checkout': True,
            'protocol_sha256': digest(root / PROTOCOL), 'config_sha256': digest(root / CONFIG),
            'production_guard': production_guard(root, root / PROTOCOL)}


def clean_environment():
    for name in ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'CLASSPATH',
                 'MAVEN_OPTS', 'MAVEN_EXT_CLASS_PATH'):
        require(not os.environ.get(name), 'unsupported injected environment: ' + name)
    require(os.environ.get('MAVEN_ARGS', '') in ('', '-ntp'), 'unsupported MAVEN_ARGS')


def java_identity(root):
    clean_environment()
    java = (Path(os.environ['JAVA_HOME']) / 'bin/java') if os.environ.get('JAVA_HOME') else Path(
        shutil.which('java') or '/missing-java')
    java = java.resolve(strict=True)
    settings = command(root, [str(java), '-XshowSettings:properties', '-version'])
    properties = dict(re.findall(r'^\s+(java\.(?:home|runtime.version|vendor)) = (.+)$',
                                 settings, re.MULTILINE))
    require(properties.keys() >= {'java.home', 'java.runtime.version', 'java.vendor'}, 'missing JDK identity')
    require(re.match(r'27(?:[.+-]|$)', properties['java.runtime.version']), 'JDK 27 required')
    require(java.parent.parent == Path(properties['java.home']).resolve(), 'JDK home mismatch')
    return {'executable': str(java), **properties}


def class_inventory(root):
    inventory, fqns = {}, set()
    require(not (root / 'target').is_symlink(), 'target symlink')
    for base in ('target/classes', 'target/test-classes'):
        directory = root / base
        require(directory.is_dir() and not directory.is_symlink(), 'missing compiled classes: ' + base)
        require(not any(p.is_symlink() for p in directory.rglob('*')), 'class directory contains symlink')
        files = sorted(directory.rglob('*.class'))
        require(files, 'empty compiled classes: ' + base)
        for path in files:
            require(not path.is_symlink() and path.resolve().is_relative_to(directory.resolve()),
                    'class symlink/escape')
            relative = path.relative_to(directory).as_posix()
            require(relative == 'module-info.class' or relative not in fqns, 'main/test FQN shadow: ' + relative)
            fqns.add(relative)
            inventory[path.relative_to(root).as_posix()] = digest(path)
    return inventory


def dependency_inventory(paths):
    result = {}
    for path in paths:
        require(path.is_absolute() and path.is_file() and path.suffix == '.jar', 'dependency must be explicit jar')
        canonical = str(path.resolve())
        require(str(path) == canonical and canonical not in result, 'duplicate/noncanonical dependency')
        with zipfile.ZipFile(path) as jar:
            require(not any(name.endswith('.class') and
                    (name.startswith('xyz/xuminghai/tetris/') or
                     re.match(r'META-INF/versions/\d+/xyz/xuminghai/tetris/', name))
                    for name in jar.namelist()), 'dependency shadows project classes')
        result[canonical] = digest(path)
    require(result, 'missing resolved dependencies')
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


def prepare(root, build_log=None):
    root = root.resolve(strict=True)
    if build_log is not None:
        build_log = build_log.absolute()
        require(not build_log.resolve().is_relative_to(root), 'streamed build log must be outside checkout')
    require(not (root / 'target').is_symlink() and not (root / BUILD).is_symlink(), 'target symlink')
    # A failed attempt must not leave an older certificate looking like this attempt's output.
    (root / MANIFEST).unlink(missing_ok=True)
    state = source_state(root)
    jdk = java_identity(root)
    maven_version = command(root, ['./mvnw', '--version'])
    require('Apache Maven 3.9.16' in maven_version, 'Wrapper Maven 3.9.16 required')
    args = build_command(root)
    # clean must run before compilation. Capture outside target so clean cannot unlink the log.
    try:
        log = command(root, args) if build_log is None else command(root, args, log_path=build_log)
    except subprocess.CalledProcessError as error:
        (root / BUILD).mkdir(parents=True, exist_ok=True)
        (root / BUILD / 'build.log').write_text(error.output, encoding='utf-8')
        raise
    directory = root / BUILD
    directory.mkdir(parents=True, exist_ok=True)
    (directory / 'build.log').write_text(log, encoding='utf-8')
    require(source_state(root) == state, 'checkout changed during build')
    dependencies = dependency_inventory([Path(p) for p in
        (directory / 'classpath.txt').read_text().strip().split(os.pathsep)])
    classes = class_inventory(root)
    classpath = [str(root / 'target/classes'), str(root / 'target/test-classes'), *dependencies]
    manifest = {'schema': 'goal-window-build-v1', 'root': str(root), **state, 'jdk': jdk,
                'jvm_args': [], 'maven_version': maven_version, 'build_command': args, 'classpath': classpath,
                'class_files': classes, 'dependencies': dependencies,
                'build_log_sha256': digest(directory / 'build.log'),
                'dependency_tree_sha256': digest(directory / 'dependency-tree.txt'),
                'environment': {'os': platform.platform(), 'arch': platform.machine(),
                                'cpu_count': os.cpu_count()}}
    path = root / MANIFEST
    path.write_text(json.dumps(manifest, sort_keys=True, indent=2) + '\n', encoding='utf-8')
    return {'manifest': str(path), 'manifest_sha256': digest(path), 'source_sha': state['source_sha']}


def verify_build(path, expected_digest):
    require(re.fullmatch('[0-9a-f]{64}', expected_digest or ''), 'full manifest digest required')
    require(digest(path) == expected_digest, 'manifest digest mismatch')
    manifest = read_json(path)
    require(manifest['schema'] == 'goal-window-build-v1', 'wrong build schema')
    root = Path(manifest['root']).resolve(strict=True)
    require(path.resolve() == root / MANIFEST, 'manifest outside prepared target')
    state = source_state(root)
    require(all(manifest.get(k) == v for k, v in state.items()), 'source/protocol/config/guard mismatch')
    require(manifest.get('build_command') == build_command(root) and
            'Apache Maven 3.9.16' in manifest.get('maven_version', ''), 'missing/wrong clean build command/version')
    require(java_identity(root) == manifest['jdk'] and manifest['jvm_args'] == [], 'JDK/flags mismatch')
    require(class_inventory(root) == manifest['class_files'], 'stale/changed/extra compiled classes')
    dependencies = dependency_inventory([Path(p) for p in manifest['classpath'][2:]])
    require(dependencies == manifest['dependencies'], 'changed dependency bytes')
    require(manifest['classpath'][:2] == [str(root / 'target/classes'), str(root / 'target/test-classes')],
            'wrong main/test classpath order')
    require(digest(root / BUILD / 'build.log') == manifest['build_log_sha256'] and
            digest(root / BUILD / 'dependency-tree.txt') == manifest['dependency_tree_sha256'],
            'changed build/dependency logs')
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path('.'))
    parser.add_argument('--verify', type=Path)
    parser.add_argument('--manifest-sha256')
    parser.add_argument('--build-log', type=Path, help='new external log retaining the live clean-build prefix')
    args = parser.parse_args()
    if args.verify:
        require(args.build_log is None, 'build log is only valid when preparing')
        manifest = verify_build(args.verify, args.manifest_sha256)
        print(json.dumps({'verified': True, 'source_sha': manifest['source_sha']}))
    else:
        require(args.manifest_sha256 is None, 'digest is only valid with --verify')
        print(json.dumps(prepare(args.root, args.build_log)))


if __name__ == '__main__':
    main()
