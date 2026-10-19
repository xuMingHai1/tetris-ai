"""Clean-build and verify-only GoalWindow preflight. This CLI never starts games."""
import argparse
from contextlib import contextmanager
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import platform
import signal
import subprocess
import sys
import time

import prepare_goal_window_build as build

APPLICATION = 'xyz.xuminghai.tetris.ai.benchmark.GoalWindowPairedApplication'
BUILD_TIMEOUT_SECONDS = 300
PREFLIGHT_TIMEOUT_SECONDS = 120
MEASUREMENT_TIMEOUT_SECONDS = build.EXPECTED_CONFIG['measurement_timeout_seconds']


def utc_now():
    return datetime.now(timezone.utc).isoformat()


def environment():
    """Timing context, not an environment-variable dump (which could expose credentials)."""
    facts = {'os': platform.platform(), 'kernel': platform.release(), 'arch': platform.machine(),
             'cpu_count': os.cpu_count(), 'python': sys.version, 'python_executable': sys.executable,
             'locale': {k: os.environ.get(k) for k in ('LANG', 'LC_ALL', 'TZ')}}
    if hasattr(os, 'sched_getaffinity'):
        facts['cpu_affinity'] = sorted(os.sched_getaffinity(0))
    if hasattr(os, 'getloadavg'):
        facts['load_average'] = os.getloadavg()
    for name, path in {'cpuinfo': '/proc/cpuinfo', 'memory': '/proc/meminfo',
                       'cgroup_cpu': '/sys/fs/cgroup/cpu.max',
                       'cgroup_memory': '/sys/fs/cgroup/memory.max'}.items():
        file = Path(path)
        if file.is_file():
            text = file.read_text()
            # CPU description/features suffice; avoid dumping serial/host identity fields.
            if name == 'cpuinfo':
                text = '\n'.join(line for line in text.splitlines()
                                 if line.split(':')[0].strip() in ('model name', 'flags', 'Features'))
                text = '\n'.join(dict.fromkeys(text.splitlines()))
            facts[name] = text
    return facts


@contextmanager
def interruption_handler():
    cancellation = {'launching': False, 'pending': None}

    def interrupted(number, frame):
        if cancellation['launching']:
            # Own the returned Popen handle before acting on cancellation. Raising from
            # inside Popen construction could otherwise orphan an already-started child.
            cancellation['pending'] = number
            return
        # Cancellation may be delivered more than once. Once stopping, keep later TERM/INT
        # from interrupting cleanup before the unconditional group KILL has run.
        for sig in (signal.SIGTERM, signal.SIGINT):
            signal.signal(sig, signal.SIG_IGN)
        raise InterruptedError('received signal ' + str(number))

    previous = {sig: signal.signal(sig, interrupted) for sig in (signal.SIGTERM, signal.SIGINT)}
    try:
        yield cancellation
    finally:
        for sig, handler in previous.items():
            signal.signal(sig, handler)


def stop_group(process):
    """The verifier can spawn Python/git; stop the whole JVM group, even if its leader exited."""
    previous = {}
    try:
        for sig in (signal.SIGTERM, signal.SIGINT):
            previous[sig] = signal.signal(sig, signal.SIG_IGN)
        try:
            os.killpg(process.pid, signal.SIGTERM)
        except ProcessLookupError:
            pass
        try:
            process.wait(timeout=2)
        except subprocess.TimeoutExpired:
            pass
    finally:
        # Even an interruption while entering cleanup must reach KILL. A descendant may
        # ignore TERM after the leader exited; waiting only for the leader is insufficient.
        try:
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            process.wait(timeout=2)
        finally:
            for sig, handler in previous.items():
                signal.signal(sig, handler)


def run_process(command, root, output, timeout_seconds, *, log_prefix=''):
    """Lifecycle seam for real process tests. Public launch always supplies the fixed JVM command."""
    build.require(os.name == 'posix', 'GoalWindow supervisor requires a POSIX process group')
    result = {'command': command, 'timeout_seconds': timeout_seconds, 'started_utc': utc_now()}
    started = time.monotonic_ns()
    # Direct file redirection cannot deadlock on full stdout/stderr pipes and retains failure prefixes.
    with (output / (log_prefix + 'stdout.log')).open('xb') as stdout, \
            (output / (log_prefix + 'stderr.log')).open('xb') as stderr:
        with interruption_handler() as cancellation:
            process = None
            try:
                cancellation['launching'] = True
                process = subprocess.Popen(command, cwd=root, stdout=stdout, stderr=stderr, start_new_session=True)
                result['pid'] = process.pid
                cancellation['launching'] = False
                if cancellation['pending'] is not None:
                    raise InterruptedError('received signal ' + str(cancellation['pending']) + ' during launch')
                process.wait(timeout=timeout_seconds)
                result['state'] = 'EXITED'
            except subprocess.TimeoutExpired:
                result['state'] = 'TIMEOUT'
            except (KeyboardInterrupt, InterruptedError) as error:
                result.update(state='INTERRUPTED', interruption=str(error))
            finally:
                cancellation['launching'] = False
                if process is not None:
                    stop_group(process)
            build.require(process is not None, 'process launch was interrupted without a child')
            result.update(returncode=process.returncode, ended_utc=utc_now(),
                          elapsed_nanos=time.monotonic_ns() - started)
    return result


def launch_build(root, output):
    # prepare and Maven inherit one supervised group. Stream Maven output independently of
    # prepare's final JSON, so cancellation preserves the console prefix before prepare returns.
    command = [sys.executable, str(root / 'scripts/prepare_goal_window_build.py'), '--root', str(root),
               '--build-log', str(output / 'build-console.log')]
    return run_process(command, root, output, BUILD_TIMEOUT_SECONDS, log_prefix='prepare-')


