"""Read a complete derived GoalWindow bundle; hashes do not authenticate a run."""
import argparse
import hashlib
from pathlib import Path
import stat
import sys
import json

from goal_window_baseline_format import render_baseline_tables
from goal_window_envelopes import assemble_envelopes
from write_goal_window_bundle import json_bytes, parse_input

FILES = {"records.json", "metadata.json", "protocol.json", "development.json",
         "calibration.json", "baseline-derived.csv"}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def ordinary(path, *, directory=False):
    info = path.lstat()
    # lstat catches dangling links too; Windows junctions are reparse points.
    require(not stat.S_ISLNK(info.st_mode)
            and not getattr(info, "st_file_attributes", 0) & stat.FILE_ATTRIBUTE_REPARSE_POINT,
            "linked bundle paths are not supported")
    require(stat.S_ISDIR(info.st_mode) if directory else stat.S_ISREG(info.st_mode),
            "expected ordinary bundle directory/file")


def read_bundle(path):
    """Return validated in-memory bytes without changing the evidence directory.

    Require the writer's exact v1 layout and rederive outputs from original inputs,
    including the unchanged frozen consumers. The result only establishes internal
    consistency, never raw/build/seed provenance or prototype/runtime acceptance.
    Concurrent filesystem mutation is outside this offline reader's contract.
    """
    path = Path(path)
    ordinary(path, directory=True)
    require({entry.name for entry in path.iterdir()} == FILES | {"manifest.json"},
            "incomplete or unexpected bundle files")
    for name in FILES | {"manifest.json"}:
        ordinary(path / name)
    manifest = parse_input((path / "manifest.json").read_bytes())
    require(isinstance(manifest, dict) and set(manifest) == {
        "schema", "evidence_kind", "derived", "provenance_authenticated",
        "eligible_for_runtime_validation", "files"}, "invalid manifest fields")
    require(manifest["schema"] == "goal-window-bundle-v1"
            and manifest["derived"] is True
            and manifest["provenance_authenticated"] is False
            and manifest["eligible_for_runtime_validation"] is False,
            "unsupported schema or authentication/permission claims")
    bindings = manifest["files"]
    require(isinstance(bindings, dict) and set(bindings) == FILES, "invalid file bindings")
    files = {}
    for name in sorted(FILES):
        binding = bindings[name]
        require(isinstance(binding, dict) and set(binding) == {"bytes", "sha256"},
                "invalid binding: " + name)
        require(type(binding["bytes"]) is int and binding["bytes"] >= 0
                and isinstance(binding["sha256"], str), "invalid binding types: " + name)
        raw = (path / name).read_bytes()
        require(len(raw) == binding["bytes"]
                and hashlib.sha256(raw).hexdigest() == binding["sha256"],
                "bundle bytes/hash mismatch: " + name)
        files[name] = raw
    records, metadata = parse_input(files["records.json"]), parse_input(files["metadata.json"])
    envelopes = assemble_envelopes(records, metadata, files["protocol.json"])
    require(manifest["evidence_kind"] == metadata["evidence_kind"], "evidence kind mismatch")
    for group, envelope in envelopes.items():
        require(files[group + ".json"] == json_bytes(envelope), "derived envelope mismatch: " + group)
    baseline = render_baseline_tables(envelopes["development"], envelopes["calibration"],
                                      files["protocol.json"]).encode("utf-8")
    require(files["baseline-derived.csv"] == baseline, "derived baseline mismatch")
    return {"manifest": manifest, "files": files}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bundle", type=Path, required=True)
    args = parser.parse_args()
    try:
        result = read_bundle(args.bundle)
    except (ValueError, OSError) as failure:
        print("GoalWindow bundle rejected: " + str(failure), file=sys.stderr)
        return 2
    print(json.dumps({"internally_consistent": True,
                      "evidence_kind": result["manifest"]["evidence_kind"],
                      "provenance_authenticated": False,
                      "eligible_for_runtime_validation": False}, sort_keys=True))
    return 0


if __name__ == "__main__":
    sys.exit(main())
