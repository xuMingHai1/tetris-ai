"""Own the fixed GoalWindow session and retain its evidence; default is verify-only."""
import argparse
import copy
import hashlib
import json
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

import analyze_goal_window_prototype as prototype
import analyze_heart_baseline as baseline
import analyze_heart_paired as paired
import audit_goal_window_raw as raw
from goal_window_paired_format import convert_game
import prepare_goal_window_build as build
from read_goal_window_bundle import read_bundle
import supervise_goal_window as supervisor
from write_goal_window_bundle import json_bytes, parse_input, write_bundle


def digest(value):
    return hashlib.sha256(value).hexdigest()


def derive_session(raw_bytes, manifest_bytes, manifest_sha256, protocol_bytes, environment, javafx, *, evidence_kind):
    """Derive one complete session from the same retained byte snapshots.

    This pure seam checks internal consistency, never authenticates an imported
    stream. Only run() owns the build, fixed JVM launch and disk revalidation.
    """
    lines = raw_bytes.splitlines(keepends=True)
    audit = raw.audit(lines, manifest_bytes=manifest_bytes,
                      expected_manifest_sha256=manifest_sha256)
    manifest = parse_input(manifest_bytes)
    events = [json.loads(line, object_pairs_hook=raw.unique_object,
                         parse_constant=raw.reject_constant, parse_float=raw.finite_float) for line in lines]
    records, game_events = [], []
    warmup = {"BASELINE": 0, "CANDIDATE": 0}
    for event in events:
        kind, data = event["type"], event["data"]
        if kind == "game_started":
            game_events = [event]
        elif kind in ("turn", "game_complete"):
            game_events.append(event)
            if kind == "game_complete":
                identity = data["game"]
                if identity["phase"] == "WARMUP":
                    warmup[identity["arm"]] += data["result"]["decisions"]
                else:
                    records.append({"arm": identity["arm"].lower(),
                                    "game": convert_game(game_events, run_order=len(records) % 2)})
                game_events = []
    build.require(digest(protocol_bytes) == build.PROTOCOL_SHA == manifest["protocol_sha256"],
                  "changed frozen protocol")
    protocol = parse_input(protocol_bytes)
    # The audit has checked every observed warmup result; do not fabricate its count.
    paired.require(set(warmup.values()) == {100}, "incomplete observed per-arm warmup")
    paired.require(environment.get("cpuinfo") and environment.get("memory"),
                   "Linux CPU/memory evidence required for measurement")
    metadata = {
        "schema": paired.SCHEMA, "protocol_sha256": digest(protocol_bytes),
        "evidence_kind": evidence_kind, "timing_scope": paired.TIMING_SCOPE,
        "input_boundary": "post-spawn-gravity-known-preview", "reset_strategy_per_game": True,
        "goal_window_session": {"raw_sha256": audit["raw_sha256"], "manifest_sha256": manifest_sha256},
        "environment": {
            "os": environment["os"], "arch": environment["arch"],
            "cpu": json.dumps({key: environment[key] for key in
                              ("cpu_count", "cpuinfo", "cpu_affinity") if key in environment}, sort_keys=True),
            "ram": environment["memory"], "jdk": manifest["jdk"]["java.runtime.version"],
            "javafx": javafx, "jvm_flags": "[]", "clock": "java.lang.System.nanoTime",
        },
        "arms": {},
    }
    for arm in ("baseline", "candidate"):
        config = {
            "agent": "build-shape" if arm == "baseline" else "goal-window",
            "rows": 20, "cols": 10, "target": "heart", "piece_horizon": 1000,
            "preview_rescue": False, "parameters": {} if arm == "baseline" else copy.deepcopy(build.EXPECTED_CONFIG),
        }
        metadata["arms"][arm] = {
            "source_sha": manifest["source_sha"], "config": config,
            "config_sha256": paired.digest(config),
            "production_base_sha": protocol["production_base_sha"],
            "warmup": {**protocol["warmup"], "decisions": warmup[arm.upper()]},
        }
    return records, metadata, protocol_bytes, audit


def build_protocol(manifest):
    value = (Path(manifest["root"]) / build.PROTOCOL).read_bytes()
    build.require(digest(value) == build.PROTOCOL_SHA == manifest["protocol_sha256"],
                  "changed frozen protocol")
    return value


def write_new(path, value):
    with path.open("xb") as stream:
        stream.write(json_bytes(value))


def artifacts(output):
    result = {}
    for path in sorted(output.rglob("*")):
        build.require(not path.is_symlink(), "linked session artifact")
        if path.is_file() and path not in (output / "report.json", output / "INCOMPLETE"):
            result[path.relative_to(output).as_posix()] = {
                "sha256": build.digest(path), "bytes": path.stat().st_size}
    return result


