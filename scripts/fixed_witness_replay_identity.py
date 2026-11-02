"""Byte identities for fixed-witness replay; no build, launch or fixture reader.

These independent primitives bind a clean checkout and explicit classpath. They
do not attest compiler trust, loaded classes, argv, budgets or diagnostic success.
The final supervisor must retain and compare identities before AND after use.
"""
import hashlib
from pathlib import Path
import re
import subprocess
import zipfile


def require(condition, message):
    if not condition:
        raise ValueError(message)


def canonical(path):
    path = Path(path).absolute()
    require(path == path.resolve(strict=True), 'linked/noncanonical path: ' + str(path))
    return path


def sha256(path):
    path = canonical(path)
    require(path.is_file(), 'regular file required')
    value = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(65536), b''):
            value.update(block)
    return value.hexdigest()


def _git(root, *args, environment=None):
    result = subprocess.run(['git', *args], cwd=root, check=True,
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=10,
                            env=environment)
    return result.stdout.decode('utf-8', errors='strict')


def source_identity(root, *, environment=None):
    """Bind every tracked byte; reject dirty/hidden inputs and linked paths.

    Commands are small inventory reads, not the build supervisor. Ignored files
    under source/build configuration directories can silently affect compilation.
    """
    root = canonical(root)
    def git(*args):
        return _git(root, *args, environment=environment)

    require(Path(git('rev-parse', '--show-toplevel').strip()).resolve() == root,
            'checkout root must be Git top-level')
    sha = git('rev-parse', 'HEAD').strip()
    require(re.fullmatch('[0-9a-f]{40}', sha), 'SHA-1 source commit required')
    require(not git('status', '--porcelain', '--untracked-files=all').strip(),
            'dirty checkout')
    entries = git('ls-files', '-v', '-z').split('\0')
    require(all(entry.startswith('H ') for entry in entries if entry), 'hidden index flags')
    require(not git('ls-files', '--others', '--ignored', '--exclude-standard',
                     '--', 'src', '.mvn').strip(), 'ignored source/build input')
    names = [entry[2:] for entry in entries if entry]
    require(names and len(names) == len(set(names)), 'empty/duplicate source inventory')
    files = {}
    for name in names:
        path = canonical(root / name)
        require(path.is_relative_to(root), 'source escapes checkout')
        files[name] = sha256(path)
    return {'source_sha': sha, 'source_files': files}


def _compiled(root):
    files, classes = {}, {}
    for base in ('target/classes', 'target/test-classes'):
        directory = canonical(root / base)
        require(directory.is_dir(), 'compiled directory required')
        for path in sorted(directory.rglob('*')):
            path = canonical(path)
            require(path.is_dir() or path.is_file(), 'nonregular compiled input')
            if path.is_file():
                relative = path.relative_to(directory).as_posix()
                files[base + '/' + relative] = sha256(path)
                if relative.endswith('.class') and relative != 'module-info.class':
                    require(relative not in classes, 'main/test class shadow: ' + relative)
                    classes[relative] = base
    require(classes, 'no compiled classes')
    return files, classes


def classpath_identity(root, dependencies):
    """Bind all compiled resources and jar bytes; reject classpath extensions.

    Multi-release variants within one jar count as one class owner. All physical
    class names participate conservatively, even versions not selected by JDK27.
    module-info is metadata, so it is excluded only from the shadow comparison.
    """
    root = canonical(root)
    files, owners = _compiled(root)
    jars = {}
    for dependency in dependencies:
        path = canonical(dependency)
        require(path.is_file() and path.suffix == '.jar', 'explicit jar required')
        require(str(path) not in jars, 'duplicate classpath entry')
        with zipfile.ZipFile(path) as archive:
            names = archive.namelist()
            require(len(names) == len(set(names)), 'duplicate jar entry')
            manifests = [n for n in names if n.upper() == 'META-INF/MANIFEST.MF']
            require(len(manifests) <= 1, 'ambiguous jar manifest')
            for name in manifests:
                manifest = archive.read(name).decode('utf-8', errors='strict')
                # The JAR specification permits CR, LF and CRLF line endings.
                manifest = manifest.replace('\r\n', '\n').replace('\r', '\n')
                manifest = manifest.replace('\n ', '')
                require(not re.search(r'^Class-Path\s*:', manifest, re.I | re.M),
                        'implicit jar Class-Path')
            local_classes = set()
            for name in names:
                if name.endswith('.class'):
                    normalized = re.sub(r'^META-INF/versions/[0-9]+/', '', name)
                    if normalized != 'module-info.class':
                        local_classes.add(normalized)
            for name in local_classes:
                require(name not in owners, 'classpath class shadow: ' + name)
                owners[name] = str(path)
        jars[str(path)] = sha256(path)
    return {'classpath': [str(root / 'target/classes'), str(root / 'target/test-classes'), *jars],
            'compiled_files': files, 'dependencies': jars}


def verify_identity(expected, actual):
    """The expected value must be retained independently by the supervisor."""
    require(expected == actual, 'source/classpath identity drift')
