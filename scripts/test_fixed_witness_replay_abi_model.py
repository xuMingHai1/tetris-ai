"""Finite pure-model cases; never authenticate proc or run an ABI probe."""
from dataclasses import FrozenInstanceError, replace
import struct
import unittest
from unittest.mock import ANY

from fixed_witness_replay_abi_model import AbiModelError, AbiProbeModel, ExecIdentity, ProbeRecord
from fixed_witness_replay_ptrace import PtraceBoundaryError, SyscallInfo, decode_syscall_info


def records():
    return tuple(ProbeRecord(mode, '/frozen/test_fixed_witness_replay_network.py',
                             'a' * 64, '/usr/bin/python3.14', 'b' * 64,
                             ('/usr/bin/python3.14', '-I', '-B',
                              '/frozen/test_fixed_witness_replay_network.py', mode))
                 for mode in ('x32', 'compat'))


def entry(mode='x32'):
    arch, number = (0xc000003e, 0x40000029) if mode == 'x32' else (0x40000003, 102)
    raw = struct.pack('<B3xIQQ7Q', 1, arch, 7, 8, number, *range(6))
    return decode_syscall_info(raw, len(raw))


class AbiModelTest(unittest.TestCase):
    def bound(self, mode='x32'):
        model = AbiProbeModel(records())
        identity = ExecIdentity(2, 2, 100, 1)
        model.bind(mode, identity, records()[0 if mode == 'x32' else 1])
        return model, identity

    def consume(self, model, identity, mode='x32', info=None):
        return model.consume_entry(mode, identity, model.manifest_sha256,
                                   entry(mode) if info is None else info)

    def test_two_modes_consume_once_without_returning_authority(self):
        model = AbiProbeModel(records())
        for i, record in enumerate(records(), 2):
            identity = ExecIdentity(i, i, 100 + i, 1)
            model.bind(record.mode, identity, record)
            self.assertIsNone(self.consume(model, identity, record.mode))
            self.assertEqual('AWAITING_SIGSYS', model.state(record.mode))
        self.assertIsNone(model.stopped)

    def test_exact_two_modes_and_common_source_interpreter(self):
        first, second = records()
        for bad in ([], (), (first,), (first, first), (first, second, second),
                    (first, replace(second, source_sha256='c' * 64)),
                    (first, replace(second, interpreter_sha256='c' * 64))):
            with self.subTest(bad=bad), self.assertRaises(AbiModelError):
                AbiProbeModel(bad)

    def test_manifest_canonical_order_and_all_frozen_fields(self):
        original = AbiProbeModel(records()).manifest_sha256
        self.assertEqual(original, AbiProbeModel(tuple(reversed(records()))).manifest_sha256)
        for field in ('source_sha256', 'interpreter_sha256'):
            changed = tuple(replace(r, **{field: 'c' * 64}) for r in records())
            self.assertNotEqual(original, AbiProbeModel(changed).manifest_sha256)
        for field, value, position in (('source', '/other/probe.py', 3),
                                       ('interpreter', '/other/python', 0)):
            changed = []
            for record in records():
                argv = list(record.argv)
                argv[position] = value
                changed.append(replace(record, **{field: value}, argv=tuple(argv)))
            self.assertNotEqual(original, AbiProbeModel(tuple(changed)).manifest_sha256)

    def test_record_is_immutable_and_rejects_mutable_argv(self):
        first, second = records()
        with self.assertRaises(FrozenInstanceError):
            first.mode = 'compat'
        with self.assertRaises(FrozenInstanceError):
            ExecIdentity(1, 1, 1, 1).generation = 2
        with self.assertRaises(AbiModelError):
            AbiProbeModel((replace(first, argv=list(first.argv)), second))
        with self.assertRaises(AbiModelError):
            AbiProbeModel((replace(first, argv=(ANY,) * 5), second))
        model = AbiProbeModel(records())
        with self.assertRaises(AbiModelError):
            model.bind('x32', ExecIdentity(2, 2, 100, 1), replace(first, argv=(ANY,) * 5))

    def test_lexical_paths_hashes_and_exact_argv(self):
        first, second = records()
        changes = [{'mode': 'other'}, {'source_sha256': 'A' * 64},
                   {'interpreter_sha256': 'x' * 64}, {'source_sha256': 'a' * 63},
                   {'source': '/a/../probe.py'}, {'source': '/a//probe.py'},
                   {'source': '/a/./probe.py'}, {'source': 'relative.py'},
                   {'source': '//host/probe.py'}, {'source': '/a\x00b'},
                   {'interpreter': '/usr/bin/python/'}, {'interpreter': True},
                   {'argv': first.argv + ('extra',)}, {'argv': first.argv[:-1]},
                   {'argv': (first.interpreter, '-B', '-I', first.source, 'x32')}]
        for change in changes:
            with self.subTest(change=change), self.assertRaises(AbiModelError):
                AbiProbeModel((replace(first, **change), second))

    def test_observed_mode_path_hash_and_argv_mismatch_latches(self):
        first = records()[0]
        for field, value in (('mode', 'compat'), ('source', '/other/probe.py'),
                             ('interpreter', '/other/python'),
                             ('source_sha256', 'c' * 64),
                             ('interpreter_sha256', 'c' * 64),
                             ('argv', first.argv + ('extra',))):
            model = AbiProbeModel(records())
            with self.subTest(field=field), self.assertRaises(AbiModelError):
                model.bind('x32', ExecIdentity(2, 2, 100, 1), replace(first, **{field: value}))
            self.assertEqual('UNBOUND', model.state('x32'))
            with self.assertRaises(AbiModelError):
                model.bind('x32', ExecIdentity(2, 2, 100, 1), first)

    def test_unbound_or_raw_boolean_receipt_is_not_a_binding(self):
        for observation in (True, {'authenticated': True}, records()[0].argv):
            model = AbiProbeModel(records())
            with self.assertRaises(AbiModelError):
                model.bind('x32', ExecIdentity(2, 2, 100, 1), observation)
        model = AbiProbeModel(records())
        with self.assertRaises(AbiModelError):
            self.consume(model, ExecIdentity(2, 2, 100, 1))

    def test_invalid_identity_widths_types_and_zero(self):
        for field, values in (('birth', (0, -1, True, 1 << 64)),
                              ('group_birth', (0, False, 1 << 64)),
                              ('tid', (0, 1 << 31, 1.0)),
                              ('generation', (0, True, 1 << 64))):
            for value in values:
                model = AbiProbeModel(records())
                with self.subTest(field=field, value=value), self.assertRaises(AbiModelError):
                    model.bind('x32', replace(ExecIdentity(2, 2, 100, 1), **{field: value}),
                               records()[0])

    def test_birth_group_tid_exec_generation_and_manifest_must_match(self):
        for field, value in (('birth', 3), ('group_birth', 3), ('tid', 101),
                             ('generation', 2), ('generation', True)):
            model, identity = self.bound()
            with self.subTest(field=field), self.assertRaises(AbiModelError):
                self.consume(model, replace(identity, **{field: value}))
            self.assertEqual('BOUND', model.state('x32'))
        model, identity = self.bound()
        with self.assertRaises(AbiModelError):
            model.consume_entry('x32', identity, 'c' * 64, entry())

    def test_second_binding_and_cross_mode_identity_reuse(self):
        for mode, identity in (('x32', ExecIdentity(3, 3, 101, 1)),
                               ('compat', ExecIdentity(2, 2, 101, 2)),
                               ('compat', ExecIdentity(3, 3, 100, 1))):
            model, _ = self.bound()
            with self.subTest(mode=mode), self.assertRaises(AbiModelError):
                model.bind(mode, identity, records()[0 if mode == 'x32' else 1])

    def test_second_entry_and_cross_mode_numbers_never_match(self):
        for mode in ('x32', 'compat'):
            model, identity = self.bound(mode)
            self.consume(model, identity, mode)
            with self.assertRaises(AbiModelError):
                self.consume(model, identity, mode)
            model, identity = self.bound(mode)
            with self.assertRaises(AbiModelError):
                self.consume(model, identity, mode, entry('compat' if mode == 'x32' else 'x32'))

    def test_creation_unknown_arch_and_number_rejected_without_masking(self):
        for mode in ('x32', 'compat'):
            for arch, number in ((0xc000003e, 41), (0x40000003, 2),
                                 (0x40000003, 120), (0x40000003, 190),
                                 (0x40000003, 435), (0xc000003e, 0x40000039),
                                 (0xc000003e, 0x40000038), (0xc000003e, 0x4000003a),
                                 (0xc000003e, 0x400001b3), (0, 102),
                                 (0xc000003e, (1 << 32) + 0x40000029)):
                model, identity = self.bound(mode)
                with self.subTest(mode=mode, arch=arch, nr=number), self.assertRaises(AbiModelError):
                    self.consume(model, identity, mode, replace(entry(mode), arch=arch, number=number))

    def test_only_complete_typed_entry_is_consumed(self):
        changes = [{'operation': 2}, {'operation': True}, {'number': True},
                   {'arch': 1 << 32}, {'number': -1}, {'number': 1 << 64},
                   {'arguments': (0,) * 5}, {'arguments': [0] * 6},
                   {'arguments': (False,) * 6}, {'arguments': (1 << 64,) * 6},
                   {'instruction_pointer': -1}, {'stack_pointer': True},
                   {'result': 0}, {'is_error': False}]
        for change in changes:
            model, identity = self.bound()
            with self.subTest(change=change), self.assertRaises(AbiModelError):
                self.consume(model, identity, info=replace(entry(), **change))
            self.assertEqual('BOUND', model.state('x32'))
        model, identity = self.bound()
        with self.assertRaises(AbiModelError):
            self.consume(model, identity, info={'operation': 1})

    def test_truncated_wire_rejected_before_model_without_implicit_padding(self):
        raw = struct.pack('<B3xIQQ7Q', 1, 0xc000003e, 0, 0, 0x40000029, *range(6))
        for size in (0, 23, 24, 79):
            with self.subTest(size=size), self.assertRaises(PtraceBoundaryError):
                decode_syscall_info(raw[:size], size)

    def test_exec_revokes_bound_slot_without_rebinding(self):
        model, identity = self.bound()
        self.assertIsNone(model.revoke_exec('x32', identity))
        self.assertEqual('REVOKED', model.state('x32'))
        self.assertIsNone(model.stopped)
        with self.assertRaises(AbiModelError):
            model.bind('x32', replace(identity, generation=2), records()[0])

    def test_awaiting_exit_exec_creation_and_failed_resume_remain_consumed(self):
        for action in ('entry', 'exit', 'exec', 'creation', 'resume-failed'):
            model, identity = self.bound()
            self.consume(model, identity)
            with self.subTest(action=action), self.assertRaises(AbiModelError):
                if action == 'exec':
                    model.revoke_exec('x32', identity)
                elif action == 'resume-failed':
                    model.fail('uncertain resume')
                else:
                    info = entry() if action == 'entry' else replace(
                        entry(), operation=2 if action == 'exit' else 1, number=56)
                    self.consume(model, identity, info=info)
            self.assertEqual('AWAITING_SIGSYS', model.state('x32'))
            first = model.stopped
            with self.assertRaises(AbiModelError):
                model.fail('later failure')
            self.assertEqual(first, model.stopped)

    def test_preexisting_failure_blocks_even_exact_entry_and_other_slot(self):
        model, identity = self.bound()
        with self.assertRaises(AbiModelError):
            model.fail('shared stop budget exhausted')
        with self.assertRaisesRegex(AbiModelError, 'shared stop budget'):
            self.consume(model, identity)
        self.assertEqual('BOUND', model.state('x32'))
        with self.assertRaisesRegex(AbiModelError, 'shared stop budget'):
            model.bind('compat', ExecIdentity(3, 3, 101, 1), records()[1])

    def test_model_match_does_not_relax_native_guard(self):
        for mode in ('x32', 'compat'):
            model, identity = self.bound(mode)
            self.consume(model, identity, mode)
            with self.assertRaises(PtraceBoundaryError):
                entry(mode).require_native(1)


if __name__ == '__main__':
    unittest.main()
