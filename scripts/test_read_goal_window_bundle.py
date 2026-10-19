"""Synthetic bundle corruption checks; no JVM or measured session."""
import copy
import hashlib
import json
from pathlib import Path
import stat
import subprocess
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

from read_goal_window_bundle import read_bundle, ordinary
from test_goal_window_envelopes import inputs, PROTOCOL_BYTES
from write_goal_window_bundle import json_bytes, write_bundle


class ReaderTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name) / "bundle"
        records, metadata = inputs()
        self.manifest = write_bundle(b" \n" + json_bytes(records), json_bytes(metadata) + b" \n",
                                     PROTOCOL_BYTES, self.root)

    def save_manifest(self, manifest=None):
        (self.root / "manifest.json").write_bytes(json_bytes(self.manifest if manifest is None else manifest))

    def replace_bound_file(self, name, raw):
        (self.root / name).write_bytes(raw)
        self.manifest["files"][name] = {"bytes": len(raw), "sha256": hashlib.sha256(raw).hexdigest()}
        self.save_manifest()

    def test_roundtrip_exact_bytes_no_mutation_and_no_authentication(self):
        before = {p.name: p.read_bytes() for p in self.root.iterdir()}
        result = read_bundle(self.root)
        self.assertEqual(result["manifest"], self.manifest)
        self.assertEqual(result["files"], {k: v for k, v in before.items() if k != "manifest.json"})
        self.assertEqual(before, {p.name: p.read_bytes() for p in self.root.iterdir()})
        self.assertFalse(result["manifest"]["provenance_authenticated"])
        result["manifest"]["files"].clear()
        self.assertEqual(read_bundle(self.root)["manifest"], self.manifest)

    def test_missing_and_unexpected_files_including_incomplete_marker(self):
        target = self.root / "development.json"
        raw = target.read_bytes()
        target.unlink()
        with self.assertRaises(ValueError): read_bundle(self.root)
        target.write_bytes(raw)
        for name in ("INCOMPLETE", "unexpected"):
            with self.subTest(name=name):
                (self.root / name).write_bytes(b"prefix")
                with self.assertRaises(ValueError): read_bundle(self.root)
                (self.root / name).unlink()

    def test_missing_manifest_or_directory_in_place_of_file(self):
        manifest = self.root / "manifest.json"
        manifest.unlink()
        with self.assertRaises(ValueError): read_bundle(self.root)
        manifest.mkdir()
        with self.assertRaises(ValueError): read_bundle(self.root)

    def test_changed_bytes_and_wrong_size_or_hash(self):
        target = self.root / "records.json"
        original = target.read_bytes()
        target.write_bytes(original + b" ")
        with self.assertRaisesRegex(ValueError, "bytes/hash"): read_bundle(self.root)
        target.write_bytes(original)
        for key, value in (("bytes", len(original) + 1), ("sha256", "0" * 64),
                           ("bytes", True), ("bytes", float(len(original))), ("bytes", -1)):
            with self.subTest(key=key, value=value):
                manifest = copy.deepcopy(self.manifest)
                manifest["files"]["records.json"][key] = value
                self.save_manifest(manifest)
                with self.assertRaises(ValueError): read_bundle(self.root)

    def test_manifest_layout_and_claims_rejected(self):
        for key, value in (("schema", "v2"), ("derived", 1),
                           ("provenance_authenticated", 0), ("eligible_for_runtime_validation", True),
                           ("evidence_kind", "measured"), ("files", [])):
            with self.subTest(key=key):
                manifest = copy.deepcopy(self.manifest)
                manifest[key] = value
                self.save_manifest(manifest)
                with self.assertRaises(ValueError): read_bundle(self.root)
        manifest = copy.deepcopy(self.manifest)
        manifest["files"]["../outside"] = {"bytes": 0, "sha256": "0" * 64}
        self.save_manifest(manifest)
        with self.assertRaises(ValueError): read_bundle(self.root)
        self.save_manifest({**self.manifest, "extra": True})
        with self.assertRaises(ValueError): read_bundle(self.root)

    def test_invalid_manifest_json_and_duplicate_keys(self):
        for raw in (b"null", b"[]", b"{", b"\xff", b"NaN", b'{"schema":1,"schema":2}'):
            with self.subTest(raw=raw):
                (self.root / "manifest.json").write_bytes(raw)
                with self.assertRaises(ValueError): read_bundle(self.root)

    def test_rehashed_derived_envelopes_and_csv_must_match_original_inputs(self):
        originals = {p.name: p.read_bytes() for p in self.root.iterdir()}
        for name in ("development.json", "calibration.json", "baseline-derived.csv"):
            with self.subTest(name=name):
                self.replace_bound_file(name, originals[name] + b" ")
                with self.assertRaisesRegex(ValueError, "derived"): read_bundle(self.root)
                self.replace_bound_file(name, originals[name])

    def test_rehashed_invalid_source_records_metadata_and_protocol_rejected(self):
        originals = {p.name: p.read_bytes() for p in self.root.iterdir()}
        for name, raw in (("records.json", b"[]"), ("metadata.json", b"{}"),
                          ("protocol.json", PROTOCOL_BYTES + b" "),
                          ("records.json", b'[{"arm":1,"arm":2}]')):
            with self.subTest(name=name, raw=raw[:20]):
                self.replace_bound_file(name, raw)
                with self.assertRaises(ValueError): read_bundle(self.root)
                self.replace_bound_file(name, originals[name])

    def test_valid_changed_source_rejects_stale_envelopes(self):
        records = json.loads((self.root / "records.json").read_bytes())
        records[0]["game"]["elapsed_game_ms"] += 1
        self.replace_bound_file("records.json", json_bytes(records))
        with self.assertRaisesRegex(ValueError, "derived envelope"): read_bundle(self.root)

    def test_measured_label_preserved_without_authentication(self):
        metadata = json.loads((self.root / "metadata.json").read_bytes())
        metadata["evidence_kind"] = "measured"
        other = self.root.parent / "measured"
        write_bundle((self.root / "records.json").read_bytes(), json_bytes(metadata), PROTOCOL_BYTES, other)
        manifest = read_bundle(other)["manifest"]
        self.assertEqual(manifest["evidence_kind"], "measured")
        self.assertFalse(manifest["provenance_authenticated"])
        self.assertFalse(manifest["eligible_for_runtime_validation"])

    def test_link_and_reparse_checks_without_symlink_privileges(self):
        for mode, attributes in ((stat.S_IFLNK, 0), (stat.S_IFREG, stat.FILE_ATTRIBUTE_REPARSE_POINT)):
            with patch.object(Path, "lstat", return_value=SimpleNamespace(
                    st_mode=mode, st_file_attributes=attributes)):
                with self.assertRaisesRegex(ValueError, "linked"): ordinary(self.root / "records.json")

    def test_cli_success_and_corruption_status_two_without_changing_evidence(self):
        args = [sys.executable, str(Path(__file__).with_name("read_goal_window_bundle.py")),
                "--bundle", str(self.root)]
        result = subprocess.run(args, capture_output=True, text=True, timeout=30)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue(json.loads(result.stdout)["internally_consistent"])
        self.assertFalse(json.loads(result.stdout)["provenance_authenticated"])
        (self.root / "INCOMPLETE").write_bytes(b"failed prefix")
        before = {p.name: p.read_bytes() for p in self.root.iterdir()}
        result = subprocess.run(args, capture_output=True, text=True, timeout=30)
        self.assertEqual(result.returncode, 2, result.stderr)
        self.assertNotIn("Traceback", result.stderr)
        self.assertEqual(before, {p.name: p.read_bytes() for p in self.root.iterdir()})

    def test_cli_deeply_nested_json_rejected_without_traceback(self):
        args = [sys.executable, str(Path(__file__).with_name("read_goal_window_bundle.py")),
                "--bundle", str(self.root)]
        original_manifest = (self.root / "manifest.json").read_bytes()
        nested = b"[" * 20000 + b"0" + b"]" * 20000
        for name in ("manifest.json", "records.json"):
            with self.subTest(name=name):
                (self.root / "manifest.json").write_bytes(original_manifest)
                if name == "manifest.json":
                    (self.root / name).write_bytes(nested)
                else:
                    self.replace_bound_file(name, nested)
                before = {p.name: p.read_bytes() for p in self.root.iterdir()}
                result = subprocess.run(args, capture_output=True, text=True, timeout=30)
                self.assertEqual(result.returncode, 2, result.stderr)
                self.assertNotIn("Traceback", result.stderr)
                self.assertEqual(before, {p.name: p.read_bytes() for p in self.root.iterdir()})


if __name__ == "__main__":
    unittest.main()
