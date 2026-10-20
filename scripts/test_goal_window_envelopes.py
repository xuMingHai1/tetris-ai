"""Synthetic assembly/consumer checks; no JVM, warmup or population is executed."""
import copy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest

import analyze_heart_paired as paired
import analyze_heart_baseline as baseline
from goal_window_baseline_format import render_baseline_tables
from goal_window_envelopes import assemble_envelopes
from goal_window_paired_format import convert_game
from test_analyze_heart_paired import fixture, game, PROTOCOL, PROTOCOL_BYTES
from test_goal_window_paired_format import raw_game


def inputs():
    metadata = fixture("development")
    del metadata["population"]
    for arm in metadata["arms"].values():
        del arm["games"]
    records = []
    for seed in range(20000, 20080):
        order = ("baseline", "candidate") if seed % 2 == 0 else ("candidate", "baseline")
        records.extend({"arm": arm, "game": copy.deepcopy(game(seed, arm, 4,
                       2 if arm == "candidate" else -1, 1_000_003))} for arm in order)
    return records, metadata


class EnvelopeTests(unittest.TestCase):
    def assemble(self, records=None, metadata=None, protocol=PROTOCOL_BYTES):
        original_records, original_metadata = inputs()
        return assemble_envelopes(original_records if records is None else records,
                                  original_metadata if metadata is None else metadata, protocol)

    def test_full_split_preserves_exact_facts_and_shared_metadata(self):
        records, metadata = inputs()
        result = self.assemble(records, metadata)
        for name, count, first, last in (("development", 20, 20000, 20019),
                                         ("calibration", 60, 20020, 20079)):
            envelope = result[name]
            for arm in ("baseline", "candidate"):
                games = envelope["arms"][arm]["games"]
                self.assertEqual(len(games), count)
                self.assertEqual([g["seed"] for g in games], list(range(first, last + 1)))
                expected = [r["game"] for r in records if r["arm"] == arm
                            and first <= r["game"]["seed"] <= last]
                self.assertEqual(games, expected)
                self.assertEqual(envelope["arms"][arm]["warmup"], metadata["arms"][arm]["warmup"])
            report = paired.analyze(envelope, PROTOCOL_BYTES)
            self.assertFalse(report["eligible_for_runtime_validation"])

    def test_session_reference_preserved_without_aliasing_or_authentication(self):
        records, metadata = inputs()
        reference = {"raw_sha256": "b" * 64, "manifest_sha256": "c" * 64}
        metadata["goal_window_session"] = copy.deepcopy(reference)
        result = self.assemble(records, metadata)
        for envelope in result.values():
            self.assertEqual(envelope["goal_window_session"], reference)
            self.assertFalse(paired.analyze(envelope, PROTOCOL_BYTES)["eligible_for_runtime_validation"])
        result["development"]["goal_window_session"]["raw_sha256"] = "d" * 64
        self.assertEqual(result["calibration"]["goal_window_session"], reference)
        self.assertEqual(metadata["goal_window_session"], reference)

    def test_malformed_session_reference_rejected(self):
        reference = {"raw_sha256": "b" * 64, "manifest_sha256": "c" * 64}
        invalid = [None, [], "session", {}, {"raw_sha256": "b" * 64},
                   dict(reference, authenticated=True)]
        for key in reference:
            invalid.extend(dict(reference, **{key: value})
                           for value in (True, 1, None, [], "A" * 64, "g" * 64, "b" * 63, "b" * 65))
        for value in invalid:
            with self.subTest(reference=value):
                records, metadata = inputs()
                metadata["goal_window_session"] = value
                with self.assertRaises(ValueError): self.assemble(records, metadata)

    def test_optional_session_does_not_allow_unknown_metadata_fields(self):
        for linked in (False, True):
            records, metadata = inputs()
            if linked:
                metadata["goal_window_session"] = {"raw_sha256": "b" * 64, "manifest_sha256": "c" * 64}
            metadata["provenance_authenticated"] = True
            with self.assertRaises(ValueError): self.assemble(records, metadata)

    def test_linked_bundle_roundtrip_and_rehashed_session_tampering(self):
        from read_goal_window_bundle import read_bundle
        from write_goal_window_bundle import json_bytes, write_bundle

        records, metadata = inputs()
        reference = {"raw_sha256": "b" * 64, "manifest_sha256": "c" * 64}
        metadata["goal_window_session"] = reference
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "bundle"
            manifest = write_bundle(json_bytes(records), json_bytes(metadata), PROTOCOL_BYTES, output)
            before = {p.name: p.read_bytes() for p in output.iterdir()}
            result = read_bundle(output)
            self.assertEqual(before, {p.name: p.read_bytes() for p in output.iterdir()})
            for group in ("development", "calibration"):
                self.assertEqual(json.loads(result["files"][group + ".json"])["goal_window_session"], reference)
            self.assertFalse(result["manifest"]["provenance_authenticated"])
            self.assertFalse(result["manifest"]["eligible_for_runtime_validation"])
            # A matching file hash cannot hide a session reference inconsistent
            # with the retained metadata; the reader must still rederive it.
            changed = json.loads(before["development.json"])
            changed["goal_window_session"]["raw_sha256"] = "d" * 64
            raw = json_bytes(changed)
            (output / "development.json").write_bytes(raw)
            manifest["files"]["development.json"] = {"bytes": len(raw), "sha256": hashlib.sha256(raw).hexdigest()}
            (output / "manifest.json").write_bytes(json_bytes(manifest))
            with self.assertRaisesRegex(ValueError, "derived envelope mismatch"):
                read_bundle(output)

    def test_raw_adapter_through_assembly_and_frozen_baseline_consumer(self):
        records, metadata = inputs()
        for record in records:
            raw, _ = raw_game(record["game"]["seed"], record["arm"].upper(), 4,
                              2 if record["arm"] == "candidate" else -1)
            record["game"] = convert_game(raw, run_order=record["game"]["run_order"])
        result = self.assemble(records, metadata)
        text = render_baseline_tables(result["development"], result["calibration"], PROTOCOL_BYTES)
        report = baseline.analyze(text, PROTOCOL)
        self.assertIsInstance(report, dict)
        self.assertIn("1000003", text)

    def test_inputs_and_outputs_are_independent(self):
        records, metadata = inputs()
        before = copy.deepcopy((records, metadata))
        result = self.assemble(records, metadata)
        self.assertEqual((records, metadata), before)
        result["development"]["environment"]["os"] = "changed"
        result["development"]["arms"]["baseline"]["warmup"]["decisions"] = 1
        result["development"]["arms"]["baseline"]["games"][0]["decision_facts"][0]["decision_nanos"] = 1
        self.assertEqual((records, metadata), before)
        self.assertEqual(result["calibration"]["environment"], metadata["environment"])
        self.assertEqual(result["calibration"]["arms"]["baseline"]["warmup"]["decisions"], 100)

    def test_claimed_measured_kind_is_preserved_without_runtime_permission(self):
        records, metadata = inputs()
        metadata["evidence_kind"] = "measured"
        for envelope in self.assemble(records, metadata).values():
            self.assertEqual(envelope["evidence_kind"], "measured")
            self.assertFalse(paired.analyze(envelope, PROTOCOL_BYTES)["eligible_for_runtime_validation"])

    def test_missing_extra_duplicate_and_reserved_games_rejected(self):
        for mutate in (lambda r: r.pop(), lambda r: r.append(copy.deepcopy(r[0])),
                       lambda r: r.__setitem__(2, copy.deepcopy(r[0])),
                       lambda r: r[0]["game"].update(seed=30000)):
            records, metadata = inputs()
            mutate(records)
            with self.assertRaises(ValueError): self.assemble(records, metadata)

    def test_observed_order_and_explicit_arm_cannot_be_reconstructed(self):
        for mutate in (lambda r: r.reverse(), lambda r: r.__setitem__(slice(0, 2), r[1::-1]),
                       lambda r: r[0].update(arm="candidate"),
                       lambda r: r[0]["game"].update(run_order=1),
                       lambda r: r[0]["game"].update(run_order=False),
                       lambda r: r[0]["game"].update(seed=20000.0)):
            records, metadata = inputs()
            mutate(records)
            with self.assertRaises(ValueError): self.assemble(records, metadata)

    def test_missing_or_failed_game_facts_rejected(self):
        for key in ("primary_failures", "fallback_decisions", "illegal_plans"):
            for missing in (True, False):
                records, metadata = inputs()
                if missing: del records[0]["game"][key]
                else: records[0]["game"][key] = 1
                with self.assertRaises(ValueError): self.assemble(records, metadata)
        records, metadata = inputs()
        records[0]["game"]["decision_facts"].pop()
        with self.assertRaises(ValueError): self.assemble(records, metadata)

    def test_frozen_metadata_and_config_validation(self):
        for mutate in (lambda m: m.update(protocol_sha256="0" * 64),
                       lambda m: m.update(timing_scope="agent-only"),
                       lambda m: m.update(reset_strategy_per_game=False),
                       lambda m: m["environment"].pop("clock"),
                       lambda m: m["arms"]["candidate"].update(source_sha="b" * 40),
                       lambda m: m["arms"]["candidate"].update(config_sha256="0" * 64),
                       lambda m: m["arms"]["baseline"]["warmup"].update(decisions=99),
                       lambda m: m["arms"]["candidate"].update(games=[]),
                       lambda m: m.update(population="validation")):
            records, metadata = inputs()
            mutate(metadata)
            with self.assertRaises(ValueError): self.assemble(records, metadata)

    def test_changed_protocol_and_malformed_types_rejected_as_value_error(self):
        with self.assertRaises(ValueError): self.assemble(protocol=PROTOCOL_BYTES + b" ")
        for records, metadata in ((None, {}), ({}, {}), ([None] * 160, inputs()[1]),
                                  (inputs()[0], None)):
            with self.assertRaises(ValueError): assemble_envelopes(records, metadata, PROTOCOL_BYTES)

    def test_invalid_occupancy_and_oversized_timing_rejected(self):
        for mutate in (lambda g: g["decision_facts"][0].update(clean_completion=True),
                       lambda g: g.update(elapsed_game_ms=10 ** 400),
                       lambda g: g["decision_facts"][0].update(decision_nanos=10 ** 400)):
            records, metadata = inputs()
            mutate(records[0]["game"])
            with self.assertRaises(ValueError): self.assemble(records, metadata)


if __name__ == "__main__":
    unittest.main()