def launch_prepared(manifest_path, manifest_sha256, output, *, verify_only):
    """One direct JVM, no shell/retries/seed restarts. Future measurement driver must validate all data.

    EXITED is only a process result, never a population-complete or acceptance verdict.
    Measurement keeps the frozen 45-minute limit including warmup; this CLI uses verify-only.
    """
    manifest = build.verify_build(manifest_path, manifest_sha256)
    root = Path(manifest['root'])
    command = [manifest['jdk']['executable'], '-cp', os.pathsep.join(manifest['classpath']), APPLICATION,
               str(manifest_path.resolve()), manifest_sha256, str(output / 'raw.jsonl')]
    if verify_only:
        command.append('--verify-only')
    return run_process(command, root, output,
                       PREFLIGHT_TIMEOUT_SECONDS if verify_only else MEASUREMENT_TIMEOUT_SECONDS)


def verify_preflight(path, manifest, manifest_sha256):
    data = path.read_bytes()
    build.require(data.endswith(b'\n'), 'truncated preflight output')
    events = [json.loads(line, object_pairs_hook=build.unique_object) for line in data.decode('utf-8').splitlines()]
    build.require(len(events) == 2, 'preflight must contain exactly two events and no game data')
    for index, kind in enumerate(('provenance_verified', 'verification_complete')):
        event = events[index]
        build.require(event['schema'] == 'goal-window-raw-v1' and type(event['sequence']) is int
                      and event['sequence'] == index + 1 and event['type'] == kind, 'invalid preflight event/order')
    provenance = events[0]['data']
    build.require(provenance['manifest_sha256'] == manifest_sha256 and provenance['manifest'] == manifest,
                  'preflight provenance differs from this clean build')
    loaded = provenance['loaded_classes']
    build.require(isinstance(loaded, dict) and loaded, 'missing actual loaded class evidence')
    for name, value in loaded.items():
        origin = Path(value['origin'])
        build.require(origin in (Path(manifest['root']) / 'target/classes',
                                 Path(manifest['root']) / 'target/test-classes'), 'wrong loaded class origin')
        relative = (origin / (name.replace('.', '/') + '.class')).relative_to(manifest['root']).as_posix()
        build.require(manifest['class_files'].get(relative) == value['sha256'], 'wrong loaded class hash')
    build.require(events[1]['data']['population_started'] is False, 'preflight started population')


def save_build_evidence(root, output):
    for name in ('build-manifest.json', 'build.log', 'dependency-tree.txt', 'classpath.txt'):
        path = root / build.BUILD / name
        if path.is_file() and not path.is_symlink():
            (output / name).write_bytes(path.read_bytes())


def preflight(root, output):
    root = root.resolve(strict=True)
    output = output.absolute()
    # clean deletes target. Keep all supervisor evidence outside the checkout, including build failures.
    build.require(not output.resolve().is_relative_to(root), 'output must be outside the checkout')
    output.mkdir()  # Existing directories are never reused or overwritten.
    report = {'schema': 'goal-window-process-v1', 'mode': 'VERIFY_ONLY', 'status': 'INCOMPLETE',
              'population_started': None, 'runtime_permission': False, 'started_utc': utc_now(),
              'environment_before': environment()}

    def write_report():
        (output / 'report.json').write_text(json.dumps(report, indent=2, sort_keys=True, allow_nan=False) + '\n',
                                           encoding='utf-8')

    write_report()
    try:
        with interruption_handler():
            report['build_process'] = launch_build(root, output)
            build.require(report['build_process']['state'] == 'EXITED' and report['build_process']['returncode'] == 0,
                          'clean build did not exit successfully; see prepare-stderr.log/build-console.log')
            prepared = build.read_json(output / 'prepare-stdout.log')
            report['build'] = prepared
            path = Path(prepared['manifest'])
            manifest = build.verify_build(path, prepared['manifest_sha256'])
            save_build_evidence(root, output)
            report['process'] = launch_prepared(path, prepared['manifest_sha256'], output, verify_only=True)
            build.require(report['process']['state'] == 'EXITED' and report['process']['returncode'] == 0,
                          'preflight JVM did not exit successfully')
            verify_preflight(output / 'raw.jsonl', manifest, prepared['manifest_sha256'])
            build.verify_build(path, prepared['manifest_sha256'])  # Reject changes while the JVM ran.
            report['status'] = 'VERIFIED'
            report['population_started'] = False
    except (Exception, KeyboardInterrupt) as error:
        # Cancellation can arrive even as the verified block exits. No exception path
        # may retain its just-assigned success status and make the CLI return zero.
        report.update(status='INCOMPLETE', population_started=None,
                      failure={'type': type(error).__name__, 'message': str(error)})
    finally:
        if 'build' in report:
            save_build_evidence(root, output)
        report.update(ended_utc=utc_now(), environment_after=environment())
        report['artifacts'] = {p.name: {'sha256': build.digest(p), 'bytes': p.stat().st_size}
                               for p in sorted(output.iterdir()) if p.is_file() and p.name != 'report.json'}
        write_report()
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path('.'))
    parser.add_argument('--output', type=Path, required=True, help='new directory outside the checkout')
    args = parser.parse_args()
    report = preflight(args.root, args.output)
    print(json.dumps({'status': report['status'], 'population_started': report['population_started'],
                      'report': str(args.output.absolute() / 'report.json')}))
    return 0 if report['status'] == 'VERIFIED' else 1


if __name__ == '__main__':
    sys.exit(main())
