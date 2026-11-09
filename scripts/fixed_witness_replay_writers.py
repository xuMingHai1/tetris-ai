"""Fixed writer reservations for the accepted manual supervisor design.

This is host-side allocation only, not a cross-process quota transport. Never
fork, pickle or reconstruct these ByteQuota objects for independent writers.
The future host must bind each permanent reservation to exactly one enforcing
writer before launch; no supervisor launch or capability claim is made here.
"""
from dataclasses import dataclass

from fixed_witness_replay_quota import ByteQuota, OUTPUT_BYTES


REPORT_BYTES = 32 * 1024
COORDINATOR_BYTES = OUTPUT_BYTES - 2 * REPORT_BYTES


@dataclass(frozen=True)
class WriterReservations:
    """Disjoint allowances; their charges must not be added to the root again.

    Each writer must reserve its own failure prefix inside its allowance. The
    host cannot borrow unused tracer/coordinator credit, even after failure.
    """

    tracer: ByteQuota
    host: ByteQuota
    coordinator: ByteQuota


def reserve_writers(root):
    """Permanently split a fresh 10MiB root before any session task launches.

    One atomic full-root debit precedes subdivision. An already used root or
    competing second allocation fails without partially reserving reports.
    The root remains fully charged even if subdivision or later I/O fails.
    This exact policy applies to the accepted manual suite, not ordinary CI
    test processes or a new historical replay execution permission.
    """
    if not isinstance(root, ByteQuota) or root.limit != OUTPUT_BYTES:
        raise ValueError('fresh frozen 10MiB ByteQuota required')
    allocation = root.reserve(OUTPUT_BYTES)
    return WriterReservations(
        tracer=allocation.reserve(REPORT_BYTES),
        host=allocation.reserve(REPORT_BYTES),
        coordinator=allocation.reserve(COORDINATOR_BYTES),
    )
