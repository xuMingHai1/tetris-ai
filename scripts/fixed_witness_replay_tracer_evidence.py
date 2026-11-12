"""Bounded lifecycle observations, never the manual suite's terminal receipt.

The original tracer owns this writer in-process. A future Java host must still
authenticate and transfer its disjoint reservation; copying ByteQuota objects
across a process boundary is not supported by this component.
"""
import json
from pathlib import Path

from fixed_witness_replay_quota import ByteQuota, write_exclusive
from fixed_witness_replay_writers import WriterReservations, REPORT_BYTES


class TracerEvidence:
    """Keep an immutable failure prefix before any native preparation or fork.

    The final fixed-size observation is attempted once, after output closure
    and original cleanup. No exception text, trace, argv or unbounded inventory
    is serialized. Both retained files debit the original tracer partition;
    failed open/write/fsync keeps its charge and is never retried.
    """

    def __init__(self, writers, directory):
        if (type(writers) is not WriterReservations
                or type(writers.tracer) is not ByteQuota
                or writers.tracer.limit != REPORT_BYTES):
            raise ValueError('original tracer writer partition required')
        self.quota = writers.tracer
        with self.quota._lock:
            if getattr(self.quota, '_evidence_claimed', False):
                raise ValueError('tracer evidence writer already claimed')
            self.quota._evidence_claimed = True
        self.directory = Path(directory)
        self.owner = None
        self.attempted = False
        self.failure = None
        # The prefix deliberately survives a later successful observation. It
        # is never overwritten with a capability/suite-success declaration.
        write_exclusive(self.directory / 'tracer-incomplete.txt', b'INCOMPLETE\n', self.quota)

    def claim(self, owner):
        if self.owner is not None or self.attempted:
            raise ValueError('exclusive tracer evidence owner required')
        self.owner = owner

    def finish(self, owner):
        if owner is not self.owner or self.owner is None or self.attempted:
            raise ValueError('original once-only tracer evidence owner required')
        self.attempted = True
        try:
            totals = owner.launch.ledger.totals
            observation = {
                'schema': 'fixed-witness-tracer-lifecycle-v1',
                'state': 'DRAINED_OBSERVATION' if owner.drained else 'INCOMPLETE',
                'suite_verified': False,
                'attempts': totals.attempts,
                'live': totals.live,
                'pending': totals.pending,
                'wait_stops': totals.wait_stops,
                'clone3_enosys': totals.clone3_enosys,
                'launch_attempted': owner.launch.attempted,
                'handshake_admitted': owner.launch.admitted,
                'ledger_stopped': totals.stopped is not None,
                'launch_stopped': owner.launch.stopped is not None,
                'failed': owner.failure is not None,
                'cleanup_failed': owner.cleanup_error is not None,
                'output_closed': owner.output is not None and owner.output.closed,
                'output_failed': owner.output is not None and owner.output.failure is not None,
            }
            data = (json.dumps(observation, sort_keys=True, separators=(',', ':')) + '\n').encode('ascii')
            write_exclusive(self.directory / 'tracer-lifecycle.json', data, self.quota)
        except BaseException as error:
            self.failure = error
            raise
