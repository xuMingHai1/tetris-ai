"""Synthetic 20+60 envelope boundary tests; never runs games or consumes reserved seeds."""
import copy
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

import analyze_goal_window_prototype as prototype
import analyze_heart_paired as paired
from test_analyze_heart_paired import PROTOCOL_BYTES, fixture, game


def inputs():
    result = [fixture(p) for p in prototype.POPULATIONS]
    for data in result:
        data[prototype.SESSION_FIELD] = {'raw_sha256': 'b' * 64, 'manifest_sha256': 'c' * 64}
        for arm in ('baseline', 'candidate'):
            config = data['arms'][arm]['config']
            if arm == 'candidate':
                config['agent'] = 'goal-window'
                config['parameters'] = copy.deepcopy(prototype.EXPECTED_CONFIG)
            data['arms'][arm]['config_sha256'] = paired.digest(config)
            data['arms'][arm]['games'] = [game(g['seed'], arm, 800 if arm == 'baseline' else 900,
                500 if arm == 'candidate' and g['seed'] == 20000 else -1) for g in data['arms'][arm]['games']]
    return result


def replace(data, arm, index, **kwargs):
    old = data['arms'][arm]['games'][index]
    values = {'pieces': old['pieces_placed'], 'clean_at': old['first_clean_piece'],
              'nanos': old['max_decision_nanos']}
    values.update(kwargs)
    data['arms'][arm]['games'][index] = game(old['seed'], arm, **values)


