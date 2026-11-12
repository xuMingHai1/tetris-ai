"""Existing siblings plus pending native clone; every kernel transport is fake."""
import struct
import unittest
from unittest.mock import Mock, call

from fixed_witness_replay_cleanup_reap import CleanupReapError
from fixed_witness_replay_ptrace import decode_syscall_info
from fixed_witness_replay_wait_owner import BoundedWaitOwner, WAIT_OPTIONS
from test_fixed_witness_replay_ptrace import entry
import test_fixed_witness_replay_thread_cleanup as fixture


class GroupThreadCreationCleanupTest(unittest.TestCase):
    def setUp(self):
        self.f = fixture.ThreadCleanupTest()
        self.f.setUp()
        self.launch, self.ledger = self.f.launch, self.f.ledger
        self.transport = self.f.transport
        self.launch.dispatcher._factory = lambda: Mock()
        raw, size = entry(56)
        raw = bytearray(raw)
        struct.pack_into('<Q', raw, 32, 0x10000 | 0x800 | 0x100)
        self.f.f.syscall((bytes(raw), size))
        self.f.f.wire.reset_mock()
        self.f.f.wire.event_tid.return_value = 13
        self.transport.wait.side_effect = [(11, 0x3057f), (11, 9), (12, 9), (13, 9)]

    def cleanup(self):
        self.f.stop()
        return self.f.owner()

    def untouched(self):
        self.assertEqual([], self.transport.mock_calls)
        self.f.f.wire.event_tid.assert_not_called()

    def test_original_event_joins_one_group_pin_and_three_real_reaps(self):
        before = self.ledger.totals
        owner = self.cleanup()
        result = owner.run()
        self.assertEqual([(1, 11), (2, 12), (3, 13)], [(b, t) for b, t, _ in result])
        self.assertEqual([call.wait(11, WAIT_OPTIONS), call.open(11, 0),
                          call.send(70, 9, None, 0), call.wait(11, WAIT_OPTIONS),
                          call.wait(12, WAIT_OPTIONS), call.wait(13, WAIT_OPTIONS),
                          call.close(70)], self.transport.mock_calls)
        self.assertEqual([call.event_tid(11)], self.f.f.wire.mock_calls)
        self.assertEqual([], self.f.child.mock_calls)
        self.assertEqual((before.attempts, 1, 0, before.wait_stops + 1, 'original ledger failure'),
                         (self.ledger.totals.attempts, self.ledger.totals.live,
                          self.ledger.totals.pending, self.ledger.totals.wait_stops,
                          self.ledger.totals.stopped))
        self.assertEqual('original failure', self.launch.stopped)

    def test_new_child_reap_does_not_settle_original_parent_obligation(self):
        def wait(tid, options):
            if self.transport.wait.call_count == 1:
                return (11, 0x3057f)
            if tid == 13:
                return (13, 9)
            if self.transport.wait.call_count < 5:
                return (0, 0)
            if tid == 11:
                self.assertEqual(1, self.ledger.totals.pending)
            return (tid, 9)
        self.transport.wait.side_effect = wait
        result = self.cleanup().run()
        self.assertEqual([3, 1, 2], [b for b, _, _ in result])
        self.assertEqual((1, 0), (self.ledger.totals.live, self.ledger.totals.pending))

    def test_sibling_unmatched_exec_blocks_before_creation_wait(self):
        for number in (59, 322):
            self.setUp()
            self.f.child.syscall_info.return_value = decode_syscall_info(*entry(number))
            self.f.f.owner.accept(12, 0x857f)
            with self.assertRaisesRegex(ValueError, 'thread/exec'):
                self.cleanup().run()
            self.untouched()

    def test_missing_sibling_wire_blocks_before_creation_wait(self):
        self.launch.dispatcher._tasks[2] = (12, None)
        with self.assertRaises(ValueError):
            self.cleanup().run()
        self.untouched()

    def test_reaped_leader_is_not_reopened_or_polled(self):
        self.f.f.owner.accept(11, 0)
        with self.assertRaises(ValueError):
            self.cleanup().run()
        self.untouched()

    def test_quarantined_sibling_blocks_before_creation_wait(self):
        self.launch.retain_terminal_wait(12, 9)
        with self.assertRaisesRegex(ValueError, 'ambiguous'):
            self.cleanup().run()
        self.untouched()

    def test_missing_original_obligation_blocks_before_creation_wait(self):
        self.launch.dispatcher._sequence[1] = None
        with self.assertRaises(ValueError):
            self.cleanup().run()
        self.untouched()

    def test_process_creation_in_existing_group_remains_incomplete(self):
        # A different exact fixture: settle the clone ENTRY before reserving fork.
        self.f = fixture.ThreadCleanupTest()
        self.f.setUp()
        self.launch, self.ledger, self.transport = self.f.launch, self.f.ledger, self.f.transport
        self.f.f.syscall(entry(57))
        self.f.f.wire.reset_mock()
        with self.assertRaisesRegex(ValueError, 'bound original thread'):
            self.cleanup().run()
        self.untouched()

    def test_failed_original_event_read_is_not_retried(self):
        normal = BoundedWaitOwner(self.f.f.owner, waitpid=Mock(return_value=(11, 0x3057f)))
        self.f.f.wire.event_tid.side_effect = OSError('uncertain read')
        with self.assertRaises(ValueError):
            normal.run()
        self.f.f.wire.reset_mock()
        with self.assertRaisesRegex(CleanupReapError, 'uncertain'):
            self.cleanup().run()
        self.untouched()

    def test_original_read_receipt_reused_without_second_read_or_stop_debit(self):
        def event(_):
            self.launch.dispatcher.stopped = 'bind failure'
            return 13
        self.f.f.wire.event_tid.side_effect = event
        normal = BoundedWaitOwner(self.f.f.owner, waitpid=Mock(return_value=(11, 0x3057f)))
        with self.assertRaises(ValueError):
            normal.run()
        self.f.f.wire.event_tid.side_effect = None
        self.f.f.wire.reset_mock()
        before = self.ledger.totals
        self.transport.wait.side_effect = [(11, 9), (12, 9), (13, 9)]
        owner = self.cleanup()
        owner.run()
        self.assertIs(owner.reaper.adopted_wait, normal.last_wait)
        self.assertEqual(before.wait_stops, self.ledger.totals.wait_stops)
        self.assertEqual([], self.f.f.wire.mock_calls)
        self.assertEqual([call(11, WAIT_OPTIONS), call(12, WAIT_OPTIONS), call(13, WAIT_OPTIONS)],
                         self.transport.wait.call_args_list)

    def test_child_wire_failure_preserves_birth_and_prevents_pin(self):
        def fail():
            raise OSError('wire failure')
        self.launch.dispatcher._factory = fail
        with self.assertRaises(ValueError):
            self.cleanup().run()
        self.assertEqual((13, 1), (self.ledger.identity(3).tid, self.ledger.identity(3).group_birth))
        self.assertEqual((4, 4, 1), (self.ledger.totals.attempts, self.ledger.totals.live,
                                   self.ledger.totals.pending))
        self.transport.open.assert_not_called()

    def test_pin_failure_keeps_new_child_binding_and_never_resumes(self):
        self.transport.open.side_effect = OSError('pin failure')
        owner = self.cleanup()
        with self.assertRaises(OSError):
            owner.run()
        with self.assertRaises(CleanupReapError):
            owner.run()
        self.assertEqual(3, self.ledger.cleanup_pending()[0].child)
        self.assertEqual(4, self.ledger.totals.live)
        self.assertEqual([call.event_tid(11)], self.f.f.wire.mock_calls)
        self.transport.send.assert_not_called()


if __name__ == '__main__':
    unittest.main()
