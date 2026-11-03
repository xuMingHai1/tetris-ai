"""Pre-write byte accounting for cooperative fixed-witness output writers.

Reservations debit the parent immediately and are never refunded. They bound a
child's maximum output, not its actual file size. A later supervisor must route
EVERY writer through one root or an enforced reservation; this module neither
monitors directories nor constrains an unmodified external/JVM writer.
"""
import os
from pathlib import Path
from threading import Lock


OUTPUT_BYTES = 10 * 1024 * 1024


class OutputLimitError(ValueError):
    """The next complete artifact cannot fit within its allocated allowance."""


def _count(value):
    if type(value) is not int or value < 0:
        raise ValueError('nonnegative integer byte count required')
    return value


class ByteQuota:
    """Monotone, thread-safe allowance; failed I/O never returns byte credit.

    Allocate a failure-record reservation before normal output. The remainder
    can be used for pipes/artifacts or split into bounded child allowances.
    Parent charged bytes include entire reservations: adding their children's
    charges again would double count them. No release/reset API is provided.
    """

    def __init__(self, limit=OUTPUT_BYTES):
        _count(limit)
        if not 0 < limit <= OUTPUT_BYTES:
            raise ValueError('quota must be within frozen 10MiB ceiling')
        self._limit = limit
        self._charged = 0
        self._lock = Lock()

    @property
    def limit(self):
        return self._limit

    @property
    def remaining(self):
        with self._lock:
            return self._limit - self._charged

    @property
    def charged(self):
        with self._lock:
            return self._charged

    def charge(self, count):
        """Charge all bytes atomically before an artifact is opened/written."""
        _count(count)
        with self._lock:
            if count > self._limit - self._charged:
                raise OutputLimitError('cumulative output allowance exhausted')
            self._charged += count

    def take_prefix(self, count):
        """Atomically charge the retained pipe prefix, possibly zero bytes."""
        _count(count)
        with self._lock:
            retained = min(count, self._limit - self._charged)
            self._charged += retained
            return retained

    def reserve(self, count):
        """Permanently allocate a child allowance BEFORE its producer starts.

        An external producer must independently enforce this exact limit. A
        reservation alone is not enforcement, and unused credit is not reused.
        """
        child = ByteQuota(count)
        self.charge(count)
        return child


def write_exclusive(path, data, quota):
    """Write one complete binary artifact, with charge before exclusive open.

    No overwrite, refund or retry: partial writes and fsync/open failures keep
    their full charge. Caller owns trusted paths and handles failure evidence.
    Logs already charged by OutputBudget must not be copied through this API
    unless the copy is an additional retained artifact requiring another debit.
    """
    if not isinstance(data, bytes) or not isinstance(quota, ByteQuota):
        raise ValueError('bytes and shared ByteQuota required')
    quota.charge(len(data))
    with Path(path).open('xb') as stream:
        if stream.write(data) != len(data):
            raise OSError('short artifact write')
        stream.flush()
        os.fsync(stream.fileno())
