"""Synthetic full-session plumbing and failure tests; never launches warmup or games."""
from contextlib import contextmanager, redirect_stderr, redirect_stdout
import copy
import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock

import drive_goal_window as driver
from test_analyze_heart_paired import PROTOCOL_BYTES
from test_audit_goal_window_raw import fixture as raw_fixture, encode
from test_check_goal_window_session_header import fixture as header_fixture
from write_goal_window_bundle import json_bytes


ENVIRONMENT = {"os": "synthetic-linux", "arch": "synthetic-x64", "cpu_count": 2,
               "cpuinfo": "synthetic-cpu", "cpu_affinity": [0, 1], "memory": "synthetic-ram"}


class DriverTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        self.root, self.output = self.directory / "checkout", self.directory / "run"
        self.root.mkdir()
        _, header = header_fixture(str(self.root))
        self.manifest = header["data"]["manifest"]
        self.manifest["jdk"] = {"java.runtime.version": "27-synthetic"}
        self.manifest_bytes = json_bytes(self.manifest)
        self.digest = hashlib.sha256(self.manifest_bytes).hexdigest()
        header["data"]["manifest_sha256"] = self.digest
        self.events = raw_fixture()
        self.events[0] = header
        self.path = self.root / driver.build.MANIFEST
        self.path.parent.mkdir(parents=True)
        self.path.write_bytes(self.manifest_bytes)
        protocol = self.root / driver.build.PROTOCOL
        protocol.parent.mkdir(parents=True)
        protocol.write_bytes(PROTOCOL_BYTES)
        (self.root / "pom.xml").write_text(
            '<project xmlns="http://maven.apache.org/POM/4.0.0">'
            '<properties><javafx.version>27</javafx.version></properties></project>')
        self.preflight = mock.patch.object(driver.supervisor, "preflight", side_effect=self.prepare).start()
        self.launch = mock.patch.object(driver.supervisor, "launch_prepared", side_effect=self.measure).start()
        self.verify = mock.patch.object(driver.build, "verify_build", side_effect=self.verify_manifest).start()
        mock.patch.object(driver.supervisor, "environment", return_value=copy.deepcopy(ENVIRONMENT)).start()
        self.addCleanup(mock.patch.stopall)

    def prepare(self, root, output):
        output.mkdir()
        (output / "build-manifest.json").write_bytes(self.manifest_bytes)
        (output / "build-console.log").write_bytes(b"synthetic retained build log\n")
        return {"status": "VERIFIED", "population_started": False,
                "build": {"manifest": str(self.path), "manifest_sha256": self.digest,
                          "source_sha": self.manifest["source_sha"]}}

    def verify_manifest(self, path, expected):
        if hashlib.sha256(path.read_bytes()).hexdigest() != expected:
            raise ValueError("changed synthetic manifest")
        return copy.deepcopy(self.manifest)

    def measure(self, path, expected, output, *, verify_only):
        self.assertFalse(verify_only)
        self.assertEqual(self.path, path)
        self.assertEqual(self.digest, expected)
        (output / "raw.jsonl").write_bytes(b"".join(encode(self.events)))
        (output / "stdout.log").write_bytes(b"synthetic stdout\n")
        (output / "stderr.log").write_bytes(b"")
        return {"state": "EXITED", "returncode": 0, "timeout_seconds": 2700}

    def collect(self):
        return driver.run(self.root, self.output, collect=True)

    def assert_incomplete(self, report):
        self.assertEqual("INCOMPLETE", report["status"])
        self.assertFalse(report["measurement_complete"])
        self.assertTrue((self.output / "INCOMPLETE").is_file())
        self.assertFalse(report["runtime_permission"])
        self.assertEqual(report, json.loads((self.output / "report.json").read_bytes()))

    def test_full_synthetic_chain_preserves_nanos_clean_non_q_hashes_and_permissions(self):
        report = self.collect()
        self.assertEqual("PROTOTYPE_PASSED", report["status"])
        self.assertTrue(report["measurement_complete"])
        self.assertTrue(report["build_revalidated_before_and_after"])
        self.assertTrue(report["raw_header_bound"])
        self.assertFalse((self.output / "INCOMPLETE").exists())
        raw_bytes = b"".join(encode(self.events))
        self.assertEqual(hashlib.sha256(raw_bytes).hexdigest(), report["raw_sha256"])
        self.assertEqual(raw_bytes, (self.output / "measurement/raw.jsonl").read_bytes())
        bundle = driver.read_bundle(self.output / "bundle")
        development = json.loads(bundle["files"]["development.json"])
        calibration = json.loads(bundle["files"]["calibration.json"])
        self.assertEqual(20, len(development["arms"]["candidate"]["games"]))
        self.assertEqual(60, len(calibration["arms"]["candidate"]["games"]))
        self.assertEqual(2, development["arms"]["candidate"]["games"][0]["first_clean_piece"])
        self.assertEqual(1000, development["arms"]["candidate"]["games"][0]["decision_facts"][0]["decision_nanos"])
        self.assertEqual(100, development["arms"]["baseline"]["warmup"]["decisions"])
        self.assertEqual(development["goal_window_session"], calibration["goal_window_session"])
        result = json.loads((self.output / "prototype-report.json").read_bytes())
        self.assertTrue(result["prototype_gates_pass"])
        self.assertFalse(result["provenance_verified"])
        self.assertFalse(result["runtime_permission"])
        self.assertFalse(bundle["manifest"]["provenance_authenticated"])
        self.assertEqual(hashlib.sha256(bundle["files"]["development.json"]).hexdigest(),
                         result["input_files_sha256"]["development"])
        for name, binding in report["artifacts"].items():
            value = (self.output / name).read_bytes()
            self.assertEqual({"bytes": len(value), "sha256": hashlib.sha256(value).hexdigest()}, binding)

    def test_pure_derivation_retains_synthetic_kind_and_does_not_verify_disk_or_launch(self):
        records, metadata, protocol, audit = driver.derive_session(
            b"".join(encode(self.events)), self.manifest_bytes, self.digest,
            PROTOCOL_BYTES, ENVIRONMENT, "27-synthetic", evidence_kind="synthetic")
        self.assertEqual(160, len(records))
        self.assertEqual("synthetic", metadata["evidence_kind"])
        self.assertEqual(PROTOCOL_BYTES, protocol)
        self.assertFalse(audit["build_provenance_verified"])
        frozen = copy.deepcopy(driver.build.EXPECTED_CONFIG)
        metadata["arms"]["candidate"]["config"]["parameters"]["warmup"]["games"] = 1
        self.assertEqual(frozen, driver.build.EXPECTED_CONFIG)
        self.verify.assert_not_called()
        self.preflight.assert_not_called()
        self.launch.assert_not_called()

    def test_default_cli_only_uses_preflight(self):
        with redirect_stdout(io.StringIO()):
            self.assertEqual(0, driver.main(["--root", str(self.root), "--output", str(self.output)]))
        self.launch.assert_not_called()
        self.verify.assert_not_called()
        self.preflight.assert_called_once_with(self.root, self.output)

    def test_preflight_failure_never_starts_measurement(self):
        self.preflight.side_effect = None
        self.preflight.return_value = {"status": "INCOMPLETE", "population_started": None}
        self.assert_incomplete(self.collect())
        self.launch.assert_not_called()
        self.assertFalse((self.output / "measurement").exists())

    def test_timeout_interrupt_and_nonzero_retain_prefix_without_retry(self):
        for state, code in (("TIMEOUT", -9), ("INTERRUPTED", -15), ("EXITED", 1)):
            self.output = self.directory / state
            def stopped(path, expected, output, **kwargs):
                (output / "raw.jsonl").write_bytes(b"retained partial")
                (output / "stderr.log").write_bytes(b"retained error")
                return {"state": state, "returncode": code, "timeout_seconds": 2700}
            self.launch.side_effect = stopped
            self.assert_incomplete(self.collect())
            self.assertEqual(b"retained partial", (self.output / "measurement/raw.jsonl").read_bytes())
            self.assertFalse((self.output / "bundle").exists())
        self.assertEqual(3, self.launch.call_count)

    def test_truncated_or_incomplete_raw_cannot_create_a_bundle(self):
        self.events.pop()
        self.assert_incomplete(self.collect())
        self.assertFalse((self.output / "bundle").exists())
        self.assertTrue((self.output / "measurement/raw.jsonl").is_file())

    def test_manifest_mutation_after_process_cannot_become_complete(self):
        def changed(*args, **kwargs):
            result = self.measure(*args, **kwargs)
            self.path.write_bytes(self.manifest_bytes + b" ")
            return result
        self.launch.side_effect = changed
        self.assert_incomplete(self.collect())
        self.assertFalse((self.output / "bundle").exists())

    def test_post_process_build_revalidation_failure_stops_before_derivation(self):
        self.verify.side_effect = [copy.deepcopy(self.manifest), ValueError("changed compiled class")]
        self.assert_incomplete(self.collect())
        self.assertFalse((self.output / "bundle").exists())

    def test_foreign_loaded_class_is_rejected_even_after_exit_zero(self):
        loaded = self.events[0]["data"]["loaded_classes"]
        loaded[next(iter(loaded))]["origin"] = "/foreign/target/classes"
        self.assert_incomplete(self.collect())
        self.assertFalse((self.output / "bundle").exists())

    def test_raw_changed_during_derivation_cannot_publish_a_complete_run(self):
        original = driver.write_bundle
        def changed(*args, **kwargs):
            result = original(*args, **kwargs)
            (self.output / "measurement/raw.jsonl").write_bytes(b"changed after audit\n")
            return result
        with mock.patch.object(driver, "write_bundle", side_effect=changed):
            self.assert_incomplete(self.collect())

    def test_complete_measurement_with_failed_gates_retains_evidence_and_exit_one(self):
        for event in self.events:
            data = event["data"]
            if event["type"] == "turn" and data["game"]["arm"] == "CANDIDATE":
                data["decision_fact"].update(clean_completion=False, selected_matched_required_cells=0,
                                             selected_visual_error_cells=32)
                data["actual_progress"]["matchedRequiredCells"] = 0
                data["actual_q"].update(clean=False, matchedRequired=0)
            elif event["type"] == "game_complete" and data["game"]["arm"] == "CANDIDATE":
                row = data["completion_csv"].split(",")
                row[5:11] = ["-1", "-1.000000", "0", "0", "0", "false"]
                data["completion_csv"] = ",".join(row)
        with redirect_stdout(io.StringIO()):
            self.assertEqual(1, driver.main(["--root", str(self.root), "--output", str(self.output), "--collect"]))
        report = json.loads((self.output / "report.json").read_bytes())
        self.assertEqual("PROTOTYPE_FAILED", report["status"])
        self.assertTrue(report["measurement_complete"])
        self.assertFalse(report["prototype_gates_pass"])
        self.assertFalse((self.output / "INCOMPLETE").exists())
        self.assertTrue((self.output / "bundle/manifest.json").is_file())

    def test_late_cancellation_after_analysis_keeps_incomplete_marker_and_report(self):
        @contextmanager
        def late():
            yield
            raise InterruptedError("late cancellation after analysis")
        with mock.patch.object(driver.supervisor, "interruption_handler", late):
            self.assert_incomplete(self.collect())
        self.assertTrue((self.output / "prototype-report.json").is_file())

    def test_existing_output_is_not_overwritten(self):
        self.output.mkdir()
        retained = self.output / "retained"
        retained.write_bytes(b"old evidence")
        with redirect_stderr(io.StringIO()):
            self.assertEqual(2, driver.main(["--root", str(self.root), "--output", str(self.output), "--collect"]))
        self.assertEqual([retained], list(self.output.iterdir()))
        self.assertEqual(b"old evidence", retained.read_bytes())
        self.launch.assert_not_called()

    def test_missing_environment_or_changed_protocol_rejects_pure_derivation(self):
        for protocol, environment in ((PROTOCOL_BYTES + b" ", ENVIRONMENT),
                                      (PROTOCOL_BYTES, dict(ENVIRONMENT, memory=""))):
            with self.assertRaises(ValueError):
                driver.derive_session(b"".join(encode(self.events)), self.manifest_bytes, self.digest,
                                      protocol, environment, "27", evidence_kind="synthetic")


if __name__ == "__main__":
    unittest.main()
