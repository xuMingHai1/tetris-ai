"""Evaluate the registered 80-seed GoalWindow prototype gates from two #105 envelopes.

This is an analysis-only tool: it neither runs games nor attests the referenced raw/build evidence.
"""
import argparse
import hashlib
import json
from pathlib import Path
import statistics

import analyze_heart_paired as paired
from prepare_goal_window_build import EXPECTED_CONFIG
from read_goal_window_bundle import read_bundle

POPULATIONS = ('development', 'calibration')
SESSION_FIELD = 'goal_window_session'


def analyze(development, calibration, protocol_bytes):
    inputs = dict(zip(POPULATIONS, (development, calibration)))
    reports = {}
    for population, data in inputs.items():
        paired.require(isinstance(data, dict) and data['population'] == population,
                       'expected development then calibration; validation is not a prototype population')
        # Keep #105's frozen validation and full group diagnostics unchanged.
        reports[population] = paired.analyze(data, protocol_bytes)
    protocol = json.loads(protocol_bytes)
    for field in ('schema', 'protocol_sha256', 'evidence_kind', 'environment', 'timing_scope',
                  'input_boundary', 'reset_strategy_per_game'):
        paired.require(development[field] == calibration[field], 'cross-population metadata mismatch: ' + field)
    reference = development[SESSION_FIELD]
    paired.require(isinstance(reference, dict) and set(reference) == {'raw_sha256', 'manifest_sha256'},
                   'missing/invalid common session reference')
    for value in reference.values():
        paired.sha(value, 64)
    paired.require(calibration[SESSION_FIELD] == reference, 'populations must reference one raw session/build')
    for arm in ('baseline', 'candidate'):
        for field in ('source_sha', 'config', 'config_sha256', 'warmup'):
            paired.require(development['arms'][arm][field] == calibration['arms'][arm][field],
                           'cross-population arm mismatch: ' + arm + '.' + field)
    paired.require(development['arms']['baseline']['source_sha'] == development['arms']['candidate']['source_sha'],
                   'GoalWindow arms must use the same measurement checkout')
    config = development['arms']['candidate']['config']
    paired.require(config['agent'] == 'goal-window' and config['preview_rescue'] is False,
                   'candidate must be the GoalWindow prototype')
    paired.require(paired.digest(config['parameters']) == paired.digest(EXPECTED_CONFIG),
                   'candidate parameters must contain the entire frozen GoalWindow config')

    expected = set().union(*(paired.seed_set(protocol['populations'][p]) for p in POPULATIONS))
    paired.require(expected == set(range(20000, 20080)), 'wrong registered prototype population')
    summaries = {}
    for arm in ('baseline', 'candidate'):
        games = sorted((game for data in inputs.values() for game in data['arms'][arm]['games']),
                       key=lambda game: game['seed'])
        paired.require(len(games) == 80 and {g['seed'] for g in games} == expected, 'incomplete combined population')
        # Reuse the original pure statistics over each arm's entire decision pool. Never
        # average group percentiles or mix a cheap baseline into candidate latency.
        summaries[arm] = paired.summarize(games,
            [[row['decision_nanos'] for row in game['decision_facts']] for game in games],
            protocol['completion_piece_limit'])
    pairs = sorted((pair for report in reports.values() for pair in report['pairs']), key=lambda pair: pair['seed'])
    deltas = [pair['pieces_delta'] for pair in pairs]
    won = sum(p['candidate']['clean_by_limit'] and not p['baseline']['clean_by_limit'] for p in pairs)
    lost = sum(p['baseline']['clean_by_limit'] and not p['candidate']['clean_by_limit'] for p in pairs)
    survivor_losses = [p['seed'] for p in pairs if p['baseline']['reached_piece_limit']
                       and not p['candidate']['reached_piece_limit']]
    combined = {
        'candidate_only_clean': won, 'baseline_only_clean': lost,
        'clean_p_value_diagnostic': paired.exact_clean_p(won, lost),
        'pieces_delta_mean': statistics.mean(deltas), 'pieces_delta_median': statistics.median(deltas),
        'regressed_seed_fraction': sum(d < 0 for d in deltas) / 80,
        'maximum_piece_loss': max(0, -min(deltas)), 'baseline_survivors_lost': survivor_losses,
        'horizon_rate_loss': (summaries['baseline']['horizon_games'] - summaries['candidate']['horizon_games']) / 80,
    }
    gates = {}

    def gate(name, observed, threshold, passed):
        gates[name] = {'observed': observed, 'threshold': threshold, 'pass': bool(passed)}

    clean_count = summaries['candidate']['clean_by_limit']
    gate('candidate_has_clean_by_500', clean_count, '> 0', clean_count > 0)
    gate('positive_paired_clean_gain', won - lost, '> 0', won > lost)
    adoption = protocol['adoption']
    for name, field in (('minimum_mean_piece_delta', 'pieces_delta_mean'),
                        ('minimum_median_piece_delta', 'pieces_delta_median')):
        gate(name, combined[field], adoption[name], combined[field] >= adoption[name])
    for name, field in (('maximum_regressed_seed_fraction', 'regressed_seed_fraction'),
                        ('maximum_piece_loss', 'maximum_piece_loss'),
                        ('maximum_horizon_rate_loss', 'horizon_rate_loss')):
        gate(name, combined[field], adoption[name], combined[field] <= adoption[name])
    gate('maximum_baseline_survivors_lost', len(survivor_losses), adoption['maximum_baseline_survivors_lost'],
         len(survivor_losses) <= adoption['maximum_baseline_survivors_lost'])
    for arm, summary in summaries.items():
        for field in ('decision_p95_ms', 'decision_max_ms', 'primary_failures', 'fallback_decisions', 'illegal_plans'):
            value, threshold = summary[field], adoption.get(field, 0)
            gate(arm + '.' + field, value, threshold, value is not None and value <= threshold)
    passed = all(g['pass'] for g in gates.values())
    return {
        'schema': 'goal-window-prototype-v1', 'protocol_sha256': development['protocol_sha256'],
        'populations': list(POPULATIONS), 'games_per_arm': 80, 'seed_start': 20000, 'seed_end': 20079,
        'evidence_kind': development['evidence_kind'], 'session_reference': reference,
        'environment': development['environment'], 'arm_metadata': reports['development']['arm_metadata'],
        'group_diagnostics': reports, 'arms': summaries, 'paired': combined, 'pairs': pairs, 'gates': gates,
        'prototype_gates_pass': passed,
        # Even a measured declaration is not binary/raw attestation or permission to consume fresh seeds.
        'provenance_verified': False, 'eligible_for_runtime_validation': False, 'runtime_permission': False,
        'conclusion': ('synthetic-diagnostic-only' if development['evidence_kind'] == 'synthetic' else
                       'prototype-gates-pass-evidence-review-required' if passed else 'prototype-gates-failed-stop'),
    }


