"""Exactly eight artificial metadata cases; no real study reservation or process launch."""
import hashlib
import os
from pathlib import Path
import sys
import tempfile
from threading import Barrier, Thread
import time
import unittest
from unittest.mock import patch

import fixed_witness_replay_registration as registration
from fixed_witness_replay_quota import ByteQuota, OutputLimitError


class RegistrationTest(unittest.TestCase):
    def setUp(self):
        self.base = Path(self.enterContext(tempfile.TemporaryDirectory())).resolve()
        self.checkout = self.base / 'checkout'
        self.registry = self.base / 'registry'
        self.checkout.mkdir()
        self.registry.mkdir()
        self.value = dict(schema=registration.SCHEMA, study_key='synthetic-study',
                          session_id='synthetic-session', implementation_sha='a' * 40,
                          input_sha256='b' * 64, compatibility_sha256='c' * 64,
                          command=['/synthetic/java', 'synthetic.Main'],
                          execution_location=str(self.checkout), limits=dict(registration.LIMITS),
                          **dict.fromkeys(registration.FLAGS, False))

    def retain(self, quota=None):
        data = registration.encode_intent(self.value)
        return registration.retain_intent(self.registry, self.checkout, data,
                                          hashlib.sha256(data).hexdigest(), quota or ByteQuota())

    def linux(self):
        if not sys.platform.startswith('linux'):
            self.skipTest('Linux nofollow/directory-fsync persistence required')

    def test_canonical_bytes_are_owned_and_decode_consistently(self):
        data = registration.encode_intent(self.value)
        self.assertEqual(self.value, registration.decode_intent(data))
        self.value['command'].append('later')
        self.assertNotIn(b'later', data)
        self.assertTrue(data.endswith(b'\n') and not data.endswith(b'\n\n'))

    def test_duplicate_field_never_charges_or_creates(self):
        data = registration.encode_intent(self.value).replace(b'{', b'{"schema":"duplicate",', 1)
        quota = ByteQuota()
        with self.assertRaisesRegex(ValueError, 'duplicate'):
            registration.retain_intent(self.registry, self.checkout, data,
                                        hashlib.sha256(data).hexdigest(), quota)
        self.assertEqual(0, quota.charged)
        self.assertEqual([], list(self.registry.iterdir()))

    def test_changed_original_limit_never_charges_or_creates(self):
        data = registration.encode_intent(self.value).replace(b'"max_processes":16', b'"max_processes":17')
        quota = ByteQuota()
        with self.assertRaisesRegex(ValueError, 'limits'):
            registration.retain_intent(self.registry, self.checkout, data,
                                        hashlib.sha256(data).hexdigest(), quota)
        self.assertEqual(0, quota.charged)
        self.assertEqual([], list(self.registry.iterdir()))

    def test_insufficient_quota_rejects_before_creation(self):
        self.linux()
        data = registration.encode_intent(self.value)
        quota = ByteQuota(len(data) - 1)
        with self.assertRaises(OutputLimitError):
            self.retain(quota)
        self.assertEqual(len(data) - 1, quota.remaining)
        self.assertEqual([], list(self.registry.iterdir()))

    def test_success_and_two_verifications_charge_exactly_once(self):
        self.linux()
        quota = ByteQuota()
        receipt = self.retain(quota)
        self.assertIs(receipt, registration.verify_intent(receipt))
        self.assertIs(receipt, registration.verify_intent(receipt))
        self.assertEqual('INTENT_RETAINED', receipt.state)
        self.assertEqual(len(receipt.canonical_bytes), quota.charged)
        self.assertEqual(receipt.canonical_bytes,
                         (self.registry / 'synthetic-study.intent.json').read_bytes())

    def test_two_sessions_compete_for_one_permanent_logical_key(self):
        self.linux()
        barrier, outcomes, quota = Barrier(2), [], ByteQuota()
        deadline = time.monotonic() + 5

        def compete(session):
            try:
                value = dict(self.value, session_id=session)
                data = registration.encode_intent(value)
                barrier.wait(timeout=max(.001, deadline - time.monotonic()))
                outcomes.append(registration.retain_intent(self.registry, self.checkout, data,
                                hashlib.sha256(data).hexdigest(), quota))
            except Exception as error:
                outcomes.append(error)

        workers = [Thread(target=compete, args=('synthetic-session-' + str(i),)) for i in range(2)]
        for worker in workers:
            worker.start()
        for worker in workers:
            worker.join(timeout=max(0, deadline - time.monotonic()))
        self.assertTrue(all(not worker.is_alive() for worker in workers))
        self.assertEqual(1, sum(type(result) is registration.IntentReceipt for result in outcomes))
        self.assertEqual(1, sum(isinstance(result, FileExistsError) for result in outcomes))
        receipt = next(result for result in outcomes if type(result) is registration.IntentReceipt)
        self.assertIs(receipt, registration.verify_intent(receipt))

    def test_failed_write_keeps_prefix_and_same_key_cannot_retry(self):
        self.linux()
        quota, original = ByteQuota(), os.write

        def fail_after_prefix(descriptor, data):
            original(descriptor, data[:7])
            raise OSError('artificial write failure')

        with patch.object(registration.os, 'write', side_effect=fail_after_prefix):
            with self.assertRaisesRegex(OSError, 'artificial write failure'):
                self.retain(quota)
        path = self.registry / 'synthetic-study.intent.json'
        self.assertEqual(registration.encode_intent(self.value)[:7], path.read_bytes())
        with self.assertRaises(FileExistsError):
            self.retain(quota)
        self.assertEqual(2 * len(registration.encode_intent(self.value)), quota.charged)
        self.assertEqual(7, path.stat().st_size)

    def test_post_registration_replacement_is_rejected_without_rewrite(self):
        self.linux()
        receipt = self.retain()
        path = self.registry / 'synthetic-study.intent.json'
        replacement = self.registry / 'replacement'
        replacement.write_bytes(b'artificial replacement')
        replacement.replace(path)
        with self.assertRaisesRegex(ValueError, 'identity'):
            registration.verify_intent(receipt)
        self.assertEqual(b'artificial replacement', path.read_bytes())


if __name__ == '__main__':
    unittest.main()
