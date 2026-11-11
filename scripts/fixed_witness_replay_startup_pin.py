"""Parent-owned startup pidfd; no fork, wait, signal or capability execution.

The dedicated sole waiter must retain SIGCHLD disposition and prohibit other
reapers from the pre-fork check until capture. A pidfd pins the returned child,
not a numeric PID recovered later from /proc. No host configuration is changed.
"""
import ctypes
import os
import platform
import sys


class StartupPinError(ValueError):
    """Startup cannot continue; retain the original cleanup duty, never retry."""


class _KernelSigaction(ctypes.Structure):
    # Linux x86-64 rt_sigaction ABI, NOT libc's larger struct sigaction.
    _fields_ = [('handler', ctypes.c_uint64), ('flags', ctypes.c_uint64),
                ('restorer', ctypes.c_uint64), ('mask', ctypes.c_uint64)]


class CoordinatorPin:
    """One pre-fork non-autoreap check and one immediate parent acquisition.

    Default SIGCHLD with no SA_NOCLDWAIT leaves an exited child waitable until
    the original owner consumes it. A custom/ignored handler is rejected, not
    reset. The cooperative host must forbid all other waiters/signal changes;
    this is not protection against a hostile same-process caller.
    """

    def __init__(self, action, open_pidfd, close):
        if not all(callable(fn) for fn in (action, open_pidfd, close)):
            raise StartupPinError('startup pin transport required')
        self.action, self.open_pidfd, self.close = action, open_pidfd, close
        self.prepared = self.attempted = self.released = False
        self.pid = self.fd = None

    @classmethod
    def for_host(cls):
        if (sys.platform != 'linux' or platform.machine() != 'x86_64'
                or sys.byteorder != 'little' or ctypes.sizeof(ctypes.c_void_p) != 8
                or not callable(getattr(os, 'pidfd_open', None))):
            raise StartupPinError('CAPABILITY_UNAVAILABLE: Linux x86-64 pidfd required')
        syscall = ctypes.CDLL(None, use_errno=True).syscall
        syscall.restype = ctypes.c_long

        def action():
            value = _KernelSigaction()
            # Read only: NULL new action; kernel sigset size is exactly 8.
            result = syscall(ctypes.c_long(13), ctypes.c_long(17), ctypes.c_void_p(),
                             ctypes.byref(value), ctypes.c_size_t(8))
            if result != 0:
                raise StartupPinError('CAPABILITY_UNAVAILABLE: SIGCHLD query failed')
            return value.handler, value.flags

        return cls(action, os.pidfd_open, os.close)

    def prepare(self):
        if self.prepared or self.attempted:
            raise StartupPinError('startup pin preparation already attempted')
        self.attempted = True
        value = self.action()
        if (type(value) is not tuple or len(value) != 2
                or any(type(item) is not int or not 0 <= item < 2**64 for item in value)
                or value[0] != 0 or value[1] & 2):  # SIG_DFL; !SA_NOCLDWAIT
            raise StartupPinError('CAPABILITY_UNAVAILABLE: waitable SIGCHLD required')
        self.prepared = True

    def capture(self, pid):
        if (not self.prepared or self.pid is not None or self.released
                or type(pid) is not int or not 0 < pid < 2**31):
            raise StartupPinError('one prepared coordinator PID required')
        self.pid = pid  # Retain the attempted target even on an uncertain open.
        fd = self.open_pidfd(pid, 0)
        if type(fd) is not int or not 0 <= fd < 2**31:
            raise StartupPinError('invalid startup pidfd result')
        self.fd = fd

    def take(self, pid):
        """Transfer exactly once to cleanup; never reopen a consumed PID."""
        if (type(pid) is not int or pid != self.pid or self.fd is None or self.released):
            raise StartupPinError('retained coordinator pin required')
        fd, self.fd, self.released = self.fd, None, True
        return fd

    def release(self):
        """Close once, including after expiry; close(EINTR) must not be retried."""
        if self.fd is not None:
            self.close(self.take(self.pid))
