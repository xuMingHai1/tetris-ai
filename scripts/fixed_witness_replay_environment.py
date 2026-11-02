"""Explicit environment and toolchain bytes for the fixed-action supervisor.

No build, process launch, network isolation or historical input is performed.
The caller must pass the returned environment explicitly to every child, retain
the identity independently, and compare it again after build and diagnostic use.
Byte identity is not a vendor signature or proof of compiler trust.
"""
import json
import os
from pathlib import Path
import re

from fixed_witness_replay_identity import canonical, require, sha256


WRAPPER_PROPERTIES = {
    'wrapperVersion': '3.3.4',
    'distributionType': 'only-script',
    'distributionUrl': 'https://repo.maven.apache.org/maven2/org/apache/maven/'
                       'apache-maven/3.9.16/apache-maven-3.9.16-bin.zip',
    'distributionSha256Sum': '5af3b743dd8b876b5c45da33b676251e5f1687712644abb4ee519ca56e1d89ce',
}


def _properties(path):
    """Accept this repository's simple pinned format, not Java escape syntax."""
    result = {}
    for line in path.read_text(encoding='utf-8', errors='strict').splitlines():
        if not line or line.startswith('#'):
            continue
        require('=' in line and '\\' not in line, 'unsupported wrapper property')
        key, value = line.split('=', 1)
        require(key and key not in result, 'duplicate wrapper property')
        result[key] = value
    require(result == WRAPPER_PROPERTIES, 'Wrapper version/URL/checksum drift')
    return result


def toolchain_identity(root, java_home):
    """Bind the complete supplied Linux JDK tree plus the two Wrapper inputs.

    Record internal file links (used by JDK legal notices); reject directory
    links and external targets. Supply the resolved JDK, not a setup alias.
    The source identity must separately bind the entire clean repository.
    """
    root, java_home = canonical(root), canonical(java_home)
    require(root.is_dir() and java_home.is_dir(), 'toolchain directories required')
    release = canonical(java_home / 'release')
    versions = []
    for line in release.read_text(encoding='utf-8', errors='strict').splitlines():
        if line.startswith('JAVA_VERSION='):
            versions.append(json.loads(line.split('=', 1)[1]))
    require(len(versions) == 1 and isinstance(versions[0], str)
            and re.fullmatch(r'27(?:[.][0-9]+)*(?:[+\-][A-Za-z0-9.+\-]+)?', versions[0]),
            'unambiguous JDK27 release required')
    for name in ('bin/java', 'bin/javac', 'lib/modules', 'lib/server/libjvm.so'):
        require(canonical(java_home / name).is_file(), 'incomplete Linux JDK')
    files, links = {}, {}
    for path in sorted(java_home.rglob('*')):
        name = path.relative_to(java_home).as_posix()
        if path.is_symlink():
            target = path.resolve(strict=True)
            require(target.is_relative_to(java_home) and target.is_file(),
                    'external or directory JDK link')
            links[name] = os.readlink(path)
            files[name] = sha256(target)
            continue
        path = canonical(path)
        require(path.is_dir() or path.is_file(), 'nonregular JDK entry')
        if path.is_file():
            files[name] = sha256(path)
    properties_path = canonical(root / '.mvn/wrapper/maven-wrapper.properties')
    properties = _properties(properties_path)
    wrapper = canonical(root / 'mvnw')
    require(wrapper.is_file(), 'repository Wrapper script required')
    return {'java_home': str(java_home), 'java_version': versions[0], 'jdk_files': files,
            'jdk_links': links,
            'wrapper_properties': properties,
            'wrapper_files': {'mvnw': sha256(wrapper),
                              '.mvn/wrapper/maven-wrapper.properties': sha256(properties_path)}}


def controlled_environment(java_home, home, temporary):
    """Return a fresh allowlist; never read or serialize the ambient environment.

    HOME and TMPDIR must be distinct, existing empty canonical directories owned
    by this session. The caller creates them outside the checkout. Empty HOME
    plus generated MAVEN_OPTS prevents ambient Maven user settings being read.
    The direct diagnostic JVM must receive jvm_directory_options separately;
    Java does not derive user.home from HOME or honor MAVEN_OPTS by itself.
    This does not block network access or command-line JVM option injection.
    """
    java_home, home, temporary = map(canonical, (java_home, home, temporary))
    require(all(not any(c in str(path) for c in (':', '\n', '\r', '\0'))
                for path in (java_home, home, temporary)), 'Linux environment paths required')
    require(all(path.is_dir() for path in (java_home, home, temporary)),
            'environment directories required')
    paths = (java_home, home, temporary)
    require(all(not a.is_relative_to(b) and not b.is_relative_to(a)
                for index, a in enumerate(paths) for b in paths[index + 1:]),
            'environment directories must be disjoint')
    require(not any(home.iterdir()) and not any(temporary.iterdir()),
            'fresh empty HOME/TMPDIR required')
    options = jvm_directory_options(home, temporary)
    return {'JAVA_HOME': str(java_home), 'HOME': str(home), 'TMPDIR': str(temporary),
            'PATH': str(java_home / 'bin') + ':/usr/bin',
            'MAVEN_OPTS': ' '.join(options),
            'LANG': 'C.UTF-8', 'LC_ALL': 'C.UTF-8', 'TZ': 'UTC'}


def jvm_directory_options(home, temporary):
    """Explicit JVM flags; restrict whitespace for Wrapper's shell expansion."""
    home, temporary = map(canonical, (home, temporary))
    require(all(path.is_dir() and not any(c.isspace() for c in str(path))
                for path in (home, temporary)), 'whitespace-free JVM directories required')
    return ['-Duser.home=' + str(home), '-Djava.io.tmpdir=' + str(temporary)]