class PrototypeTests(unittest.TestCase):
    def report(self, data=None):
        return prototype.analyze(*(inputs() if data is None else data), PROTOCOL_BYTES)

    def test_one_actual_clean_can_pass_prototype_while_frozen_adoption_gates_fail(self):
        report = self.report()
        self.assertTrue(report['prototype_gates_pass'])
        self.assertEqual(1, report['paired']['candidate_only_clean'])
        self.assertEqual(.5, report['paired']['clean_p_value_diagnostic'])
        self.assertFalse(report['group_diagnostics']['development']['all_headless_gates_pass'])
        self.assertEqual(80, len(report['pairs']))
        for key in ('provenance_verified', 'eligible_for_runtime_validation', 'runtime_permission'):
            self.assertFalse(report[key])
        self.assertEqual('synthetic-diagnostic-only', report['conclusion'])

    def test_measured_declaration_also_cannot_attest_sources_or_authorize_adoption(self):
        data = inputs()
        for envelope in data:
            envelope['evidence_kind'] = 'measured'
        report = self.report(data)
        self.assertTrue(report['prototype_gates_pass'])
        self.assertFalse(report['provenance_verified'])
        self.assertFalse(report['eligible_for_runtime_validation'])
        self.assertFalse(report['runtime_permission'])

    def test_clean_500_is_included_501_and_no_clean_are_not(self):
        for clean_at in (500, 501, -1):
            data = inputs()
            replace(data[0], 'candidate', 0, clean_at=clean_at)
            report = self.report(data)
            self.assertEqual(clean_at == 500, report['gates']['candidate_has_clean_by_500']['pass'])
            self.assertEqual(clean_at == 500, report['prototype_gates_pass'])

    def test_actual_clean_without_positive_discordant_gain_stops(self):
        data = inputs()
        replace(data[0], 'baseline', 0, clean_at=500)
        report = self.report(data)
        self.assertTrue(report['gates']['candidate_has_clean_by_500']['pass'])
        self.assertFalse(report['gates']['positive_paired_clean_gain']['pass'])
        self.assertFalse(report['prototype_gates_pass'])

    def test_four_of_80_losses_pass_even_when_group_fraction_fails_five_do_not(self):
        data = inputs()
        for index in range(4):
            replace(data[0], 'candidate', index, pieces=799)
        report = self.report(data)
        self.assertFalse(report['group_diagnostics']['development']['gates']['maximum_regressed_seed_fraction']['pass'])
        self.assertTrue(report['prototype_gates_pass'])
        replace(data[0], 'candidate', 4, pieces=799)
        self.assertFalse(self.report(data)['gates']['maximum_regressed_seed_fraction']['pass'])

    def test_maximum_loss_100_101_boundary(self):
        for pieces in (700, 699):
            data = inputs()
            replace(data[0], 'candidate', 0, pieces=pieces)
            self.assertEqual(pieces == 700, self.report(data)['gates']['maximum_piece_loss']['pass'])

    def test_mean_and_median_deltas_are_independent(self):
        data = inputs()
        for envelope in data:
            for index in range(len(envelope['arms']['candidate']['games'])):
                replace(envelope, 'candidate', index, pieces=800)
        replace(data[0], 'candidate', 0, pieces=799)
        report = self.report(data)
        self.assertFalse(report['gates']['minimum_mean_piece_delta']['pass'])
        self.assertTrue(report['gates']['minimum_median_piece_delta']['pass'])
        for envelope in data:
            for index, old in enumerate(envelope['arms']['candidate']['games']):
                replace(envelope, 'candidate', index, pieces=799 if old['seed'] < 20041 else 900)
        report = self.report(data)
        self.assertTrue(report['gates']['minimum_mean_piece_delta']['pass'])
        self.assertFalse(report['gates']['minimum_median_piece_delta']['pass'])

    def test_survivor_loss_is_independent_of_rounded_horizon_loss(self):
        data = inputs()
        replace(data[0], 'baseline', 0, pieces=1000)
        replace(data[0], 'candidate', 0, pieces=999)
        report = self.report(data)
        self.assertFalse(report['gates']['maximum_baseline_survivors_lost']['pass'])
        self.assertTrue(report['gates']['maximum_horizon_rate_loss']['pass'])
        replace(data[0], 'baseline', 1, pieces=1000)
        self.assertFalse(self.report(data)['gates']['maximum_horizon_rate_loss']['pass'])

    def test_latency_pools_all_decisions_per_arm_instead_of_group_percentiles(self):
        data = inputs()
        for index in range(2):
            replace(data[0], 'candidate', index, nanos=51_000_000)
        report = self.report(data)
        self.assertFalse(report['group_diagnostics']['development']['gates']['candidate.decision_p95_ms']['pass'])
        self.assertTrue(report['gates']['candidate.decision_p95_ms']['pass'])
        self.assertTrue(report['prototype_gates_pass'])
        for envelope in data:
            for index in range(len(envelope['arms']['candidate']['games'])):
                replace(envelope, 'candidate', index, nanos=50_000_001)
        report = self.report(data)
        self.assertFalse(report['gates']['candidate.decision_p95_ms']['pass'])
        self.assertTrue(report['gates']['baseline.decision_p95_ms']['pass'])

    def test_exact_p95_and_max_boundaries_for_both_arms(self):
        for arm in ('baseline', 'candidate'):
            data = inputs()
            for envelope in data:
                for index in range(len(envelope['arms'][arm]['games'])):
                    replace(envelope, arm, index, nanos=50_000_000)
            self.assertTrue(self.report(data)['gates'][arm + '.decision_p95_ms']['pass'])
            data = inputs()
            replace(data[0], arm, 0, nanos=250_000_000)
            self.assertTrue(self.report(data)['gates'][arm + '.decision_max_ms']['pass'])
            replace(data[0], arm, 0, nanos=250_000_001)
            self.assertFalse(self.report(data)['gates'][arm + '.decision_max_ms']['pass'])

    def test_correctness_counts_are_not_filled_with_zero_or_hidden_by_gain(self):
        for arm in ('baseline', 'candidate'):
            for field in ('primary_failures', 'fallback_decisions', 'illegal_plans'):
                data = inputs()
                data[0]['arms'][arm]['games'][0][field] = 1
                report = self.report(data)
                self.assertFalse(report['gates'][arm + '.' + field]['pass'])
                self.assertFalse(report['prototype_gates_pass'])
                del data[0]['arms'][arm]['games'][0][field]
                with self.assertRaises(KeyError):
                    self.report(data)

    def test_missing_duplicate_and_wrong_population_are_invalid(self):
        for kind in ('missing', 'duplicate', 'population', 'timing', 'clean'):
            data = inputs()
            games = data[1]['arms']['candidate']['games']
            if kind == 'missing':
                games.pop()
            elif kind == 'duplicate':
                games.append(games[0])
            elif kind == 'population':
                data[1]['population'] = 'validation'
            else:
                games[0] = copy.deepcopy(games[0])
                if kind == 'timing':
                    games[0]['decision_facts'].pop()
                else:
                    games[0]['decision_facts'][0]['clean_completion'] = True
            with self.assertRaises(ValueError):
                self.report(data)

    def test_cross_population_identity_environment_config_warmup_and_session_must_match(self):
        for kind in ('source', 'environment', 'config', 'warmup', 'session', 'evidence'):
            data = inputs()
            if kind == 'source':
                data[1]['arms']['candidate']['source_sha'] = 'd' * 40
            elif kind == 'environment':
                data[1]['environment']['cpu'] = 'another host'
            elif kind == 'config':
                arm = data[1]['arms']['candidate']
                arm['config']['parameters']['setup_top_k'] = 4
                arm['config_sha256'] = paired.digest(arm['config'])
            elif kind == 'warmup':
                data[1]['arms']['candidate']['warmup']['decisions'] = 99
            elif kind == 'session':
                data[1][prototype.SESSION_FIELD]['raw_sha256'] = 'd' * 64
            else:
                data[1]['evidence_kind'] = 'measured'
            with self.assertRaises(ValueError):
                self.report(data)

    def test_different_arm_checkout_wrong_agent_or_missing_reference_is_invalid(self):
        for kind in ('arm_source', 'agent', 'reference', 'hash', 'parameters', 'rescue'):
            data = inputs()
            if kind == 'arm_source':
                for envelope in data:
                    envelope['arms']['candidate']['source_sha'] = 'd' * 40
            elif kind == 'agent':
                for envelope in data:
                    arm = envelope['arms']['candidate']
                    arm['config']['agent'] = 'other-candidate'
                    arm['config_sha256'] = paired.digest(arm['config'])
            elif kind == 'reference':
                del data[0][prototype.SESSION_FIELD]
            elif kind in ('parameters', 'rescue'):
                for envelope in data:
                    arm = envelope['arms']['candidate']
                    if kind == 'parameters':
                        arm['config']['parameters']['setup_top_k'] = 4
                    else:
                        arm['config']['preview_rescue'] = True
                    arm['config_sha256'] = paired.digest(arm['config'])
            else:
                data[0][prototype.SESSION_FIELD]['manifest_sha256'] = 'wrong'
            with self.assertRaises((ValueError, KeyError)):
                self.report(data)

    def test_cli_rejects_deep_json_in_either_envelope_or_protocol_without_report(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            paths = [root / name for name in ('development.json', 'calibration.json', 'protocol.json')]
            valid = [json.dumps(value).encode('utf-8') for value in inputs()] + [PROTOCOL_BYTES]
            # Exercise the real child interpreter's decoder, not a mocked exception.
            nested = b'[' * 20000 + b'0' + b']' * 20000
            for index in range(len(paths)):
                with self.subTest(input=paths[index].name):
                    for number, path in enumerate(paths):
                        path.write_bytes(nested if number == index else valid[number])
                    output = root / str(index) / 'report.json'
                    command = [sys.executable, str(Path(prototype.__file__)),
                               *(str(p) for p in paths[:2]), '--protocol', str(paths[2]),
                               '--output', str(output)]
                    completed = subprocess.run(command, capture_output=True, text=True)
                    self.assertEqual(2, completed.returncode, completed.stderr)
                    self.assertIn('Invalid prototype evidence/output:', completed.stderr)
                    self.assertNotIn('Traceback', completed.stderr)
                    self.assertFalse(output.parent.exists())

    def test_cli_exit_codes_refuse_invalid_json_and_preserve_existing_report(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            data = inputs()
            paths = [root / (population + '.json') for population in prototype.POPULATIONS]
            for path, value in zip(paths, data):
                path.write_text(json.dumps(value))
            report = root / 'report.json'
            script = Path(prototype.__file__)
            command = [sys.executable, str(script), *(str(p) for p in paths), '--output', str(report)]
            completed = subprocess.run(command, capture_output=True, text=True)
            self.assertEqual(0, completed.returncode, completed.stderr)
            original = report.read_bytes()
            self.assertFalse(json.loads(original)['eligible_for_runtime_validation'])
            self.assertEqual(hashlib.sha256(paths[0].read_bytes()).hexdigest(),
                             json.loads(original)['input_files_sha256']['development'])
            self.assertEqual(2, subprocess.run(command, capture_output=True).returncode)
            self.assertEqual(original, report.read_bytes())
            replace(data[0], 'candidate', 0, clean_at=-1)
            paths[0].write_text(json.dumps(data[0]))
            command[-1] = str(root / 'failed-gates.json')
            self.assertEqual(1, subprocess.run(command, capture_output=True).returncode)
            self.assertFalse(json.loads(Path(command[-1]).read_text())['prototype_gates_pass'])
            for text in ('{"population":"development","population":"development"}', '{"value":NaN}'):
                paths[0].write_text(text)
                command[-1] = str(root / 'invalid.json')
                self.assertEqual(2, subprocess.run(command, capture_output=True).returncode)
                self.assertFalse(Path(command[-1]).exists())


if __name__ == '__main__':
    unittest.main()
