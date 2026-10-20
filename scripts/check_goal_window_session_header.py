"""Bind a supplied raw provenance header to manifest bytes; never attest execution."""
import argparse
import hashlib
from pathlib import Path, PurePosixPath, PureWindowsPath
import re
import sys

from prepare_goal_window_build import PROTOCOL_SHA
from write_goal_window_bundle import json_bytes, parse_input

# Versioned counterpart of GoalWindowBuildProvenance.CRITICAL. A source-contract
# test forces explicit review if the Java producer changes this evidence boundary.
CRITICAL = (
    "ai.GoalWindowCandidate", "ai.ActionPlanCandidates", "ai.ActionStateSearch",
    "ai.BoardOccupancyMetrics", "ai.ActionPlanSimulator", "ai.BoardSimulator",
    "ai.BuildShapeActionPlanningAgent", "ai.ObjectiveRiskController", "ai.ObjectiveSafetyBudget",
    "ai.ShapeTarget", "ai.GameSnapshot", "core.BoardRules", "core.BagPieceGenerator",
    "ai.benchmark.HeadlessGameRunner", "ai.benchmark.ShapeCompletionTelemetry",
    "ai.benchmark.GoalWindowGameEvidence", "ai.benchmark.GoalWindowPairedRunner",
    "ai.benchmark.GoalWindowPairedApplication", "ai.benchmark.GoalWindowBuildProvenance",
)


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha(value, length=64):
    require(isinstance(value, str) and re.fullmatch(r"[0-9a-f]{%d}" % length, value),
            "invalid SHA")


def check_header(header_bytes, manifest_bytes, expected_manifest_sha256):
    """Check the first raw event against an independently retained manifest digest.

    Pure path handling supports Linux evidence on Windows without resolving paths
    against the reader's disk. This does not call verify_build, launch a JVM, audit
    the remaining stream or authenticate any caller-supplied provenance claims.
    """
    try:
        return _check(header_bytes, manifest_bytes, expected_manifest_sha256)
    except (KeyError, TypeError, AttributeError, OverflowError, RecursionError) as failure:
        raise ValueError("missing or malformed session header/manifest") from failure


def _check(header_bytes, manifest_bytes, expected_hash):
    sha(expected_hash)
    require(hashlib.sha256(manifest_bytes).hexdigest() == expected_hash, "manifest digest mismatch")
    manifest, header = parse_input(manifest_bytes), parse_input(header_bytes)
    require(isinstance(header, dict) and set(header) == {"schema", "sequence", "type", "data"},
            "invalid first event fields")
    require(header["schema"] == "goal-window-raw-v1" and type(header["sequence"]) is int
            and header["sequence"] == 1 and header["type"] == "provenance_verified",
            "expected first provenance_verified event")
    data = header["data"]
    require(isinstance(data, dict) and set(data) == {"manifest_sha256", "manifest", "loaded_classes"},
            "invalid provenance fields")
    require(data["manifest_sha256"] == expected_hash, "header manifest digest mismatch")
    # JSON equality would silently equate True/1 and 27/27.0 in untrusted evidence.
    require(json_bytes(data["manifest"]) == json_bytes(manifest), "embedded manifest mismatch")
    require(isinstance(manifest, dict) and manifest["schema"] == "goal-window-build-v1"
            and manifest["clean_checkout"] is True and manifest["jvm_args"] == []
            and manifest["protocol_sha256"] == PROTOCOL_SHA
            and manifest["production_guard"]["production_diff_verified"] is True,
            "unsupported build claims")
    sha(manifest["source_sha"], 40)
    sha(manifest["config_sha256"])
    root_text = manifest["root"]
    require(isinstance(root_text, str) and root_text, "missing root")
    root = PureWindowsPath(root_text) if PureWindowsPath(root_text).drive else PurePosixPath(root_text)
    require(root.is_absolute() and ".." not in root.parts and str(root) == root_text,
            "noncanonical or relative root")
    classes, loaded = manifest["class_files"], data["loaded_classes"]
    require(isinstance(classes, dict) and isinstance(loaded, dict)
            and set(loaded) == {"xyz.xuminghai.tetris." + suffix for suffix in CRITICAL},
            "missing or unexpected critical loaded classes")
    for suffix in CRITICAL:
        name = "xyz.xuminghai.tetris." + suffix
        base = "target/test-classes" if "GoalWindow" in suffix else "target/classes"
        relative = base + "/" + name.replace(".", "/") + ".class"
        recorded = classes[relative]
        sha(recorded)
        require(loaded[name] == {"origin": str(root / base), "sha256": recorded},
                "loaded class origin/hash mismatch: " + name)
    return {"schema": "goal-window-session-header-check-v1", "header_consistent": True,
            "manifest_sha256": expected_hash, "header_sha256": hashlib.sha256(header_bytes).hexdigest(),
            "source_sha": manifest["source_sha"], "critical_classes": len(CRITICAL),
            "provenance_authenticated": False, "raw_completeness_verified": False,
            "eligible_for_runtime_validation": False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--raw", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--manifest-sha256", required=True)
    args = parser.parse_args()
    try:
        with args.raw.open("rb") as stream:
            header = stream.readline()
        require(header.endswith(b"\n"), "unterminated first raw event")
        result = check_header(header, args.manifest.read_bytes(), args.manifest_sha256)
    except (ValueError, OSError) as failure:
        print("GoalWindow session header rejected: " + str(failure), file=sys.stderr)
        return 2
    sys.stdout.buffer.write(json_bytes(result))
    return 0


if __name__ == "__main__":
    sys.exit(main())
