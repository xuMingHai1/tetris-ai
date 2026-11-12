"""Exclusive tracer -> coordinator artifact writer handoff, never a run permit.

The tracer retains stdout/stderr only. A permanent same-root reservation moves
artifact ownership to its exact direct child through the original stdin pipe.
The trusted child calls receive before preparation, then uses the existing
prepare and mutually exclusive verify/report/execution writers. This transport
does not authorize native probes or historical inputs.
"""
import json
import math
import os
from pathlib import Path
import stat
import time

from fixed_witness_replay_process import OutputBudget
from fixed_witness_replay_quota import ByteQuota, write_exclusive
from fixed_witness_replay_writers import COORDINATOR_BYTES


MAX_GRANT = 160
TERMINAL_BYTES = 4096


def _deadline(deadline, clock):
    if (type(deadline) not in (int, float) or not math.isfinite(deadline)
            or not clock() < deadline <= clock() + 900):
        raise ValueError('original active coordinator deadline required')


def _directory(directory):
    path = Path(directory)
    if not path.is_absolute() or path != path.resolve(strict=True) or not path.is_dir():
        raise ValueError('canonical original session required')
    return path


class CoordinatorArtifactGrant:
    """One pre-fork debit, one post-fork PID-bound atomic pipe write, no refund.

    No child ByteQuota is returned to the tracer. Failed construction, launch,
    write or close consumes the attempt. The child must independently enforce
    its allowance; this cooperative transport is not an OS filesystem sandbox.
    """

    def __init__(self, quota, directory, allowance, deadline):
        if type(quota) is not ByteQuota or quota.limit != COORDINATOR_BYTES:
            raise ValueError('original coordinator partition required')
        if type(allowance) is not int or not TERMINAL_BYTES < allowance < COORDINATOR_BYTES:
            raise ValueError('bounded artifact allowance with remaining pipe credit required')
        self.directory = _directory(directory)
        _deadline(deadline, time.monotonic)
        # Serialize the transfer claim with other users of the original quota.
        # Once claimed, even a failed reservation may not be retried.
        with quota._lock:
            if getattr(quota, '_artifact_claimed', False):
                raise ValueError('coordinator artifact transfer already attempted')
            quota._artifact_claimed = True
        quota.reserve(allowance)  # Deliberately discard the tracer-side child writer.
        self.allowance, self.deadline = allowance, deadline
        self.parent = os.getpid()
        self.launch = None
        self.sent = self.closed = False
        self.reader = self.writer = None
        try:
            self.reader, self.writer = os.pipe()
            for fd in (self.reader, self.writer):
                os.set_inheritable(fd, False)
            os.set_blocking(self.writer, False)
        except BaseException:
            self.close()
            raise

    def claim(self, launch):
        if (self.launch is not None or self.closed or launch.attempted
                or launch.stdio[0] != self.reader or launch.owner != self.parent
                or launch.deadline != self.deadline):
            raise ValueError('exclusive original coordinator launch required')
        self.launch = launch

    def send(self, launch):
        if self.sent or self.closed or self.launch is not launch:
            raise ValueError('coordinator artifact grant already consumed or unbound')
        self.sent = True
        launch._owner_and_deadline()
        pid = launch.cleanup_tid
        if (os.getpid() != self.parent or not launch.attempted or launch.stopped is not None
                or type(pid) is not int or not 0 < pid < 2**31 or pid == self.parent):
            raise ValueError('original successful coordinator birth required')
        remaining = int((self.deadline - launch.clock()) * 1_000_000_000)
        if not 0 < remaining <= 900_000_000_000:
            raise ValueError('original coordinator grant deadline exhausted')
        raw = ('FIXED_ARTIFACTS_V1 %d %d %d %d\n' % (
            self.parent, pid, self.allowance, remaining)).encode('ascii')
        if len(raw) > MAX_GRANT or os.write(self.writer, raw) != len(raw):
            raise OSError('incomplete coordinator artifact grant')
        # The supervisor's existing post-fork finally closes both parent ends.
        # Child stdin keeps its separate read descriptor and requires EOF.

    def close(self):
        if self.closed:
            return
        self.closed = True
        first = None
        for fd in (self.reader, self.writer):
            if fd is not None:
                try:
                    os.close(fd)
                except BaseException as error:
                    if first is None:
                        first = error
        if first is not None:
            raise first


