"""Pidfd SIGKILL plus terminal reap for authenticated process births.

No native operation runs on import. This is not arbitrary tree/EXEC cleanup,
a capability probe, or permission to execute the historical fixture.
"""
import os
import signal

from fixed_witness_replay_cleanup_reap import CleanupReaper, CleanupReapError
from fixed_witness_replay_launch import CoordinatorLaunch
from fixed_witness_replay_handshake import CoordinatorHandshake
from fixed_witness_replay_startup_pin import CoordinatorPin

SIGKILL = 9  # Frozen Linux ABI; injectable synthetic transport also runs on Windows.


class SettledProcessCleanup:
    """Pin every eligible unreaped child before signalling any of them.

    The exclusive original tracer must retain wait ownership throughout. With
    known creations bound first, each live original TGID and every thread's
    settled non-EXEC state checked, neither another reaper nor a nonleader EXEC
    can free/reuse the group PID before pidfd_open. After acquisition only the pinned descriptor is signalled,
    never a numeric PID.
    Existing failure latches and the original deadline/stop budget stay intact.
    """

    def __init__(self, launch, *, open_pidfd=None, send_signal=None,
                 close=os.close, **wait_options):
        authenticated = (type(launch) is CoordinatorLaunch and launch.admitted
                and launch.dispatcher is not None
                and type(launch.handshake) is CoordinatorHandshake
                and launch.handshake.completed
                and launch.handshake.dispatcher is launch.dispatcher)
        pin = getattr(launch, 'startup_pin', None)
        self.startup_pin = None
        if not authenticated and type(launch) is CoordinatorLaunch and type(pin) is CoordinatorPin:
            # Only the retained pre-wait descriptor can serve a failed startup.
            # Never infer a pin from admitted (which means attempted) or PID.
            identities = launch.ledger.cleanup_identities()
            if (pin.fd is not None and not pin.released and len(identities) == 1
                    and identities[0].birth == launch.ticket
                    and identities[0].tid == pin.pid == launch.cleanup_tid
                    and identities[0].tid == identities[0].tgid
                    and not launch.ledger.cleanup_pending()
                    and identities[0].tid not in launch.unsettled_terminal_waits):
                self.startup_pin = pin
        if not authenticated and self.startup_pin is None:
            raise CleanupReapError('authenticated traced launch or retained startup pin required for pidfd binding')
        self.open_pidfd = open_pidfd if open_pidfd is not None else getattr(os, 'pidfd_open', None)
        self.send_signal = (send_signal if send_signal is not None
                            else getattr(signal, 'pidfd_send_signal', None))
        if not all(callable(item) for item in (self.open_pidfd, self.send_signal, close)):
            raise CleanupReapError('CAPABILITY_UNAVAILABLE: pidfd transport required')
        self.close = self.startup_pin.close if self.startup_pin is not None else close
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
            # EXEC may have retired/rekeyed a thread before normal dispatch
            # failed. Adopt only that owner's consumed event before any PID pin.
            if not self.reaper.exec_binding_started:
                self.reaper.bind_retained_exec()
            if not self.reaper.inflight_exec_started:
                self.reaper.bind_inflight_exec()
            # Recover the original owner's consumed creation event, or wait
            # for its already-reserved native event, before opening any PID.
            # Never retry a previously attempted/failed binding phase. Fully
            # bound inventories keep their existing no-extra-wait path.
            if (not self.reaper.creation_binding_started
                    and any(item.child is None
                            for item in self.reaper.ledger.cleanup_pending())):
                self.reaper.bind_creation_events()
            identities = self.reaper._inventory()
            self._same_inventory(identities)
            # pidfd_open(flags=0) and pidfd_send_signal(flags=0) address the
            # whole thread group. Never open a nonleader TID or signal a group
            # twice. The full birth inventory remains frozen through all pins
            # and sends, and every member still requires its own actual reap.
            leaders = tuple(item for item in identities if item.tid == item.tgid)
            for item in leaders:
                self._same_inventory(identities)
                fd = (self.startup_pin.take(item.tid) if self.startup_pin is not None
                      else self.open_pidfd(item.tid, 0))
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
