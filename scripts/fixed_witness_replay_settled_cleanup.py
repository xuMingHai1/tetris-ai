"""Pidfd SIGKILL plus terminal reap for settled process births only.

No native operation runs on import. This is not unresolved/thread tree cleanup,
a capability probe, or permission to execute the historical fixture.
"""
import os
import signal

from fixed_witness_replay_cleanup_reap import CleanupReaper, CleanupReapError
from fixed_witness_replay_launch import CoordinatorLaunch
from fixed_witness_replay_handshake import CoordinatorHandshake

SIGKILL = 9  # Frozen Linux ABI; injectable synthetic transport also runs on Windows.


class SettledProcessCleanup:
    """Pin every eligible unreaped child before signalling any of them.

    The exclusive original tracer must retain wait ownership throughout. With
    no pending creation and one live birth per original TGID, neither another
    reaper nor a nonleader EXEC can free/reuse the PID before pidfd_open. After
    acquisition only the pinned descriptor is signalled, never a numeric PID.
    Existing failure latches and the original deadline/stop budget stay intact.
    """

    def __init__(self, launch, *, open_pidfd=None, send_signal=None,
                 close=os.close, **wait_options):
        # A returned fork PID before authenticated TRACEME admission can have
        # self-reaped under SIGCHLD=IGN/SA_NOCLDWAIT. Do not open that number.
        if (type(launch) is not CoordinatorLaunch or not launch.admitted
                or launch.dispatcher is None
                or type(launch.handshake) is not CoordinatorHandshake
                or not launch.handshake.completed
                or launch.handshake.dispatcher is not launch.dispatcher):
            raise CleanupReapError('authenticated traced launch required for pidfd binding')
        self.open_pidfd = open_pidfd if open_pidfd is not None else getattr(os, 'pidfd_open', None)
        self.send_signal = (send_signal if send_signal is not None
                            else getattr(signal, 'pidfd_send_signal', None))
        if not all(callable(item) for item in (self.open_pidfd, self.send_signal, close)):
            raise CleanupReapError('CAPABILITY_UNAVAILABLE: pidfd transport required')
        self.close = close
        self.reaper = CleanupReaper(launch, **wait_options)
        self.started = False
        self.stopped = None
        self.pinned = []
        self.signalled = []
        self.close_errors = []

    def _same_inventory(self, identities):
        self.reaper._check()
        if self.reaper._inventory() != identities:
            raise CleanupReapError('cleanup birth inventory changed')
        if any(item.tid in self.reaper.launch.unsettled_terminal_waits for item in identities):
            raise CleanupReapError('consumed terminal wait has unresolved birth identity')

    def run(self):
        """Attempt once; even ESRCH is an error, never evidence of reap."""
        if self.started:
            raise CleanupReapError('settled cleanup already attempted')
        self.started = True
        failed = True
        try:
            self.reaper._check()
            identities = self.reaper._inventory()
            self._same_inventory(identities)
            for item in identities:
                self._same_inventory(identities)
                fd = self.open_pidfd(item.tid, 0)
                if type(fd) is not int or not 0 <= fd < 2**31:
                    raise CleanupReapError('invalid pidfd transport result')
                if any(previous == fd for _, previous in self.pinned):
                    raise CleanupReapError('duplicate pidfd transport result')
                # Retain ownership before any fallible post-acquisition check.
                self.pinned.append((item, fd))
                self._same_inventory(identities)
            for item, fd in self.pinned:
                self._same_inventory(identities)
                result = self.send_signal(fd, SIGKILL, None, 0)
                if result is not None:
                    raise CleanupReapError('invalid pidfd signal transport result')
                self.signalled.append(item.birth)
                self._same_inventory(identities)
            self.reaper._after_pidfd_kill(identities)
            result = self.reaper.run()
            failed = False
            return result
        except BaseException:
            self.stopped = 'settled cleanup incomplete; preserve identities'
            raise
        finally:
            # Release descriptors even after expiry; never retry close(EINTR),
            # which could otherwise close a subsequently reused descriptor.
            for item, fd in reversed(self.pinned):
                try:
                    self.close(fd)
                except BaseException as error:
                    self.close_errors.append((item.birth, fd, error))
            if self.close_errors:
                self.stopped = 'settled cleanup descriptor release incomplete'
                if not failed:
                    raise CleanupReapError(self.stopped)
