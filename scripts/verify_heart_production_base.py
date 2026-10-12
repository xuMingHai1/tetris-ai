"""Reject strategy/build drift before sampling the registered HEART baseline."""
import argparse
import json
import re
import subprocess
from pathlib import Path


def verify(root, protocol_path):
    protocol = json.loads(protocol_path.read_text())
    base = protocol['production_base_sha']
    if not isinstance(base, str) or not re.fullmatch(r'[0-9a-f]{40}', base):
        raise ValueError('production_base_sha must be a full lowercase commit SHA')
    # The workflow fetches this exact commit, never a mutable branch name.
    def git(*args):
        return subprocess.check_output(['git', '-C', str(root), *args])
    if git('rev-parse', base + '^{commit}').decode().strip() != base:
        raise ValueError('registered production base is not a commit')
    changed = git('diff', '--name-only', '--no-renames', '-z', base, '--',
                  'src/main', 'pom.xml', '.mvn', 'mvnw', 'mvnw.cmd').decode().split('\0')
    # Only evaluation instrumentation may differ. Protect all other production
    # sources/resources and build inputs, including additions and deletions.
    drift = [p for p in changed if p and not p.startswith(
        'src/main/java/xyz/xuminghai/tetris/ai/benchmark/')]
    if drift:
        raise ValueError('frozen HEART production differs: ' + ', '.join(drift))
    return {'production_base_sha': base,
            'measured_source_sha': git('rev-parse', 'HEAD').decode().strip(),
            'production_diff_verified': True}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path('.'))
    parser.add_argument('--protocol', type=Path,
                        default=Path('docs/research/heart-acceptance-v1.json'))
    args = parser.parse_args()
    print(json.dumps(verify(args.root, args.protocol), sort_keys=True))
