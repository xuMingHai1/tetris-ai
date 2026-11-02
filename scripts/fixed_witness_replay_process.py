"""Linux process component for fixed-action replay; no diagnostic launch CLI.

This module never selects a Java application or reads the historical fixture.
The caller must still authenticate commands, register the unique session and
validate the actual journal. A zero exit code is only a process observation.
"""
from contextlib import contextmanager
from dataclasses import dataclass
import math
import os
from pathlib import Path
import selectors
import signal
import subprocess
import sys
import time


OUTPUT_BYTES = 10 * 1024 * 1024
BUILD_SECONDS = 600
JVM_SECONDS = 120
PARENT_SECONDS = 180
TOTAL_SECONDS = 900


@dataclass
class OutputBudget:
    """One cumulative stdout/stderr allowance shared by every command stage.

    Artifact bytes (journal/manifest/report) must be charged separately by the
    final supervisor; this component alone does not enforce total artifact size.
    """
    limit: int = OUTPUT_BYTES
    written: int = 0
    observed: int = 0

    def __post_init__(self):
        if type(self.limit) is not int or not 0 < self.limit <= OUTPUT_BYTES:
            raise ValueError('output limit must be within frozen 10MiB ceiling')
        if self.written != 0 or self.observed != 0:
            raise ValueError('a new budget must start empty')

    def consume(self, data, stream):
        self.observed += len(data)
        retained = min(len(data), self.limit - self.written)
        stream.write(data[:retained])
        stream.flush()
        self.written += retained
        return retained < len(data)


def _seconds(value):
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) or value <= 0:
        raise ValueError('positive finite wall-clock budget required')
    return value


@contextmanager
def _cancellation():
    state = {'launching': False, 'signal': None}

    def cancel(number, frame):
        state['signal'] = number
        if not state['launching']:
            raise InterruptedError('received signal ' + str(number))

    previous = {number: signal.signal(number, cancel) for number in (signal.SIGINT, signal.SIGTERM)}
    try:
        yield state
    finally:
        for number, handler in previous.items():
            signal.signal(number, handler)


def run_process(command, cwd, output, name, budget, *, seconds, total_deadline):
    """Run once, retain bounded binary prefixes and kill the entire owned group.

    Linux only; start_new_session creates a group even when the leader exits
    before a pipe-holding descendant. KILL also runs after normal leader exit.
    This supervises cooperative children, not adversarial setsid escapees or
    network access. Caller must provide the accepted offline JVM/environment.
    """
    if sys.platform != 'linux':
        raise RuntimeError('Linux process supervision required')
    seconds = _seconds(seconds)
    if seconds > BUILD_SECONDS:
        raise ValueError('stage exceeds frozen build ceiling')
    if not isinstance(budget, OutputBudget):
        raise ValueError('shared output budget required')
    if not isinstance(command, (list, tuple)) or not command or any(not isinstance(x, str) or not x for x in command):
        raise ValueError('explicit nonempty argv required')
    if not name or any(c not in 'abcdefghijklmnopqrstuvwxyz0123456789-' for c in name):
        raise ValueError('invalid stage name')
    started = time.monotonic()
    if not isinstance(total_deadline, (int, float)) or isinstance(total_deadline, bool) or not math.isfinite(total_deadline):
        raise ValueError('finite absolute total deadline required')
    deadline = min(started + seconds, total_deadline)
    if deadline <= started or budget.written >= budget.limit:
        raise ValueError('budget exhausted before launch')
    output = Path(output)
    # Exclusive files preserve prior attempts; callers own directory creation.
    result = {'state': 'INCOMPLETE', 'returncode': None, 'command': list(command),
              'pid': None, 'elapsed_seconds': None, 'stdout_stderr_bytes': 0,
              'observed_bytes_lower_bound': 0, 'peak_rss': None,
              'peak_rss_reason': 'not sampled; heap/metaspace are not an RSS limit'}
    before_written, before_observed = budget.written, budget.observed
    process = None
    with (output / (name + '-stdout.log')).open('xb') as stdout, \
            (output / (name + '-stderr.log')).open('xb') as stderr, \
            selectors.DefaultSelector() as selector, _cancellation() as cancellation:
        try:
            cancellation['launching'] = True
            try:
                process = subprocess.Popen(command, cwd=cwd, stdin=subprocess.DEVNULL,
                                           stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                           start_new_session=True)
            finally:
                cancellation['launching'] = False
            result['pid'] = process.pid
            if cancellation['signal'] is not None:
                raise InterruptedError('received signal during launch')
            for pipe, sink in ((process.stdout, stdout), (process.stderr, stderr)):
                os.set_blocking(pipe.fileno(), False)
                selector.register(pipe, selectors.EVENT_READ, sink)
            while True:
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    result['state'] = 'TIMEOUT'
                    break
                events = selector.select(min(0.02, remaining))
                exceeded = False
                for key, _ in events:
                    # Read at most one chunk beyond the cap; never retain it.
                    data = os.read(key.fileobj.fileno(), min(8192, budget.limit - budget.written + 1))
                    if not data:
                        selector.unregister(key.fileobj)
                    elif budget.consume(data, key.data):
                        exceeded = True
                        break
                if exceeded:
                    result['state'] = 'OUTPUT_LIMIT'
                    break
                if process.poll() is not None and not selector.get_map():
                    result['state'] = 'EXITED'
                    break
        except (KeyboardInterrupt, InterruptedError) as error:
            result.update(state='INTERRUPTED', error=str(error))
        except OSError as error:
            result.update(state='PROCESS_ERROR', error=str(error))
        finally:
            # Ignore repeated cancellation until group KILL and reaping finish.
            for number in (signal.SIGINT, signal.SIGTERM):
                signal.signal(number, signal.SIG_IGN)
            if process is not None:
                try:
                    os.killpg(process.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
                try:
                    process.wait(timeout=2)
                except subprocess.TimeoutExpired:
                    result['state'] = 'CLEANUP_FAILED'
                for pipe in (process.stdout, process.stderr):
                    pipe.close()
                result['returncode'] = process.returncode
            for stream in (stdout, stderr):
                stream.flush()
                os.fsync(stream.fileno())
            result.update(elapsed_seconds=time.monotonic() - started,
                          stdout_stderr_bytes=budget.written - before_written,
                          observed_bytes_lower_bound=budget.observed - before_observed)
    return result
