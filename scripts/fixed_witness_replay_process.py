"""Linux process component for fixed-action replay; no diagnostic launch CLI.

This module never selects a Java application or reads the historical fixture.
The caller must still authenticate commands, register the unique session and
validate the actual journal. A zero exit code is only a process observation.
"""
from contextlib import contextmanager, nullcontext
from dataclasses import dataclass
import math
import os
from pathlib import Path
import selectors
import signal
import subprocess
import sys
import time
from queue import SimpleQueue
from threading import Thread, Event
from fixed_witness_replay_identity import evidence_control


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
    state = {'signal': None}

    def cancel(number, frame):
        # Never raise inside Popen construction or cleanup. Repeated signals
        # only record cancellation; the bounded poll loop acts on the flag.
        state['signal'] = number

    previous = {number: signal.signal(number, cancel) for number in (signal.SIGINT, signal.SIGTERM)}
    try:
        yield state
    finally:
        for number, handler in previous.items():
            signal.signal(number, handler)


def _work(function, *args, deadline):
    """Read-only cooperative work; only the supervising thread owns child stdin.

    A slow worker cannot postpone pipe draining or child termination. Daemon
    workers may finish reading after timeout. Inventory Git is bounded by the
    same absolute deadline/cancellation and killed in its own process group.
    """
    result = SimpleQueue()
    cancelled = Event()

    def run():
        try:
            with evidence_control(deadline, cancelled):
                value = function(*args)
            result.put((True, value))
        except BaseException as error:
            result.put((False, error))

    worker = Thread(target=run, daemon=True, name='fixed-witness-evidence')
    worker.start()
    return result, cancelled, worker


def _stop_work(pending):
    if pending is not None:
        pending[1].set()
        pending[2].join(timeout=1)


def run_process(command, cwd, output, name, budget, *, seconds, total_deadline, environment=None,
                handshake=None, cancellation=None):
    """Run once, retain bounded binary prefixes and kill the entire owned group.

    Linux only; start_new_session creates a group even when the leader exits
    before a pipe-holding descendant. KILL also runs after normal leader exit.
    This supervises cooperative children, not adversarial setsid escapees or
    network access. Caller must provide the accepted offline JVM/environment.
    environment=None preserves the existing helper's inherited-environment mode;
    authenticated preparation and later diagnostics must pass an explicit dict.
    Optional handshake is a cooperative read-only ready/authenticate/finalize
    adapter. Workers never receive stdin; ACK is one bounded nonblocking write
    on the supervising thread. Post-exit evidence work shares the same deadline.
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
    if environment is not None and (not isinstance(environment, dict) or any(
            not isinstance(k, str) or not k or '=' in k or '\0' in k
            or not isinstance(v, str) or '\0' in v for k, v in environment.items())):
        raise ValueError('explicit string environment required')
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
    pending = None
    phase = 'WAITING'
    if handshake is not None:
        if environment is None or seconds > JVM_SECONDS:
            raise ValueError('handshake requires explicit environment and <=120s stage')
        result['ack_sent'] = False
    with (output / (name + '-stdout.log')).open('xb') as stdout, \
            (output / (name + '-stderr.log')).open('xb') as stderr, \
            selectors.DefaultSelector() as selector, \
            (nullcontext(cancellation) if cancellation is not None else _cancellation()) as cancellation:
        try:
            if cancellation['signal'] is not None:
                raise InterruptedError('received signal before launch')
            process = subprocess.Popen(command, cwd=cwd,
                                       stdin=subprocess.PIPE if handshake is not None else subprocess.DEVNULL,
                                       stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                       start_new_session=True, env=environment)
            result['pid'] = process.pid
            if handshake is not None:
                os.set_blocking(process.stdin.fileno(), False)
            if cancellation['signal'] is not None:
                raise InterruptedError('received signal during launch')
            for pipe, sink in ((process.stdout, stdout), (process.stderr, stderr)):
                os.set_blocking(pipe.fileno(), False)
                selector.register(pipe, selectors.EVENT_READ, sink)
            while True:
                if cancellation['signal'] is not None:
                    result.update(state='INTERRUPTED', error='received signal ' + str(cancellation['signal']))
                    break
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
                if handshake is not None:
                    if phase == 'WAITING' and process.poll() is None and handshake.ready():
                        pending = _work(handshake.authenticate, process.pid, deadline=deadline)
                        phase = 'AUTHENTICATING'
                    if pending is not None and not pending[0].empty():
                        ok, value = pending[0].get()
                        _stop_work(pending)
                        pending = None
                        if not ok:
                            result.update(state='EVIDENCE_ERROR', error=str(value))
                            break
                        # Authentication can outlast the budget or the child.
                        # Never let a late worker release its own ACK.
                        if time.monotonic() >= deadline or cancellation['signal'] is not None:
                            continue
                        if phase == 'AUTHENTICATING':
                            if process.poll() is not None:
                                result['state'] = 'EXIT_BEFORE_ACK'
                                break
                            ack = value['ack']
                            if not isinstance(ack, bytes) or not 0 < len(ack) <= 512:
                                raise ValueError('bounded ACK bytes required')
                            if os.write(process.stdin.fileno(), ack) != len(ack):
                                raise OSError('partial ACK write')
                            process.stdin.close()  # JVM requires EOF, not just LF.
                            result.update(ack_sent=True, admission=value['evidence'])
                            phase = 'ACKED'
                        else:
                            result.update(state='VERIFIED_NO_REPLAY', verification=value)
                            break
                if process.poll() is not None and not selector.get_map():
                    if handshake is not None:
                        if phase == 'ACKED' and process.returncode == 0:
                            pending = _work(handshake.finalize, result['admission'], deadline=deadline)
                            phase = 'FINALIZING'
                        if phase == 'FINALIZING':
                            continue
                        result['state'] = 'EXIT_BEFORE_ACK' if not result['ack_sent'] else 'CHILD_FAILED'
                        break
                    result['state'] = 'EXITED'
                    break
        except (KeyboardInterrupt, InterruptedError) as error:
            result.update(state='INTERRUPTED', error=str(error))
        except OSError as error:
            result.update(state='PROCESS_ERROR', error=str(error))
        except Exception as error:
            result.update(state='EVIDENCE_ERROR', error=str(error))
        finally:
            # The handler only records signals, including during group cleanup.
            if process is not None:
                try:
                    os.killpg(process.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
            # Kill the JVM immediately on a limit; waiting for evidence workers
            # first would extend its execution beyond the stage deadline.
            _stop_work(pending)
            if process is not None:
                try:
                    process.wait(timeout=2)
                except subprocess.TimeoutExpired:
                    result['state'] = 'CLEANUP_FAILED'
                for pipe in (process.stdin, process.stdout, process.stderr):
                    if pipe is not None:
                        pipe.close()
                result['returncode'] = process.returncode
            for stream in (stdout, stderr):
                stream.flush()
                os.fsync(stream.fileno())
            result.update(elapsed_seconds=time.monotonic() - started,
                          stdout_stderr_bytes=budget.written - before_written,
                          observed_bytes_lower_bound=budget.observed - before_observed)
    return result
