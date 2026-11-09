"""Synthetic wait words only: no child process, ptrace or capability probe."""
import dataclasses
import unittest

from fixed_witness_replay_wait import WaitStatusError, decode_wait_status


def stopped(sig, event=0):
    return (event << 16) | (sig << 8) | 0x7f


class WaitStatusTest(unittest.TestCase):
    def test_all_exit_codes(self):
        for code in range(256):
            value = decode_wait_status(code << 8)
            self.assertEqual((value.kind, value.exit_code, value.signal), ('exited', code, None))

    def test_all_terminal_signals_and_core_bit(self):
        for sig in range(1, 65):
            for core in (False, True):
                value = decode_wait_status(sig | (0x80 if core else 0))
                self.assertEqual((value.kind, value.signal, value.core_dumped), ('signaled', sig, core))

    def test_all_signal_stops_remain_ambiguous(self):
        for sig in range(1, 65):
            value = decode_wait_status(stopped(sig))
            self.assertEqual((value.kind, value.signal, value.event), ('signal-stop', sig, None))

    def test_syscall_trap_does_not_guess_entry_or_exit(self):
        value = decode_wait_status(stopped(133))
        self.assertEqual((value.kind, value.signal, value.event), ('syscall-stop', 133, None))
        self.assertEqual(decode_wait_status(stopped(5)).kind, 'signal-stop')

    def test_enabled_ptrace_events(self):
        for event, name in ((1, 'FORK'), (2, 'VFORK'), (3, 'CLONE'), (4, 'EXEC')):
            value = decode_wait_status(stopped(5, event))
            self.assertEqual((value.kind, value.event, value.signal), ('ptrace-event', name, 5))

    def test_disabled_and_unknown_events_fail_closed(self):
        for event in (5, 6, 7, 128, 255, 256, 65535):
            with self.subTest(event=event), self.assertRaises(WaitStatusError):
                decode_wait_status(stopped(5, event))

    def test_event_must_have_plain_sigtrap(self):
        for sig in (0, 9, 19, 64, 133, 255):
            with self.subTest(sig=sig), self.assertRaises(WaitStatusError):
                decode_wait_status(stopped(sig, 1))

    def test_invalid_signal_and_continued_words(self):
        for word in (0x80, 65, 127, 255, 0xffff, stopped(0), stopped(65), stopped(128), stopped(255)):
            with self.subTest(word=word), self.assertRaises(WaitStatusError):
                decode_wait_status(word)

    def test_nonstop_high_bits_are_rejected(self):
        for word in (0x10000, 0x10009, 0x10089, 0x109, 0x189, 0x80000000, 0xffffffff):
            with self.subTest(word=word), self.assertRaises(WaitStatusError):
                decode_wait_status(word)

    def test_strict_integer_and_width(self):
        for word in (True, False, None, '0', 0.0, -1, 2**32):
            with self.subTest(word=word), self.assertRaises(WaitStatusError):
                decode_wait_status(word)

    def test_results_are_immutable(self):
        value = decode_wait_status(0)
        with self.assertRaises(dataclasses.FrozenInstanceError):
            value.kind = 'syscall-stop'


if __name__ == '__main__':
    unittest.main()
