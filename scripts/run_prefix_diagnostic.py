"""Supervise the preregistered offline HEART population; timeout is incomplete, never a miss."""
import argparse
import hashlib
import json
from pathlib import Path
import platform
import subprocess
import time


def run_population(command, output, sha):
    output.mkdir(parents=True, exist_ok=True)
    protocol = Path('docs/design/heart-prefix-domain.md').read_bytes()
    metadata = dict(source_sha=sha, protocol_sha256=hashlib.sha256(protocol).hexdigest(),
                    seeds=list(range(20000, 20020)), rows=20, cols=10, depth=24, beam=128,
                    roots_per_seed=2945, timeout_seconds=60, host=platform.platform(),
                    kind='OFFLINE_EXISTENCE_ONLY', command=command,
                    peak_rss_bytes=None, cpu_seconds=None,
                    java_version=subprocess.run([command[0], '-version'], capture_output=True,
                                                text=True, check=True).stderr,
                    started_utc=time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime()))
    (output / 'prefix-metadata.json').write_text(json.dumps(metadata, indent=2), encoding='utf-8')
    results = []
    with (output / 'prefix-raw.log').open('w', encoding='utf-8') as combined:
        for seed in metadata['seeds']:
            seed_log = output / f'prefix-{seed}.log'
            status = 'ERROR'
            started = time.monotonic()
            with seed_log.open('w', encoding='utf-8') as log:
                # Direct JVM child: no Maven/shell grandchildren can outlive this budget.
                child = subprocess.Popen(command + [str(seed)], stdout=log, stderr=subprocess.STDOUT)
                try:
                    code = child.wait(timeout=60)
                    status = 'FINISHED' if code == 0 else 'ERROR'
                except subprocess.TimeoutExpired:
                    child.kill()
                    child.wait()
                    status = 'INCOMPLETE'
            raw = seed_log.read_text(encoding='utf-8')
            combined.write(raw)
            combined.flush()
            terminal = [line for line in raw.splitlines() if line.startswith('prefix_result,')]
            if status == 'FINISHED':
                if len(terminal) != 1 or terminal[0].split(',')[1] != str(seed):
                    status = 'ERROR'
                else:
                    status = terminal[0].split(',')[2]
                    if status not in ('REPLAYED_CLEAN', 'BOUNDED_MISS'):
                        status = 'ERROR'
            results.append(dict(seed=seed, status=status, elapsed_seconds=time.monotonic() - started))
            (output / 'prefix-summary.json').write_text(
                json.dumps(dict(results=results, unrun=metadata['seeds'][len(results):],
                                complete=len(results) == 20 and status not in ('INCOMPLETE', 'ERROR'),
                                runtime_permission=False), indent=2), encoding='utf-8')
            print(f'{seed}: {status}', flush=True)
            if status in ('INCOMPLETE', 'ERROR'):
                return 2
    return 0


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java', default='java')
    parser.add_argument('--classpath', required=True)
    parser.add_argument('--source-sha', required=True)
    parser.add_argument('--output', type=Path, default=Path('target/benchmark'))
    args = parser.parse_args()
    raise SystemExit(run_population(
        [args.java, '-Xmx512m', '-cp', args.classpath,
         'xyz.xuminghai.tetris.ai.benchmark.HeartPrefixDiagnosticApplication'],
        args.output, args.source_sha))
