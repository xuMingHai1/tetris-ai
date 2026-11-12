"""In-flight cleanup events with fake wait/ptrace/pidfd only; no native tasks."""
import struct
import unittest
from unittest.mock import call

from fixed_witness_replay_cleanup_reap import CleanupReapError
from fixed_witness_replay_creation import CLONE_VM, CLONE_VFORK, CLONE_THREAD, CLONE_SIGHAND, SIGCHLD
from fixed_witness_replay_tasks import TaskAccountingError
from fixed_witness_replay_wait_owner import WAIT_OPTIONS
from test_fixed_witness_replay_ptrace import entry
import test_fixed_witness_replay_bound_cleanup as bound_fixture


class InflightCleanupTest(unittest.TestCase):
    def setUp(self):
        self.fixture = bound_fixture.BoundCreationCleanupTest()
        self.fixture.setUp()
        self.launch, self.ledger = self.fixture.launch, self.fixture.ledger
        self.dispatch, self.transport = self.fixture.dispatch, self.fixture.transport

    def pending(self, number=57, flags=0):
        raw, size = entry(number)
        raw = bytearray(raw)
        struct.pack_into('<6Q', raw, 32, flags, 0, 0, 0, 0, 0)
        self.dispatch.syscall((bytes(raw), size))
        self.dispatch.wire.event_tid.return_value = 12
        self.fixture.stop()
        return self.fixture.fixture.owner()

    def test_fork_event_then_pin_kill_reap_without_resume_or_new_debit(self):
        owner = self.pending()
        before = self.ledger.totals
        self.transport.wait.side_effect = [(11, 0x1057f), (11, 9), (12, 9)]
        self.assertEqual(((1, 2, 12),), owner.reaper.bind_creation_events())
        owner.run()
        self.assertEqual([call.wait(11, WAIT_OPTIONS), call.open(11, 0), call.open(12, 0),
                          call.send(70, 9, None, 0), call.send(71, 9, None, 0),
                          call.wait(11, WAIT_OPTIONS), call.wait(12, WAIT_OPTIONS),
                          call.close(71), call.close(70)], self.transport.mock_calls)
        self.assertEqual([call.event_tid(11)], self.dispatch.wire.mock_calls)
        self.assertEqual((before.attempts, 1, 0, before.wait_stops + 1),
                         (self.ledger.totals.attempts, self.ledger.totals.live,
                          self.ledger.totals.pending, self.ledger.totals.wait_stops))
        self.assertEqual('ledger failure', self.ledger.totals.stopped)
        self.assertEqual('dispatch failure', self.launch.dispatcher.stopped)
        self.assertEqual('lifecycle failure', self.launch.dispatcher.lifecycle.stopped)
        self.assertEqual('reader failure', self.launch.reader.stopped)
        self.assertEqual('original failure', self.launch.stopped)

    def pending_thread(self):
        return self.pending(56, CLONE_VM | CLONE_SIGHAND | CLONE_THREAD)

    def test_thread_event_to_single_group_pin_kill_and_each_birth_reap(self):
        owner = self.pending_thread()
        before = self.ledger.totals
        self.transport.wait.side_effect = [(11, 0x3057f), (11, 9), (12, 9)]
        owner.run()
        self.assertEqual([call.wait(11, WAIT_OPTIONS), call.open(11, 0),
                          call.send(70, 9, None, 0), call.wait(11, WAIT_OPTIONS),
                          call.wait(12, WAIT_OPTIONS), call.close(70)], self.transport.mock_calls)
        self.assertEqual([call.event_tid(11)], self.dispatch.wire.mock_calls)
        self.assertEqual(((1, 2, 12),), tuple(owner.reaper.bound_creations))
        self.assertEqual((before.attempts, 1, 0, before.wait_stops + 1, before.stopped),
                         (self.ledger.totals.attempts, self.ledger.totals.live,
                          self.ledger.totals.pending, self.ledger.totals.wait_stops,
                          self.ledger.totals.stopped))

    def test_bound_thread_before_parent_result_needs_no_extra_creation_wait(self):
        owner = self.pending_thread()
        self.transport.wait.return_value = (11, 0x3057f)
        owner.reaper.bind_creation_events()
        self.transport.reset_mock()
        self.transport.wait.side_effect = [(11, 9), (12, 9)]
        owner.run()
        self.assertEqual([call(11, WAIT_OPTIONS), call(12, WAIT_OPTIONS)],
                         self.transport.wait.call_args_list)
        self.transport.open.assert_called_once_with(11, 0)

    def test_child_first_reap_keeps_pending_until_real_parent_reap(self):
        owner = self.pending_thread()
        def wait(tid, options):
            count = self.transport.wait.call_count
            if count == 1:
                return (11, 0x3057f)
            if count == 2:
                return (0, 0)
            if count == 3:
                return (12, 9)
            self.assertEqual((2, 1), (self.ledger.totals.live, self.ledger.totals.pending))
            return (11, 9)
        self.transport.wait.side_effect = wait
        result = owner.run()
        self.assertEqual([2, 1], [birth for birth, _, _ in result])
        self.assertEqual((1, 0), (self.ledger.totals.live, self.ledger.totals.pending))

    def test_thread_wrong_creation_event_never_reads_or_pins(self):
        for event in (0x1057f, 0x2057f, 0x4057f):
            self.setUp()
            owner = self.pending_thread()
            self.transport.wait.return_value = (11, event)
            with self.assertRaises(ValueError):
                owner.run()
            self.dispatch.wire.event_tid.assert_not_called()
            self.transport.open.assert_not_called()
            self.transport.send.assert_not_called()

    def test_thread_binding_wire_failure_keeps_birth_without_pin_or_refund(self):
        owner = self.pending_thread()
        self.launch.dispatcher._factory = lambda: (_ for _ in ()).throw(OSError('wire failed'))
        self.transport.wait.return_value = (11, 0x3057f)
        with self.assertRaises(ValueError):
            owner.run()
        self.assertEqual((12, 1), (self.ledger.identity(2).tid,
                                  self.ledger.identity(2).group_birth))
        self.assertEqual((3, 3, 1), (self.ledger.totals.attempts,
                                   self.ledger.totals.live, self.ledger.totals.pending))
        self.transport.open.assert_not_called()

    def test_thread_uncertain_event_read_cannot_retry_or_pin(self):
        owner = self.pending_thread()
        self.transport.wait.return_value = (11, 0x3057f)
        self.dispatch.wire.event_tid.side_effect = OSError('uncertain read')
        with self.assertRaises(OSError):
            owner.run()
        with self.assertRaises(CleanupReapError):
            owner.run()
        self.dispatch.wire.event_tid.assert_called_once_with(11)
        self.assertIsNone(self.ledger.cleanup_pending()[0].child)
        self.transport.open.assert_not_called()

    def test_thread_pin_failure_preserves_event_binding_and_original_failure(self):
        owner = self.pending_thread()
        self.transport.wait.return_value = (11, 0x3057f)
        self.transport.open.side_effect = OSError('pin failed')
        with self.assertRaises(OSError):
            owner.run()
        self.assertEqual(2, self.ledger.cleanup_pending()[0].child)
        self.assertEqual('original failure', self.launch.stopped)
        self.assertEqual(3, self.ledger.totals.live)
        self.transport.send.assert_not_called()

    def test_bound_thread_without_original_native_obligation_rejected_before_pin(self):
        owner = self.pending_thread()
        self.transport.wait.return_value = (11, 0x3057f)
        owner.reaper.bind_creation_events()
        self.launch.dispatcher._sequence[1] = None
        self.transport.reset_mock()
        with self.assertRaisesRegex(ValueError, 'original native clone'):
            owner.run()
        self.assertEqual([], self.transport.mock_calls)

    def test_vfork_and_legacy_clone_process_event_mapping(self):
        for number, flags, raw in ((58, 0, 0x2057f),
                                   (56, SIGCHLD | CLONE_VM, 0x1057f),
                                   (56, SIGCHLD | CLONE_VM | CLONE_VFORK, 0x2057f)):
            with self.subTest(number=number, flags=flags):
                self.setUp()
                owner = self.pending(number, flags)
                self.transport.wait.side_effect = [(11, raw), (11, 9), (12, 9)]
                owner.reaper.bind_creation_events()
                owner.run()
                self.assertEqual((3, 1, 0), (self.ledger.totals.attempts,
                                           self.ledger.totals.live, self.ledger.totals.pending))

    def test_eintr_and_idle_poll_use_original_target_options_and_deadline(self):
        owner = self.pending()
        self.transport.wait.side_effect = [InterruptedError(), (0, 0), (11, 0x1057f)]
        owner.reaper.bind_creation_events()
        self.assertEqual([call(11, WAIT_OPTIONS)] * 3, self.transport.wait.call_args_list)
        self.assertEqual([call(0.01)] * 2, self.fixture.fixture.fixture.sleep.call_args_list)

    def test_idle_expiry_stops_without_refund_or_native_pin(self):
        owner = self.pending()
        self.transport.wait.return_value = (0, 0)
        self.dispatch.clock.return_value = 99.997
        self.fixture.fixture.fixture.sleep.side_effect = lambda _: setattr(
            self.dispatch.clock, 'return_value', 100.0)
        with self.assertRaisesRegex(CleanupReapError, 'deadline'):
            owner.reaper.bind_creation_events()
        self.assertEqual((3, 3, 1), (self.ledger.totals.attempts,
                                   self.ledger.totals.live, self.ledger.totals.pending))
        self.transport.open.assert_not_called()

    def test_wrong_event_and_noncreation_stops_debit_without_ptrace(self):
        for raw in (0x2057f, 0x3057f, 0x4057f, 0x137f, 0x857f, 0x5057f):
            with self.subTest(raw=raw):
                self.setUp()
                owner = self.pending()
                before = self.ledger.totals.wait_stops
                self.transport.wait.return_value = (11, raw)
                with self.assertRaises(ValueError):
                    owner.reaper.bind_creation_events()
                self.assertEqual(before + 1, self.ledger.totals.wait_stops)
                self.assertEqual([], self.dispatch.wire.mock_calls)

    def test_wrong_target_stop_debits_before_rejection(self):
        owner = self.pending()
        before = self.ledger.totals.wait_stops
        self.transport.wait.return_value = (99, 0x1057f)
        with self.assertRaisesRegex(CleanupReapError, 'parent mismatch'):
            owner.reaper.bind_creation_events()
        self.assertEqual(before + 1, self.ledger.totals.wait_stops)
        self.dispatch.wire.event_tid.assert_not_called()

    def test_stop_budget_exhaustion_prevents_event_read(self):
        owner = self.pending()
        self.ledger._event_limit = self.ledger.totals.wait_stops + 1
        self.transport.wait.return_value = (11, 0x1057f)
        with self.assertRaises(TaskAccountingError):
            owner.reaper.bind_creation_events()
        self.dispatch.wire.event_tid.assert_not_called()
        self.assertEqual('ledger failure', self.ledger.totals.stopped)

    def test_owner_or_deadline_change_after_wait_debits_before_rejection(self):
        for change in ('owner', 'deadline'):
            self.setUp()
            owner = self.pending()
            before = self.ledger.totals.wait_stops
            def wait(*_):
                if change == 'owner':
                    self.dispatch.system.getpid.return_value = 99
                else:
                    self.dispatch.clock.return_value = 100.0
                return (11, 0x1057f)
            self.transport.wait.side_effect = wait
            with self.assertRaises(CleanupReapError):
                owner.reaper.bind_creation_events()
            self.assertEqual(before + 1, self.ledger.totals.wait_stops)
            self.dispatch.wire.event_tid.assert_not_called()

    def test_terminal_result_quarantined_never_reaped_or_refunded(self):
        owner = self.pending()
        self.transport.wait.return_value = (11, 9)
        with self.assertRaises(CleanupReapError):
            owner.reaper.bind_creation_events()
        self.assertEqual({11}, self.launch.unsettled_terminal_waits)
        self.assertTrue(self.ledger.identity(1).alive)
        with self.assertRaisesRegex(CleanupReapError, 'already failed'):
            owner.run()
        self.transport.open.assert_not_called()

    def test_echild_and_wire_errors_one_shot_without_inferred_child(self):
        for failure in ('wait', 'wire'):
            self.setUp()
            owner = self.pending()
            if failure == 'wait':
                self.transport.wait.side_effect = ChildProcessError('ECHILD')
            else:
                self.transport.wait.return_value = (11, 0x1057f)
                self.dispatch.wire.event_tid.side_effect = OSError('read failed')
            with self.assertRaises(OSError):
                owner.reaper.bind_creation_events()
            with self.assertRaises(CleanupReapError):
                owner.reaper.bind_creation_events()
            self.assertIsNone(self.ledger.cleanup_pending()[0].child)

    def test_invalid_or_duplicate_child_tid_never_creates_binding(self):
        for tid in (True, 0, -1, 2**31, 11, 10):
            self.setUp()
            owner = self.pending()
            self.transport.wait.return_value = (11, 0x1057f)
            self.dispatch.wire.event_tid.return_value = tid
            with self.assertRaises(ValueError):
                owner.reaper.bind_creation_events()
            self.assertIsNone(self.ledger.cleanup_pending()[0].child)
            self.assertEqual(3, self.ledger.totals.live)

    def test_wire_factory_failure_retains_child_birth_and_partial_binding(self):
        owner = self.pending()
        self.transport.wait.return_value = (11, 0x1057f)
        self.launch.dispatcher._factory = lambda: (_ for _ in ()).throw(OSError('wire failed'))
        with self.assertRaises(ValueError):
            owner.reaper.bind_creation_events()
        self.assertEqual(12, self.ledger.identity(2).tid)
        self.assertIsNone(self.launch.dispatcher._task(2, 12))
        self.assertEqual(2, self.ledger.cleanup_pending()[0].child)
        self.transport.open.assert_not_called()

    def test_expiry_after_event_read_retains_raw_identity_facts(self):
        owner = self.pending()
        self.transport.wait.return_value = (11, 0x1057f)
        def event(_):
            self.dispatch.clock.return_value = 100.0
            return 12
        self.dispatch.wire.event_tid.side_effect = event
        with self.assertRaisesRegex(CleanupReapError, 'deadline'):
            owner.reaper.bind_creation_events()
        self.assertEqual(12, owner.reaper.last_creation_event[3])
        self.assertEqual((1, (11, 0x1057f)), owner.reaper.last_wait)
        self.transport.open.assert_not_called()

    def test_reused_or_changed_parent_birth_refuses_before_event_read(self):
        owner = self.pending()
        def wait(*_):
            self.ledger.reaped(1)
            return (11, 0x1057f)
        self.transport.wait.side_effect = wait
        with self.assertRaisesRegex(CleanupReapError, 'parent mismatch'):
            owner.reaper.bind_creation_events()
        self.dispatch.wire.event_tid.assert_not_called()

    def test_unsupported_or_unmatched_reservations_refuse_before_wait(self):
        for kind, thread in (('clone3', False), ('clone', True), ('fork', False)):
            self.setUp()
            self.ledger.reserve(1, kind, thread=thread)
            self.fixture.stop()
            owner = self.fixture.fixture.owner()
            with self.assertRaises(ValueError):
                owner.reaper.bind_creation_events()
            self.assertEqual([], self.transport.mock_calls)

    def test_binding_phase_is_one_shot_and_unavailable_after_reap_started(self):
        owner = self.pending()
        self.transport.wait.return_value = (11, 0x1057f)
        owner.reaper.bind_creation_events()
        with self.assertRaises(CleanupReapError):
            owner.reaper.bind_creation_events()
        self.transport.wait.side_effect = [(11, 9), (12, 9)]
        owner.run()
        with self.assertRaises(CleanupReapError):
            owner.reaper.bind_creation_events()

    def test_malformed_wait_never_binds_or_refunds(self):
        for result in (None, [11, 0x1057f], (11,), (True, 0x1057f), (11, True), (0, 1)):
            self.setUp()
            owner = self.pending()
            self.transport.wait.return_value = result
            with self.assertRaises(ValueError):
                owner.reaper.bind_creation_events()
            self.assertIsNone(self.ledger.cleanup_pending()[0].child)
            self.dispatch.wire.event_tid.assert_not_called()


if __name__ == '__main__':
    unittest.main()
