"""Synthetic corruption regressions; these fixtures are not the measured population."""
import hashlib
import json
from pathlib import Path
import tempfile
import unittest

from verify_heart_prefix_evidence import (
    PROTOCOL_SHA256, SEEDS, SOURCE_SHA, parse_seed, verify,
)


def log(seed):
    sequence = ('I>J>L>O>S>T>Z>' * 4).rstrip('>').split('>')[:24]
    return (f'# sequence seed={seed} pieces={">".join(sequence)}\n'
            f'prefix_depth,{seed},1,1,3,0,0,0,0,false\n'
            f'prefix_result,{seed},BOUNDED_MISS,1,1,3,32,1.5\n')


class PrefixEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.metadata = dict(source_sha=SOURCE_SHA, protocol_sha256=PROTOCOL_SHA256,
                             seeds=SEEDS, kind='OFFLINE_EXISTENCE_ONLY', rows=20, cols=10,
                             depth=24, beam=128, roots_per_seed=2945, timeout_seconds=60,
                             cpu_seconds=None, peak_rss_bytes=None)
        self.summary = dict(complete=True, runtime_permission=False, unrun=[], results=[
            dict(seed=seed, status='BOUNDED_MISS', elapsed_seconds=0.01) for seed in SEEDS])
        for seed in SEEDS:
            (self.root / f'prefix-{seed}.log').write_text(log(seed), encoding='utf-8')

    def write(self):
        (self.root / 'prefix-metadata.json').write_text(json.dumps(self.metadata), encoding='utf-8')
        (self.root / 'prefix-summary.json').write_text(json.dumps(self.summary), encoding='utf-8')
        (self.root / 'prefix-raw.log').write_text(''.join(
            (self.root / f'prefix-{seed}.log').read_text(encoding='utf-8') for seed in SEEDS),
            encoding='utf-8')
        return {name: hashlib.sha256((self.root / name).read_bytes()).hexdigest()
                for name in ('prefix-raw.log', 'prefix-summary.json', 'prefix-metadata.json')}

    def test_complete_records_verify_only_log_integrity(self):
        result = verify(self.root, self.write())
        self.assertTrue(result['log_integrity_verified'])
        self.assertFalse(result['registered_checksums_verified'])
        self.assertEqual(60, result['generated'])
        self.assertFalse(result['build_provenance_verified'])
        self.assertFalse(result['seed_sequence_attested'])
        self.assertFalse(result['runtime_permission'])

    def test_terminal_only_fabrication_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'sequence'):
            parse_seed('prefix_result,20000,BOUNDED_MISS,0,0,0,0,0\n', 20000)
        with self.assertRaisesRegex(ValueError, 'depth'):
            parse_seed(log(20000).splitlines()[0] + '\n'
                       + 'prefix_result,20000,BOUNDED_MISS,0,0,0,0,0\n', 20000)

    def test_wrong_roots_totals_order_and_mislabelled_clean_are_rejected(self):
        corrupt = [
            log(20000).replace('1,1,3,0,0,0,0,false', '1,2,3,0,0,0,0,false'),
            log(20000).replace('BOUNDED_MISS,1,1,3,32', 'BOUNDED_MISS,1,1,4,32'),
            log(20000).replace('prefix_depth,20000,1', 'prefix_depth,20000,2'),
            log(20000).replace(',32,1.5', ',0,1.5'),
            log(20000).replace(',false\n', ',true\n'),
            log(20000).replace('BOUNDED_MISS', 'REPLAYED_CLEAN'),
        ]
        for raw in corrupt:
            with self.subTest(raw=raw), self.assertRaises(ValueError):
                parse_seed(raw, 20000)

    def test_active_frontier_or_inconsistent_beam_is_rejected(self):
        active = log(20000).replace('3,0,0,0,0,false', '3,1,1,1,0,false')
        with self.assertRaisesRegex(ValueError, 'unfinished'):
            parse_seed(active, 20000)
        with self.assertRaisesRegex(ValueError, 'beam'):
            parse_seed(active.replace('3,1,1,1,0,false', '3,1,1,0,1,false'), 20000)

    def test_duplicate_records_missing_logs_and_checksum_drift_are_rejected(self):
        with self.assertRaises(ValueError):
            parse_seed(log(20000) + log(20000), 20000)
        hashes = self.write()
        path = self.root / 'prefix-20000.log'
        path.write_text(log(20000).replace('1.5', '1.6'), encoding='utf-8')
        with self.assertRaisesRegex(ValueError, 'combined'):
            verify(self.root, hashes)
        hashes = self.write()
        (self.root / 'prefix-raw.log').write_text('changed', encoding='utf-8')
        with self.assertRaisesRegex(ValueError, 'checksum'):
            verify(self.root, hashes)
        self.write()
        path.unlink()
        with self.assertRaises(OSError):
            verify(self.root, self.write_hashes())

    def write_hashes(self):
        return {name: hashlib.sha256((self.root / name).read_bytes()).hexdigest()
                for name in ('prefix-raw.log', 'prefix-summary.json', 'prefix-metadata.json')}

    def test_wrong_population_source_budget_and_unavailable_resources_fail(self):
        for key, value in [('seeds', SEEDS[::-1]), ('source_sha', 'f' * 40), ('depth', 25),
                           ('beam', 129), ('roots_per_seed', True), ('cpu_seconds', 0)]:
            before = self.metadata[key]
            self.metadata[key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                verify(self.root, self.write())
            self.metadata[key] = before

    def test_missing_checksum_entries_cannot_skip_hash_validation(self):
        self.write()
        with self.assertRaisesRegex(ValueError, 'checksum entries'):
            verify(self.root, {})

    def test_failed_partial_and_duplicate_summary_cannot_be_completed(self):
        for change in ('error', 'partial', 'duplicate'):
            summary = json.loads(json.dumps(self.summary))
            if change == 'error':
                summary['results'][0]['status'] = 'ERROR'
            elif change == 'partial':
                summary['unrun'] = [20019]
            else:
                summary['results'][1]['seed'] = 20000
            original, self.summary = self.summary, summary
            with self.subTest(change=change), self.assertRaises(ValueError):
                verify(self.root, self.write())
            self.summary = original

    def test_wrong_sequence_and_nonfinite_timing_are_rejected(self):
        for raw in [log(20000).replace('I>J>L>O>S>T>Z', 'O>O>O>O>O>O>O'),
                    log(20000).replace('seed=20000', 'seed=20001'),
                    log(20000).replace('1.5', 'nan')]:
            with self.assertRaises(ValueError):
                parse_seed(raw, 20000)


if __name__ == '__main__':
    unittest.main()
