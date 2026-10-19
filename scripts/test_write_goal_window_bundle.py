"""Synthetic disk/CLI checks; no measured session or JVM execution."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

import analyze_heart_baseline as baseline
import analyze_heart_paired as paired
from test_goal_window_envelopes import inputs, PROTOCOL, PROTOCOL_BYTES
from write_goal_window_bundle import json_bytes, write_bundle


class BundleTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.output = self.root / "new-bundle"
        records, metadata = inputs()
        # Deliberately retain noncanonical whitespace to test exact byte binding.
        self.records = b" \n" + json_bytes(records)
        self.metadata = json_bytes(metadata) + b"\n"

    def write(self, records=None, metadata=None, protocol=PROTOCOL_BYTES):
        return write_bundle(self.records if records is None else records,
                            self.metadata if metadata is None else metadata,
                            protocol, self.output)

    def test_complete_bundle_exact_bytes_hashes_and_frozen_consumers(self):
        result = self.write()
        self.assertFalse((self.output / "INCOMPLETE").exists())
        self.assertEqual(json.loads((self.output / "manifest.json").read_bytes()), result)
        self.assertEqual(set(p.name for p in self.output.iterdir()), {*result["files"], "manifest.json"})
        for name, raw in (("records.json", self.records), ("metadata.json", self.metadata),
                          ("protocol.json", PROTOCOL_BYTES)):
            self.assertEqual((self.output / name).read_bytes(), raw)
        for name, binding in result["files"].items():
            raw = (self.output / name).read_bytes()
            self.assertEqual(binding, {"sha256": hashlib.sha256(raw).hexdigest(), "bytes": len(raw)})
        for group in ("development", "calibration"):
            envelope = json.loads((self.output / (group + ".json")).read_bytes())
            self.assertFalse(paired.analyze(envelope, PROTOCOL_BYTES)["eligible_for_runtime_validation"])
        baseline.analyze((self.output / "baseline-derived.csv").read_text(encoding="utf-8"), PROTOCOL)
        self.assertTrue(result["derived"])
        self.assertFalse(result["provenance_authenticated"])
        self.assertFalse(result["eligible_for_runtime_validation"])

    def test_measured_label_cannot_authenticate_or_authorize(self):
        metadata = json.loads(self.metadata)
        metadata["evidence_kind"] = "measured"
        report = self.write(metadata=json_bytes(metadata))
        self.assertEqual(report["evidence_kind"], "measured")
        self.assertFalse(report["provenance_authenticated"])
        self.assertFalse(report["eligible_for_runtime_validation"])

    def test_invalid_inputs_leave_no_directory(self):
        for raw in (b"[]", b"{", b'[{"arm":"baseline","arm":"candidate"}]',
                    b"[NaN]", b"[Infinity]", b"\xff"):
            with self.subTest(raw=raw):
                with self.assertRaises(ValueError): self.write(records=raw)
                self.assertFalse(self.output.exists())
        with self.assertRaises(ValueError): self.write(protocol=PROTOCOL_BYTES + b" ")
        self.assertFalse(self.output.exists())

    def test_missing_or_reordered_games_leave_no_directory(self):
        records = json.loads(self.records)
        for invalid in (records[:-1], records[::-1]):
            with self.assertRaises(ValueError): self.write(records=json_bytes(invalid))
            self.assertFalse(self.output.exists())

    def test_existing_directory_or_file_unchanged(self):
        self.output.mkdir()
        sentinel = self.output / "sentinel"
        sentinel.write_bytes(b"prior evidence")
        with self.assertRaises(FileExistsError): self.write()
        self.assertEqual(sentinel.read_bytes(), b"prior evidence")
        self.assertEqual(list(self.output.iterdir()), [sentinel])
        sentinel.unlink()
        self.output.rmdir()
        self.output.write_bytes(b"prior file")
        with self.assertRaises(FileExistsError): self.write()
        self.assertEqual(self.output.read_bytes(), b"prior file")

    def test_complete_bundle_retry_does_not_change_any_bytes(self):
        self.write()
        before = {p.name: p.read_bytes() for p in self.output.iterdir()}
        with self.assertRaises(FileExistsError): self.write()
        self.assertEqual({p.name: p.read_bytes() for p in self.output.iterdir()}, before)

    def test_write_failure_retains_incomplete_prefix_and_rejects_retry(self):
        original = Path.open

        def fail(path, *args, **kwargs):
            if path.name == "development.json":
                raise OSError("injected disk failure")
            return original(path, *args, **kwargs)

        with patch.object(Path, "open", fail):
            with self.assertRaisesRegex(OSError, "injected disk failure"): self.write()
        self.assertTrue((self.output / "INCOMPLETE").is_file())
        self.assertFalse((self.output / "manifest.json").exists())
        self.assertEqual((self.output / "records.json").read_bytes(), self.records)
        before = {p.name: p.read_bytes() for p in self.output.iterdir()}
        with self.assertRaises(FileExistsError): self.write()
        self.assertEqual({p.name: p.read_bytes() for p in self.output.iterdir()}, before)

    def test_marker_removal_failure_does_not_claim_completion(self):
        original = Path.unlink

        def fail(path, *args, **kwargs):
            if path.name == "INCOMPLETE":
                raise OSError("injected marker failure")
            return original(path, *args, **kwargs)

        with patch.object(Path, "unlink", fail):
            with self.assertRaisesRegex(OSError, "injected marker failure"): self.write()
        self.assertTrue((self.output / "manifest.json").is_file())
        self.assertTrue((self.output / "INCOMPLETE").is_file())

    def test_cli_success_then_no_overwrite_and_invalid_input_status_two(self):
        for name, raw in (("records", self.records), ("metadata", self.metadata), ("protocol", PROTOCOL_BYTES)):
            (self.root / name).write_bytes(raw)
        args = [sys.executable, str(Path(__file__).with_name("write_goal_window_bundle.py"))]
        for name in ("records", "metadata", "protocol"):
            args.extend(["--" + name, str(self.root / name)])
        args.extend(["--output", str(self.output)])
        first = subprocess.run(args, capture_output=True, text=True, timeout=30)
        self.assertEqual(first.returncode, 0, first.stderr)
        self.assertFalse(json.loads(first.stdout)["provenance_authenticated"])
        before = (self.output / "manifest.json").read_bytes()
        retry = subprocess.run(args, capture_output=True, text=True, timeout=30)
        self.assertEqual(retry.returncode, 2, retry.stderr)
        self.assertNotIn("Traceback", retry.stderr)
        self.assertEqual((self.output / "manifest.json").read_bytes(), before)
        (self.root / "records").write_bytes(b"[NaN]")
        invalid = subprocess.run(args[:-1] + [str(self.root / "invalid")], capture_output=True, text=True, timeout=30)
        self.assertEqual(invalid.returncode, 2, invalid.stderr)
        self.assertNotIn("Traceback", invalid.stderr)
        self.assertFalse((self.root / "invalid").exists())


if __name__ == "__main__":
    unittest.main()