class CoordinatorArtifacts:
    """One direct coordinator owns all prepare/JVM artifacts on one allowance.

    Receive before starting any worker/build process. The fixed on-disk claim
    prevents a second receiver in the session; PID/parent/EOF checks reject a
    replay in another child. Existing JVM ArtifactQuota enforces its own exact
    permanently reserved suballowance; parent reads never charge it again.
    """

    def __init__(self):
        raise ValueError('receive the original coordinator stdin grant')

    @classmethod
    def receive(cls, fd, directory, deadline, *, clock=time.monotonic):
        _deadline(deadline, clock)
        path = _directory(directory)
        if type(fd) is not int or fd < 0 or not stat.S_ISFIFO(os.fstat(fd).st_mode):
            raise ValueError('inherited coordinator grant pipe required')
        os.set_blocking(fd, False)
        raw = bytearray()
        while True:
            remaining = deadline - clock()
            if remaining <= 0:
                raise ValueError('coordinator grant EOF deadline exhausted')
            try:
                chunk = os.read(fd, MAX_GRANT + 1 - len(raw))
            except (BlockingIOError, InterruptedError):
                time.sleep(min(.01, remaining))
                continue
            if not chunk:
                break
            raw.extend(chunk)
            if len(raw) > MAX_GRANT:
                raise ValueError('oversized coordinator grant')
        fields = bytes(raw).split(b' ')
        if (len(fields) != 5 or fields[0] != b'FIXED_ARTIFACTS_V1'
                or not raw.endswith(b'\n')
                or any(not field.isdigit() or field.startswith(b'0') for field in fields[1:-1])
                or not fields[-1][:-1].isdigit() or fields[-1].startswith(b'0')):
            raise ValueError('canonical coordinator grant required')
        parent, pid, allowance, nanos = map(int, fields[1:])
        if (parent != os.getppid() or pid != os.getpid()
                or not TERMINAL_BYTES < allowance < COORDINATOR_BYTES
                or not 0 < nanos <= 900_000_000_000):
            raise ValueError('original PID-bound coordinator allowance required')
        result = object.__new__(cls)
        result.directory, result.clock = path, clock
        result.deadline = min(deadline, clock() + nanos / 1_000_000_000)
        result.pid, result.parent = pid, parent
        result.budget = OutputBudget(limit=allowance, quota=ByteQuota(allowance))
        result.terminal = result.budget.quota.reserve(TERMINAL_BYTES)
        write_exclusive(path / 'coordinator-artifact-claim.txt', bytes(raw), result.budget.quota)
        result.phase = 'RECEIVED'
        result.terminal_error = None
        return result

    def _enter(self, expected, following):
        if self.phase != expected:
            raise ValueError('coordinator artifact stage already attempted or unavailable')
        self.phase = following  # Failure permanently consumes this stage too.
        if os.getpid() != self.pid or os.getppid() != self.parent or self.clock() >= self.deadline:
            raise ValueError('original coordinator owner/deadline required')

    def prepare(self, root, java_home, output, *, build_storage):
        from fixed_witness_replay_prepare import prepare
        self._enter('RECEIVED', 'PREPARING')
        try:
            receipt = prepare(root, java_home, output, self.budget,
                              build_storage=build_storage, total_deadline=self.deadline)
            self.phase = 'PREPARED'
            return receipt
        except BaseException as error:
            self._finish('PREPARE_FAILED', error)
            raise

    def verify_only(self, root, java_home, receipt, output, logs, *, nonce, artifact_bytes):
        from fixed_witness_replay_pipe import run_verify_only
        self._enter('PREPARED', 'VERIFYING')
        try:
            self._request_binding('verify', None)
            result = run_verify_only(root, java_home, receipt, output, logs, self.budget,
                                     nonce=nonce, total_deadline=self.deadline,
                                     artifact_bytes=artifact_bytes)
            self._finish(result['state'])
            return result
        except BaseException as error:
            if self.phase == 'VERIFYING':
                self._finish('VERIFY_FAILED', error)
            raise

    def stage_request(self, directory, files, *, mode, declaration, expected_sha256):
        """Copy once on the received allowance, retaining independent input bytes.

        The caller supplies already retained bytes, never historical input selected
        here. Reserve the entire copy before creating its exclusive directory;
        failed writes/validation keep their debit and prohibit another attempt.
        Existing retainers own request semantics; no JSON is regenerated here.
        """
        from fixed_witness_report_pipe import LIMITS, retain_request
        from fixed_witness_replay_execution_evidence import INPUT_LIMITS, retain_execution_request
        self._enter('PREPARED', 'STAGING_REQUEST')
        try:
            if getattr(self, '_request_attempted', False):
                raise ValueError('coordinator request already attempted')
            self._request_attempted = True
            if mode not in ('report', 'execute'):
                raise ValueError('one report or execution request required')
            limits = LIMITS if mode == 'report' else INPUT_LIMITS
            if type(files) is not dict or set(files) != set(limits):
                raise ValueError('exact request file set required')
            payload = dict(files)
            if any(type(raw) is not bytes or len(raw) > limits[name]
                   for name, raw in payload.items()):
                raise ValueError('bounded immutable request bytes required')
            path = Path(directory)
            if (not path.is_absolute() or path.name in ('', '.', '..')
                    or path.parent != path.parent.resolve(strict=True)):
                raise ValueError('canonical new request directory required')
            quota = self.budget.quota.reserve(sum(map(len, payload.values())))
            path.mkdir()  # Exclusive: retain any partial prefix, never overwrite.
            for name, raw in payload.items():
                self._request_owner()
                write_exclusive(path / name, raw, quota)
            retain = retain_request if mode == 'report' else retain_execution_request
            request = retain(path, declaration, expected_sha256)
            self._request_owner()
            self._request_mode, self._request_receipt = mode, request
            self.phase = 'PREPARED'
            return request
        except BaseException as error:
            self._finish('REQUEST_FAILED', error)
            raise

    def _request_owner(self):
        if os.getpid() != self.pid or os.getppid() != self.parent or self.clock() >= self.deadline:
            raise ValueError('original coordinator owner/deadline required')

    def _request_binding(self, mode, request):
        if (getattr(self, '_request_attempted', False)
                and (getattr(self, '_request_mode', None) != mode
                     or getattr(self, '_request_receipt', None) is not request)):
            raise ValueError('original staged request and mode required')

    def report_only(self, root, java_home, receipt, output, logs, *, nonce, request,
                    handshake_bytes, report_bytes):
        """One saved-prefix report; original writer predebits both JVM allowances."""
        from fixed_witness_report_pipe import run_report_only
        self._enter('PREPARED', 'REPORTING')
        try:
            self._request_binding('report', request)
            result = run_report_only(root, java_home, receipt, output, logs, self.budget,
                                     nonce=nonce, request=request, handshake_bytes=handshake_bytes,
                                     report_bytes=report_bytes, total_deadline=self.deadline)
            self._finish(result['state'])
            return result
        except BaseException as error:
            if self.phase == 'REPORTING':
                self._finish('REPORT_FAILED', error)
            raise

    def execute(self, root, java_home, receipt, output, logs, *, nonce, request,
                handshake_bytes, journal_bytes, declaration_bytes, report_bytes):
        """One caller-authorized request, never an execution permission itself.

        The existing execution transport reserves H+J+D+R atomically before
        spawn and binds the exact limits to Java's real writers. All request
        copies must already have been charged to this same received budget.
        """
        from fixed_witness_replay_execution import run_execution
        self._enter('PREPARED', 'EXECUTING')
        try:
            self._request_binding('execute', request)
            result = run_execution(root, java_home, receipt, output, logs, self.budget,
                                   nonce=nonce, request=request, handshake_bytes=handshake_bytes,
                                   journal_bytes=journal_bytes, declaration_bytes=declaration_bytes,
                                   report_bytes=report_bytes, total_deadline=self.deadline)
            self._finish(result['state'], execution=True,
                         actions_started=result.get('verification', {}).get('actions_started'))
            return result
        except BaseException as error:
            if self.phase == 'EXECUTING':
                self._finish('EXECUTION_FAILED', error, execution=True)
            raise

    def _finish(self, state, error=None, *, execution=False, actions_started=None):
        self.phase = 'FINISHED'
        record = {'state': state,
                  'artifact_charged': self.budget.quota.charged,
                  'pipe_retained_bytes': self.budget.written}
        if execution:
            # Failure may follow ACK/actions: absence of a verified terminal
            # is unknown, never evidence that no actions started.
            record.update(execution_attempted=True, actions_started=actions_started,
                          diagnostic_authorized=False, network_isolation_verified=False)
        else:
            record['diagnostic_started'] = False
        if error is not None:
            record['error_type'] = type(error).__name__[:64]
            record['error'] = str(error)[:128]
        raw = (json.dumps(record, sort_keys=True, separators=(',', ':')) + '\n').encode('utf-8')
        try:
            write_exclusive(self.directory / 'coordinator-artifact-terminal.json', raw, self.terminal)
        except BaseException as terminal_error:
            self.terminal_error = terminal_error
            if error is None:
                raise