def run(root, output, *, collect=False):
    if not collect:
        return supervisor.preflight(root, output)
    build.require(sys.platform.startswith("linux"), "measurement driver requires Linux")
    root, output = Path(root).resolve(strict=True), Path(output).absolute()
    build.require(not output.resolve().is_relative_to(root), "output must be outside checkout")
    output.mkdir()
    marker = output / "INCOMPLETE"
    marker.write_bytes(b"Session incomplete until the final report is written.\n")
    report = {
        "schema": "goal-window-driver-v1", "mode": "MEASUREMENT", "status": "INCOMPLETE",
        "measurement_complete": False, "runtime_permission": False,
        "started_utc": supervisor.utc_now(), "environment_before": supervisor.environment(),
    }
    (output / "report.json").write_bytes(json_bytes(report))
    try:
        with supervisor.interruption_handler():
            preflight = supervisor.preflight(root, output / "preflight")
            report["preflight"] = preflight
            build.require(preflight["status"] == "VERIFIED"
                          and preflight["population_started"] is False, "preflight did not verify")
            prepared = preflight["build"]
            path, expected = Path(prepared["manifest"]), prepared["manifest_sha256"]
            manifest_bytes = (output / "preflight/build-manifest.json").read_bytes()
            build.require(digest(manifest_bytes) == expected == build.digest(path),
                          "retained manifest differs from this preflight build")
            manifest = build.verify_build(path, expected)
            build.require(manifest["root"] == str(root), "preflight belongs to another checkout")
            measurement = output / "measurement"
            measurement.mkdir()
            report["process"] = supervisor.launch_prepared(path, expected, measurement, verify_only=False)
            build.require(report["process"]["state"] == "EXITED"
                          and report["process"]["returncode"] == 0, "measurement process did not complete")
            build.require(build.digest(path) == expected, "manifest changed during measurement")
            build.verify_build(path, expected)
            report["build_revalidated_before_and_after"] = True
            raw_path = measurement / "raw.jsonl"
            build.require(not raw_path.is_symlink(), "linked raw stream")
            raw_bytes = raw_path.read_bytes()
            ns = {"m": "http://maven.apache.org/POM/4.0.0"}
            javafx = ET.parse(root / "pom.xml").find("m:properties/m:javafx.version", ns).text
            records, metadata, protocol_bytes, audit = derive_session(
                raw_bytes, manifest_bytes, expected, build_protocol(manifest), report["environment_before"],
                javafx, evidence_kind="measured")
            write_new(output / "raw-audit.json", audit)
            report["raw_header_bound"] = audit["session_header_check"]["header_consistent"]
            write_bundle(json_bytes(records), json_bytes(metadata), protocol_bytes, output / "bundle")
            # Analyze the reader's verified snapshots rather than reopening individual files.
            files = read_bundle(output / "bundle")["files"]
            group_bytes = [files[name + ".json"] for name in prototype.POPULATIONS]
            result = prototype.analyze(*(parse_input(value) for value in group_bytes), files["protocol.json"])
            result["input_files_sha256"] = {
                name: digest(value) for name, value in zip(prototype.POPULATIONS, group_bytes)}
            write_new(output / "prototype-report.json", result)
            write_new(output / "baseline-report.json", baseline.analyze(
                files["baseline-derived.csv"].decode("utf-8"), parse_input(files["protocol.json"])))
            build.require(build.digest(raw_path) == audit["raw_sha256"],
                          "raw stream changed during derivation")
            build.require(build.digest(path) == expected, "manifest changed during derivation")
            report.update(
                status="PROTOTYPE_PASSED" if result["prototype_gates_pass"] else "PROTOTYPE_FAILED",
                measurement_complete=True, prototype_gates_pass=result["prototype_gates_pass"],
                source_sha=manifest["source_sha"], manifest_sha256=expected,
                raw_sha256=audit["raw_sha256"],
            )
    except (Exception, KeyboardInterrupt) as failure:
        report.update(status="INCOMPLETE", measurement_complete=False,
                      failure={"type": type(failure).__name__, "message": str(failure)})
    finally:
        report.update(ended_utc=supervisor.utc_now(), environment_after=supervisor.environment())
        report["artifacts"] = artifacts(output)
        (output / "report.json").write_bytes(json_bytes(report))
    # Keep the marker through context-manager exit and final report persistence.
    # A late cancellation cannot publish a complete session with an INCOMPLETE report.
    if report["measurement_complete"]:
        marker.unlink()
    return report


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path("."))
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--collect", action="store_true", help="explicit fixed full session; requires accepted prerequisites")
    args = parser.parse_args(argv)
    try:
        report = run(args.root, args.output, collect=args.collect)
    except (OSError, ValueError, KeyError, TypeError) as failure:
        print("GoalWindow driver rejected: " + str(failure), file=sys.stderr)
        return 2
    print(json.dumps({"status": report["status"], "report": str(args.output.absolute() / "report.json")}))
    return 0 if report["status"] in ("VERIFIED", "PROTOTYPE_PASSED") else 1 if report["status"] == "PROTOTYPE_FAILED" else 2


if __name__ == "__main__":
    raise SystemExit(main())
