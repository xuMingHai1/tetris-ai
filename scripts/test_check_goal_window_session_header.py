"""Synthetic producer-contract and corruption tests; no JVM/build/population."""
import copy
import hashlib
import json
from pathlib import Path, PurePosixPath, PureWindowsPath
import re
import subprocess
import sys
import tempfile
import unittest

import check_goal_window_session_header as check
from write_goal_window_bundle import json_bytes


def fixture(root="/synthetic/checkout"):
    path = PureWindowsPath(root) if PureWindowsPath(root).drive else PurePosixPath(root)
    classes, loaded = {}, {}
    for suffix in check.CRITICAL:
        name = "xyz.xuminghai.tetris." + suffix
        base = "target/test-classes" if "GoalWindow" in suffix else "target/classes"
        classes[base + "/" + name.replace(".", "/") + ".class"] = "a" * 64
        loaded[name] = {"origin": str(path / base), "sha256": "a" * 64}
    manifest = {"schema": "goal-window-build-v1", "root": root, "source_sha": "b" * 40,
                "clean_checkout": True, "protocol_sha256": check.PROTOCOL_SHA,
                "config_sha256": "c" * 64, "production_guard": {"production_diff_verified": True},
                "jvm_args": [], "class_files": classes, "extra_retained_field": 27}
    raw = json_bytes(manifest)
    header = {"schema": "goal-window-raw-v1", "sequence": 1, "type": "provenance_verified",
              "data": {"manifest_sha256": hashlib.sha256(raw).hexdigest(), "manifest": manifest,
                       "loaded_classes": loaded}}
    return raw, header


class HeaderTests(unittest.TestCase):
    def verify(self, manifest, header, expected=None):
        return check.check_header(json_bytes(header), manifest,
                                  hashlib.sha256(manifest).hexdigest() if expected is None else expected)

    def test_linux_and_windows_paths_without_disk_or_authentication(self):
        for root in ("/synthetic/checkout", r"C:\synthetic\checkout"):
            manifest, header = fixture(root)
            before = copy.deepcopy(header)
            result = self.verify(manifest, header)
            self.assertTrue(result["header_consistent"])
            self.assertEqual(result["critical_classes"], 19)
            for key in ("provenance_authenticated", "raw_completeness_verified",
                        "eligible_for_runtime_validation"):
                self.assertIs(result[key], False)
            self.assertEqual(header, before)

    def test_manifest_bytes_and_external_digest_are_independent(self):
        manifest, header = fixture()
        for supplied, expected in ((manifest + b" ", hashlib.sha256(manifest).hexdigest()),
                                   (manifest, "0" * 64), (manifest, "invalid")):
            with self.assertRaises(ValueError): self.verify(supplied, header, expected)
        with self.assertRaisesRegex(ValueError, "header manifest digest"):
            self.verify(manifest + b" ", header)

    def test_embedded_manifest_tampering_including_json_numeric_types(self):
        for key, value in (("source_sha", "d" * 40), ("extra_retained_field", 27.0),
                           ("clean_checkout", 1)):
            manifest, header = fixture()
            header["data"]["manifest"][key] = value
            with self.assertRaisesRegex(ValueError, "embedded manifest"):
                self.verify(manifest, header)

    def test_first_event_identity_and_fields(self):
        for key, value in (("sequence", True), ("sequence", 1.0), ("sequence", 2),
                           ("type", "session_started"), ("schema", "other"), ("extra", 1)):
            manifest, header = fixture()
            header[key] = value
            with self.assertRaises(ValueError): self.verify(manifest, header)

    def test_missing_extra_and_changed_loaded_classes(self):
        for mode in ("missing", "extra", "hash", "origin", "field"):
            manifest, header = fixture()
            loaded = header["data"]["loaded_classes"]
            name = next(iter(loaded))
            if mode == "missing": del loaded[name]
            elif mode == "extra": loaded["injected.Class"] = loaded[name]
            elif mode == "hash": loaded[name]["sha256"] = "d" * 64
            elif mode == "origin": loaded[name]["origin"] = "/other/target/test-classes"
            else: loaded[name]["extra"] = True
            with self.assertRaises(ValueError): self.verify(manifest, header)

    def test_invalid_manifest_claims_even_with_rebound_digest(self):
        for key, value in (("source_sha", "bad"), ("config_sha256", "bad"),
                           ("protocol_sha256", "0" * 64), ("clean_checkout", 1),
                           ("jvm_args", ["-javaagent:x"]), ("root", "relative"),
                           ("root", "/root/../checkout"),
                           ("production_guard", {"production_diff_verified": 1})):
            raw, header = fixture()
            manifest = header["data"]["manifest"]
            manifest[key] = value
            raw = json_bytes(manifest)
            header["data"]["manifest_sha256"] = hashlib.sha256(raw).hexdigest()
            with self.assertRaises(ValueError): self.verify(raw, header)

    def test_malformed_and_duplicate_json_rejected(self):
        manifest, header = fixture()
        for raw in (b"null", b"[]", b"{", b'{"sequence":1,"sequence":1}',
                    b'{"x":NaN}', b'\xff', b'[' * 2000 + b']' * 2000):
            with self.assertRaises(ValueError):
                check.check_header(raw, manifest, hashlib.sha256(manifest).hexdigest())
        for value in (None, [], {}, {"schema": "goal-window-build-v1"}):
            raw = json_bytes(value)
            header["data"]["manifest"] = value
            header["data"]["manifest_sha256"] = hashlib.sha256(raw).hexdigest()
            with self.assertRaises(ValueError): self.verify(raw, header)

    def test_critical_class_boundary_matches_java_producer(self):
        source = Path(__file__).resolve().parents[1] / (
            "src/test/java/xyz/xuminghai/tetris/ai/benchmark/GoalWindowBuildProvenance.java")
        declaration = source.read_text(encoding="utf-8").split("CRITICAL = List.of(", 1)[1].split(");", 1)[0]
        self.assertEqual(tuple(re.findall(r'"([^"]+)"', declaration)), check.CRITICAL)

    def test_cli_reads_header_only_and_rejects_truncated_line(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest, header = fixture()
            (root / "manifest.json").write_bytes(manifest)
            raw = root / "raw.jsonl"
            first = (json.dumps(header, separators=(",", ":")) + "\n").encode("utf-8")
            raw.write_bytes(first + b"intentionally incomplete remainder\n")
            args = [sys.executable, str(Path(check.__file__)), "--raw", str(raw), "--manifest",
                    str(root / "manifest.json"), "--manifest-sha256", hashlib.sha256(manifest).hexdigest()]
            result = subprocess.run(args, capture_output=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn(b'"raw_completeness_verified": false', result.stdout)
            self.assertEqual(raw.read_bytes(), first + b"intentionally incomplete remainder\n")
            raw.write_bytes(first.rstrip(b"\n"))
            result = subprocess.run(args, capture_output=True)
            self.assertEqual(result.returncode, 2, result.stderr)
            self.assertNotIn(b"Traceback", result.stderr)
            raw.write_bytes(b"[" * 2000 + b"]" * 2000 + b"\n")
            result = subprocess.run(args, capture_output=True)
            self.assertEqual(result.returncode, 2, result.stderr)
            self.assertNotIn(b"Traceback", result.stderr)


if __name__ == "__main__":
    unittest.main()