def parse_envelope(raw):
    def invalid_constant(value):
        raise ValueError('non-finite JSON number: ' + value)

    return json.loads(raw.decode('utf-8'), object_pairs_hook=paired.unique_object,
                      parse_constant=invalid_constant)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('development', type=Path, nargs='?')
    parser.add_argument('calibration', type=Path, nargs='?')
    parser.add_argument('--bundle', type=Path, help='validate and analyze a complete derived bundle')
    parser.add_argument('--protocol', type=Path)
    parser.add_argument('--output', type=Path, required=True, help='new report file; existing results are never overwritten')
    args = parser.parse_args()
    if args.bundle is not None:
        if any(value is not None for value in (args.development, args.calibration, args.protocol)):
            parser.error('--bundle cannot be combined with envelope paths or --protocol')
    elif args.development is None or args.calibration is None:
        parser.error('provide both development and calibration, or --bundle')
    try:
        if args.bundle is not None:
            # Consume the reader's verified snapshots; reopening files would break
            # the link between bundle validation, analysis and reported hashes.
            files = read_bundle(args.bundle)['files']
            raw = [files[name + '.json'] for name in POPULATIONS]
            protocol_bytes = files['protocol.json']
        else:
            raw = [path.read_bytes() for path in (args.development, args.calibration)]
            protocol_bytes = (args.protocol or paired.PROTOCOL).read_bytes()
        report = analyze(*(parse_envelope(content) for content in raw), protocol_bytes)
        report['input_files_sha256'] = {name: hashlib.sha256(content).hexdigest()
                                       for name, content in zip(POPULATIONS, raw)}
        serialized = json.dumps(report, indent=2, ensure_ascii=False, allow_nan=False) + '\n'
        args.output.parent.mkdir(parents=True, exist_ok=True)
        with args.output.open('x', encoding='utf-8') as stream:
            stream.write(serialized)
    except (OSError, ValueError, KeyError, TypeError, OverflowError, RecursionError) as error:
        parser.exit(2, f'Invalid prototype evidence/output: {error}\n')
    print(json.dumps({'prototype_gates_pass': report['prototype_gates_pass'],
                      'conclusion': report['conclusion'], 'report': str(args.output)}))
    return 0 if report['prototype_gates_pass'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
