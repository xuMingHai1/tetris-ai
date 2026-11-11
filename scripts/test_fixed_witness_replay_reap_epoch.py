"""Fake owned waits and installed EXEC epochs only; no native operations."""
from dataclasses import replace
import errno
import unittest
from unittest.mock import Mock

from fixed_witness_replay_abi_model import ExecIdentity
from fixed_witness_replay_wait_owner import BoundedWaitOwner, WaitOwnerError
from fixed_witness_replay_ptrace import decode_syscall_info
from test_fixed_witness_replay_ptrace import entry, exit_info
import test_fixed_witness_replay_stop_dispatch as fixture_module


class ReapEpochTest(unittest.TestCase):
    def setUp(self):
        self.f = fixture_module.PostHandshakeDispatchTest()
        self.f.setUp()
        self.dispatch, self.launch, self.ledger = self.f.owner, self.f.launch, self.f.ledger
        self.wait = Mock()
        self.owner = BoundedWaitOwner(self.dispatch, waitpid=self.wait, sleep=Mock())
        self.birth, self.wire = 1, self.f.wire

    def execute(self, thread=False):
        if thread:
            self.wire = self.f.exec_thread()
            self.birth = 2
        else:
            self.f.syscall(entry(59))
            self.f.wire.event_tid.return_value = 11
        self.wait.return_value = (11, 0x4057f)
        if thread:
            self.owner._last_birth = 2  # Target the old leader's EXEC event.
        self.assertEqual('event', self.owner._round())
        self.wire.syscall_info.return_value = decode_syscall_info(*exit_info(0, 0))
        self.wait.return_value = (11, 0x857f)
        self.assertEqual('event', self.owner._round())

    def reap(self, raw=31):
        self.wait.return_value = (11, raw)
        self.owner.run()
        record, = self.owner.settled_reaps
        return record

    def test_leader_reap_records_installed_epoch_without_extra_kernel_work(self):
        self.execute()
        self.wire.reset_mock()
        self.wait.reset_mock()
        record = self.reap()
        self.assertEqual(ExecIdentity(1, 1, 11, 1), record.exec_identity)
        self.assertIsNone(self.owner.require_exec_reap(record, record.exec_identity))
        self.owner.require_exec_reap(record, record.exec_identity)
        self.wait.assert_called_once()
        self.assertEqual([], self.wire.mock_calls)

    def test_nonleader_exec_reap_keeps_executor_epoch_and_original_group(self):
        self.execute(thread=True)
        record = self.reap()
        self.assertEqual(ExecIdentity(2, 1, 11, 1), record.exec_identity)
        self.assertEqual('exec-replaced', self.ledger.identity(1).terminal)
        self.assertEqual({2: 1}, self.dispatch._exec_generations)
        self.owner.require_exec_reap(record, record.exec_identity)

    def test_repeated_exec_keeps_latest_installed_generation(self):
        self.execute()
        self.execute()
        record = self.reap()
        self.assertEqual(2, record.exec_identity.generation)
        self.owner.require_exec_reap(record, ExecIdentity(1, 1, 11, 2))

    def test_pre_exec_reap_has_no_inferred_epoch(self):
        record = self.reap()
        self.assertIsNone(record.exec_identity)
        self.owner.require_settled_reap(record)
        with self.assertRaises(WaitOwnerError):
            self.owner.require_exec_reap(record, ExecIdentity(1, 1, 11, 1))
        self.assertEqual((record,), self.owner.settled_reaps)

    def test_terminal_bits_are_not_sigsys_or_filter_authority(self):
        for raw in (0, 7 << 8, 9, 31, 31 | 0x80):
            self.setUp()
            self.execute()
            record = self.reap(raw)
            self.assertEqual(raw, record.raw)
            self.owner.require_exec_reap(record, record.exec_identity)

    def test_wrong_identity_fields_or_types_latch_without_erasing_real_reap(self):
        for field, value in (('birth', 2), ('group_birth', 2), ('tid', 12),
                             ('generation', 2), ('generation', True)):
            self.setUp()
            self.execute()
            record = self.reap()
            with self.assertRaises(WaitOwnerError):
                self.owner.require_exec_reap(record, replace(record.exec_identity, **{field: value}))
            with self.assertRaises(WaitOwnerError):
                self.owner.require_exec_reap(record, record.exec_identity)
            self.assertEqual(1, self.ledger.totals.live)
            self.assertEqual((record,), self.owner.settled_reaps)

    def test_none_or_tuple_cannot_supply_exec_identity(self):
        for value in (None, (1, 1, 11, 1)):
            self.setUp()
            self.execute()
            record = self.reap()
            with self.assertRaises(WaitOwnerError):
                self.owner.require_exec_reap(record, value)

    def test_copied_record_cannot_promote_external_epoch(self):
        self.execute()
        record = self.reap()
        with self.assertRaises(WaitOwnerError):
            self.owner.require_exec_reap(replace(record), record.exec_identity)
        self.assertEqual((record,), self.owner.settled_reaps)

    def test_epoch_changed_during_wait_is_rejected_after_real_settlement(self):
        for value in (None, 2, True):
            self.setUp()
            self.execute()
            def wait(tid, options):
                self.dispatch._exec_generations[1] = value
                return (11, 31)
            self.wait.side_effect = wait
            with self.assertRaises(WaitOwnerError):
                self.owner.run()
            self.assertEqual((), self.owner.settled_reaps)
            self.assertEqual(1, self.ledger.totals.live)
            self.assertEqual('reaped', self.ledger.identity(1).terminal)
            self.assertEqual(31, self.owner.last_wait.raw)

    def test_post_settlement_epoch_drift_cannot_refresh_snapshot(self):
        self.execute()
        original = self.launch.dispatcher.reaped
        def settle(*args):
            original(*args)
            self.dispatch._exec_generations[1] = 2
        self.launch.dispatcher.reaped = settle
        with self.assertRaises(WaitOwnerError):
            self.reap()
        self.assertEqual((), self.owner.settled_reaps)
        self.assertEqual(1, self.ledger.totals.live)

    def test_later_epoch_drift_revokes_epoch_gate_but_preserves_diagnostics(self):
        for value in (None, 2, True):
            self.setUp()
            self.execute()
            record = self.reap()
            self.dispatch._exec_generations[1] = value
            with self.assertRaises(WaitOwnerError):
                self.owner.require_exec_reap(record, record.exec_identity)
            self.assertEqual(ExecIdentity(1, 1, 11, 1), record.exec_identity)

    def test_malformed_installed_epoch_stops_before_wait(self):
        for value in (0, -1, True, 2**64, '1'):
            self.setUp()
            self.dispatch._exec_generations[1] = value
            with self.assertRaises(WaitOwnerError):
                self.owner.run()
            self.wait.assert_not_called()
            self.assertEqual(2, self.ledger.totals.live)

    def test_later_shared_gate_failure_revokes_epoch_record(self):
        for component in ('reader', 'policy', 'owner', 'deadline'):
            self.setUp()
            self.execute()
            record = self.reap()
            if component == 'deadline':
                self.f.clock.return_value = 100.0
            else:
                target = {'reader': self.launch.reader, 'policy': self.launch.dispatcher.lifecycle.policy,
                          'owner': self.owner}[component]
                target.stopped = 'later failure'
            with self.assertRaises(ValueError):
                self.owner.require_exec_reap(record, record.exec_identity)
            self.assertEqual((record,), self.owner.settled_reaps)

    def test_echild_after_exec_never_creates_epoch_reap(self):
        self.execute()
        self.wait.side_effect = ChildProcessError(errno.ECHILD, 'gone')
        with self.assertRaises(OSError):
            self.owner.run()
        self.assertEqual((), self.owner.settled_reaps)
        self.assertEqual(2, self.ledger.totals.live)


if __name__ == '__main__':
    unittest.main()
