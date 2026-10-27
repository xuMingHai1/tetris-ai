"""Bounded terminal-certificate supervision. Default is verify-only; collect is explicit."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import sys
import time

import prepare_terminal_certificate_build as build
# Reuse only the tested POSIX lifecycle, not GoalWindow launch/config/provenance.
from supervise_goal_window import run_process, utc_now
from prepare_goal_window_build import unique_object

JOB_SECONDS = 600
VERIFY_SECONDS = 120
FINALIZATION_SECONDS = 10
APPLICATION = 'xyz.xuminghai.tetris.ai.TerminalCertificateApplication'
MAX_LINE_BYTES = 2 * 1024 * 1024


def cli_evidence(output, manifest, stdout):
    """Bind the authenticated fixed CLI's independent replay to durable files.

    The Java reader owns semantic validation; this parent does not implement game rules
    or reinterpret rank/guard facts. Its evidence hashes cover every raw byte.
    """
    raw = output / 'raw'
    build.unlinked(raw)
    build.require({p.name for p in raw.iterdir()} ==
                  {'observation.json', 'started.json', 'session.jsonl', 'completion.json'},
                  'missing/extra CLI files (including error prefix)')
    for path in raw.iterdir():
        build.unlinked(path)
        build.require(path.is_file(), 'non-file CLI evidence')
    for name in ('observation.json', 'started.json', 'completion.json'):
        build.require((raw / name).stat().st_size <= MAX_LINE_BYTES, 'CLI JSON too large')
    build.check_observation(manifest, build.read_json(raw / 'observation.json'))
    start = build.read_json(raw / 'started.json')
    build.require(start == {'schema': 'terminal-certificate-cli-v1', 'status': 'INCOMPLETE',
                           'diagnostic_started': True, 'provenance_authenticated': False,
                           'eligible_for_runtime_validation': False}, 'wrong CLI start')
    complete = build.read_json(raw / 'completion.json')
    build.require(complete == stdout and set(complete) == {
        'schema', 'status', 'search_status', 'journal_state', 'enumerations', 'solver_replays',
        'checker_replays', 'diagnostic_started', 'provenance_authenticated',
        'eligible_for_runtime_validation'}, 'CLI stdout/completion mismatch')
    build.require(complete['schema'] == 'terminal-certificate-cli-v1' and
                  complete['status'] == 'SESSION_COMPLETE' and complete['journal_state'] == 'COMPLETE' and
                  complete['search_status'] in ('Q_FOUND', 'BOUNDED_MISS') and
                  complete['diagnostic_started'] is True and complete['provenance_authenticated'] is False and
                  complete['eligible_for_runtime_validation'] is False, 'wrong CLI completion')
    for key, maximum in (('enumerations', 2000), ('solver_replays', 2000), ('checker_replays', 128 * 16)):
        build.require(type(complete[key]) is int and 0 <= complete[key] <= maximum, 'invalid CLI count: ' + key)
    hasher = hashlib.sha256()
    count = 0
    previous = None
    with (raw / 'session.jsonl').open('rb') as stream:
        # At most start + 128 attempts + finish, each already bounded by the Java reader.
        for index in range(131):
            line = stream.readline(MAX_LINE_BYTES + 2)
            if not line:
                break
            build.require(index < 130 and len(line) <= MAX_LINE_BYTES + 1 and line.endswith(b'\n'),
                          'oversized/truncated/excess journal record')
            hasher.update(line)
            event = json.loads(line.decode('utf-8'), object_pairs_hook=unique_object)
            build.require(event.get('schema') == 'terminal-certificate-session-v1' and
                          event.get('provenance_authenticated') is False and
                          event.get('eligible_for_runtime_validation') is False, 'wrong journal envelope')
            if index == 0:
                build.require(event['event'] == 'start' and event['data']['limits'] == {
                    'nodes': 200000, 'decompositions': 128, 'enumerations': 2000, 'steps': 16},
                    'wrong registered journal start/budget')
            else:
                build.require(previous['event'] != 'finish' and
                              event['event'] in ('attempt', 'finish'), 'wrong journal event order')
            previous = event
            count += 1
    build.require(count >= 2 and previous['event'] == 'finish', 'incomplete journal')
    finish = previous['data']
    build.require(finish['state'] == 'SESSION_COMPLETE' and
                  finish['search_status'] == complete['search_status'] and
                  all(finish[key] == complete[key] for key in ('enumerations', 'solver_replays', 'checker_replays')) and
                  type(finish['geometry']['decompositions']) is int and
                  finish['geometry']['decompositions'] == count - 2, 'journal/completion mismatch')
    raw_hash = hasher.hexdigest()
    return {'completion': complete, 'session_sha256': raw_hash,
            'files': {p.name: raw_hash if p.name == 'session.jsonl' else build.digest(p) for p in raw.iterdir()}}


def supervise(root, output, *, collect=False):
    build.require(sys.platform == 'linux', 'Linux execution host required')
    root = root.resolve(strict=True)
    output = output.absolute()
    build.unlinked(output)
    build.require(not output.is_relative_to(root), 'output must be outside checkout')
    output.mkdir()  # Never overwrite an earlier attempt, including failed attempts.
    started = time.monotonic()
    deadline = started + JOB_SECONDS
    report = {'schema': 'terminal-certificate-process-v1', 'mode': 'COLLECT' if collect else 'VERIFY_ONLY',
              'status': 'INCOMPLETE', 'diagnostic_started': False,
              'provenance_authenticated': False, 'eligible_for_runtime_validation': False,
              'job_seconds': JOB_SECONDS, 'verify_seconds': VERIFY_SECONDS,
              'peak_rss': None, 'peak_rss_reason': 'unavailable: process lifecycle does not sample RSS; Xmx is not total RSS',
              'started_utc': utc_now(), 'processes': []}

    def save():
        (output / 'report.json').write_text(json.dumps(report, indent=2, sort_keys=True) + '\n',
                                          encoding='utf-8')

    def stage(args, name, cap):
        # Leave room for group termination/reaping and evidence finalization. This
        # reserve is never transferred back to a slow child or used for retries.
        remaining = deadline - time.monotonic() - FINALIZATION_SECONDS
        build.require(remaining > 0, 'total job deadline exhausted before ' + name)
        result = run_process(args, root, output, min(cap, remaining), log_prefix=name + '-')
        report['processes'].append(result)
        save()
        build.require(result['state'] == 'EXITED' and result['returncode'] == 0,
                      name + ' process failed: ' + result['state'])
        build.require(time.monotonic() < deadline, 'total job deadline exhausted after ' + name)
        path = output / (name + '-stdout.log')
        build.require(path.stat().st_size <= MAX_LINE_BYTES, 'worker JSON too large')
        return build.read_json(path)

    save()
    try:
        script = str(root / 'scripts/prepare_terminal_certificate_build.py')
        prepared = stage([sys.executable, script, '--root', str(root), '--output', str(output / 'build')],
                         'prepare', JOB_SECONDS)
        # The digest is retained by the parent outside the build directory. The verify
        # worker authenticates it before starting its fixed direct -Xmx512m JVM.
        report['build'] = prepared
        save()
        build.require(prepared['manifest'] == str(output / 'build/build-manifest.json'),
                      'unexpected manifest location')
        if collect:
            def authenticate(name):
                result = stage([sys.executable, script, '--verify', prepared['manifest'],
                                '--manifest-sha256', prepared['manifest_sha256'], '--authenticate-only'],
                               name, VERIFY_SECONDS)
                build.require(result == {'status': 'BUILD_AUTHENTICATED', 'source_sha': prepared['source_sha'],
                                         'diagnostic_started': False, 'eligible_for_runtime_validation': False},
                              'wrong build authentication result')
                report[name] = result
                save()

            authenticate('before')
            path = Path(prepared['manifest'])
            build.require(build.digest(path) == prepared['manifest_sha256'], 'manifest changed before launch')
            manifest = build.read_json(path)
            build.require(manifest['root'] == str(root) and manifest['jvm_args'] == build.FLAGS and
                          manifest['source_sha'] == prepared['source_sha'], 'wrong collect manifest')
            command = [manifest['jdk']['executable'], *build.FLAGS, '-cp', os.pathsep.join(manifest['classpath']),
                       APPLICATION, '--collect', str(root), str(output / 'raw')]
            # Even failure/timeout must attempt the original guard and build-byte check.
            # This cannot extend the total deadline or retry the diagnostic JVM.
            report['diagnostic_started'] = None
            save()
            try:
                completed = stage(command, 'collect', VERIFY_SECONDS)
            finally:
                launched = [p for p in report['processes'] if p.get('command') == command]
                report['diagnostic_started'] = 'pid' in launched[0] if launched else None
                authenticate('after')
            report['evidence'] = cli_evidence(output, manifest, completed)
            build.require(build.digest(path) == prepared['manifest_sha256'], 'manifest changed after authentication')
            build.require(time.monotonic() < deadline, 'total job deadline exhausted during finalization')
            report.update(status=completed['search_status'], provenance_authenticated=True)
            return report
        verified = stage([sys.executable, script, '--verify', prepared['manifest'],
                          '--manifest-sha256', prepared['manifest_sha256']], 'verify', VERIFY_SECONDS)
        build.require(verified.get('status') == 'VERIFIED' and
                      verified.get('source_sha') == prepared['source_sha'] and
                      verified.get('provenance_authenticated') is True and
                      verified.get('diagnostic_started') is False and
                      verified.get('eligible_for_runtime_validation') is False,
                      'wrong verify-only worker result')
        report.update(status='VERIFIED', provenance_authenticated=True)
    except (Exception, KeyboardInterrupt) as error:
        # TIMEOUT/INTERRUPTED and nonzero exit remain incomplete, never a bounded miss.
        report.update(status='INCOMPLETE', provenance_authenticated=False,
                      failure={'type': type(error).__name__, 'message': str(error)})
    finally:
        report.update(ended_utc=utc_now(), elapsed_seconds=time.monotonic() - started)
        save()
    return report


def preflight(root, output):
    return supervise(root, output)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path('.'))
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--collect', action='store_true', help='run the once-registered fixed diagnostic explicitly')
    args = parser.parse_args()
    report = supervise(args.root, args.output, collect=args.collect)
    print(json.dumps({'status': report['status'], 'diagnostic_started': report['diagnostic_started'],
                      'report': str(args.output.absolute() / 'report.json')}))
    return 0 if report['status'] in ('VERIFIED', 'Q_FOUND', 'BOUNDED_MISS') else 1


if __name__ == '__main__':
    sys.exit(main())
