"""Check the archived negative HEART logs without executing a JVM or certifying provenance."""
import argparse
import hashlib
import json
import math
from pathlib import Path
import re


SEEDS = list(range(20000, 20020))
SOURCE_SHA = '76e8203c5339ba449bd4fef2b94866b552f5a423'
PROTOCOL_SHA256 = 'facddfb67ec0b26673e9372a1647770c9074987b624267e88fc4be5cf1870c4b'
REGISTERED_HASHES = {
    'prefix-raw.log': 'ba70328ad551cecdcd65a7ce5dc63927ec7cfd6272fdb68bb301379d5a2a5025',
    'prefix-summary.json': 'f698cda2b57850f7158568b305ccdc3667574554f256ad7f309fb3d1e337769c',
    'prefix-metadata.json': '9d980a434734195565b033b6ff9d9c8ae33211339a56a64af4e297d0406337fb',
}
PIECES = set('IJLOSTZ')


def require(condition, message):
    if not condition:
        raise ValueError(message)


def integer(value):
    require(isinstance(value, str) and re.fullmatch(r'0|[1-9][0-9]*', value),
            'invalid nonnegative integer')
    return int(value)


def finite_nonnegative(value):
    require(not isinstance(value, bool) and isinstance(value, (str, int, float)), 'invalid timing')
    number = float(value)
    require(math.isfinite(number) and number >= 0, 'invalid timing')
    return number


def parse_seed(raw, seed):
    """Validate complete negative depths; a miss never establishes exact exhaustion."""
    sequences = [line for line in raw.splitlines() if line.startswith('# sequence')]
    require(len(sequences) == 1, 'missing or duplicate sequence')
    match = re.fullmatch(r'# sequence seed=([0-9]+) pieces=([A-Z>]+)', sequences[0])
    require(match is not None and int(match[1]) == seed, 'wrong sequence seed')
    pieces = match[2].split('>')
    require(len(pieces) == 24 and all(piece in PIECES for piece in pieces), 'invalid sequence')
    # Shape validation only; this does not attest that this seed generated the sequence.
    for start in (0, 7, 14, 21):
        bag = pieces[start:start + 7]
        require(len(set(bag)) == len(bag), 'invalid 7-bag sequence')

    records = [line.split(',') for line in raw.splitlines() if line.startswith('prefix_')]
    require(records and records[-1][0] == 'prefix_result', 'missing terminal evidence')
    require(all(row[0] == 'prefix_depth' for row in records[:-1]),
            'unexpected, duplicate or misplaced record')
    terminal = records[-1]
    require(len(terminal) == 8 and integer(terminal[1]) == seed, 'invalid terminal')
    require(terminal[2] == 'BOUNDED_MISS', 'only negative archive evidence is supported')
    depths = records[:-1]
    require(1 <= len(depths) <= 24, 'missing or invalid depth records')
    frontier, roots, generated, admitted, pruned = 1, 0, 0, 0, 0
    for depth, row in enumerate(depths, 1):
        require(frontier > 0 and len(row) == 10, 'depth after exhausted frontier or malformed depth')
        require(integer(row[1]) == seed and integer(row[2]) == depth, 'wrong seed or depth order')
        expanded, created, accepted, unique, retained, removed = map(integer, row[3:9])
        require(row[9] == 'false', 'clean depth cannot be a negative result')
        roots += frontier
        require(expanded == roots and roots <= 2945, 'inconsistent expanded roots')
        require(created >= accepted >= unique and (accepted == 0) == (unique == 0),
                'inconsistent generated/admitted/unique counts')
        require(retained == min(unique, 128) and removed == unique - retained,
                'inconsistent beam counts')
        frontier = retained
        generated += created
        admitted += accepted
        pruned += removed
    searched, final_roots, final_generated, best_error = map(integer, terminal[3:7])
    require(searched == len(depths) and final_roots == roots and final_generated == generated,
            'terminal/depth totals disagree')
    require(searched == 24 or frontier == 0, 'unfinished frontier reported as miss')
    require(1 <= best_error <= 32, 'negative result cannot have clean visual error')
    return dict(seed=seed, status='BOUNDED_MISS', depths=searched, roots=roots,
                generated=generated, admitted=admitted, pruned=pruned,
                best_error=best_error, search_elapsed_ms=finite_nonnegative(terminal[7]))


def verify(directory, expected_hashes=None):
    """Hashes bind the recorded files; declared SHA/classpath cannot attest compiled provenance."""
    expected_hashes = REGISTERED_HASHES if expected_hashes is None else expected_hashes
    require(set(expected_hashes) == set(REGISTERED_HASHES), 'missing registered checksum entries')
    for name, expected in expected_hashes.items():
        require(isinstance(expected, str) and re.fullmatch(r'[0-9a-f]{64}', expected),
                'invalid expected checksum')
        require(hashlib.sha256((directory / name).read_bytes()).hexdigest() == expected,
                'checksum mismatch: ' + name)
    metadata = json.loads((directory / 'prefix-metadata.json').read_text(encoding='utf-8'))
    summary = json.loads((directory / 'prefix-summary.json').read_text(encoding='utf-8'))
    require(metadata['source_sha'] == SOURCE_SHA
            and metadata['protocol_sha256'] == PROTOCOL_SHA256, 'wrong declared source or protocol')
    require(metadata['seeds'] == SEEDS and all(type(seed) is int for seed in metadata['seeds'])
            and metadata['kind'] == 'OFFLINE_EXISTENCE_ONLY',
            'wrong population or evidence kind')
    for name, expected in dict(rows=20, cols=10, depth=24, beam=128,
                               roots_per_seed=2945, timeout_seconds=60).items():
        require(type(metadata[name]) is int and metadata[name] == expected, 'wrong budget: ' + name)
    require(metadata['cpu_seconds'] is None and metadata['peak_rss_bytes'] is None,
            'unavailable resource values changed')
    require(summary['complete'] is True and summary['runtime_permission'] is False
            and summary['unrun'] == [], 'incomplete or mislabelled summary')
    results = summary['results']
    require(len(results) == 20 and [row['seed'] for row in results] == SEEDS, 'wrong summary seeds')
    logs, parsed = [], []
    for seed, result in zip(SEEDS, results):
        require(type(result['seed']) is int and result['status'] == 'BOUNDED_MISS',
                'summary is not a complete negative population')
        finite_nonnegative(result['elapsed_seconds'])
        raw = (directory / f'prefix-{seed}.log').read_text(encoding='utf-8')
        logs.append(raw)
        parsed.append(parse_seed(raw, seed))
    require((directory / 'prefix-raw.log').read_text(encoding='utf-8') == ''.join(logs),
            'combined raw log differs from ordered seed logs')
    return dict(log_integrity_verified=True,
                registered_checksums_verified=expected_hashes == REGISTERED_HASHES,
                source_sha_declared=SOURCE_SHA,
                build_provenance_verified=False, seed_sequence_attested=False,
                runtime_permission=False, clean_witnesses=0, bounded_misses=20,
                generated=sum(row['generated'] for row in parsed),
                admitted=sum(row['admitted'] for row in parsed),
                pruned=sum(row['pruned'] for row in parsed),
                minimum_visual_error=min(row['best_error'] for row in parsed),
                cpu_seconds=None, peak_rss_bytes=None, seeds=parsed)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory', type=Path)
    args = parser.parse_args()
    try:
        print(json.dumps(verify(args.directory), indent=2))
    except (ValueError, OSError, KeyError, TypeError, OverflowError) as error:
        parser.exit(2, 'Evidence verification failed: ' + str(error) + '\n')
