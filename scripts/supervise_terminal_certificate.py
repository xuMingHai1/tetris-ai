"""Bounded clean-build/verify-only supervision. No solver or collect entry point."""
import argparse
import json
from pathlib import Path
import sys
import time

import prepare_terminal_certificate_build as build
# Reuse only the tested POSIX lifecycle, not GoalWindow launch/config/provenance.
from supervise_goal_window import run_process, utc_now

JOB_SECONDS = 600
VERIFY_SECONDS = 120
FINALIZATION_SECONDS = 10


def preflight(root, output):
    build.require(sys.platform == 'linux', 'Linux execution host required')
    root = root.resolve(strict=True)
    output = output.absolute()
    build.unlinked(output)
    build.require(not output.is_relative_to(root), 'output must be outside checkout')
    output.mkdir()  # Never overwrite an earlier attempt, including failed attempts.
    started = time.monotonic()
    deadline = started + JOB_SECONDS
    report = {'schema': 'terminal-certificate-process-v1', 'mode': 'VERIFY_ONLY',
              'status': 'INCOMPLETE', 'diagnostic_started': False,
              'provenance_authenticated': False, 'eligible_for_runtime_validation': False,
              'job_seconds': JOB_SECONDS, 'verify_seconds': VERIFY_SECONDS,
              'peak_rss': None, 'peak_rss_reason': 'unavailable in this verify-only supervisor',
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
        return build.read_json(output / (name + '-stdout.log'))

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


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path('.'))
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    report = preflight(args.root, args.output)
    print(json.dumps({'status': report['status'], 'diagnostic_started': False,
                      'report': str(args.output.absolute() / 'report.json')}))
    return 0 if report['status'] == 'VERIFIED' else 1


if __name__ == '__main__':
    sys.exit(main())
