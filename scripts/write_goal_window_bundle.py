"""Persist derived GoalWindow evidence without overwriting or attesting a run."""
import argparse
import hashlib
import json
from pathlib import Path
import sys

from goal_window_baseline_format import render_baseline_tables
from goal_window_envelopes import assemble_envelopes


def json_bytes(value):
    return (json.dumps(value, sort_keys=True, indent=2, allow_nan=False) + "\n").encode("utf-8")


def parse_input(raw):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError("duplicate JSON field: " + key)
            result[key] = value
        return result

    def invalid(value):
        raise ValueError("non-finite JSON number: " + value)

    return json.loads(raw.decode("utf-8"), object_pairs_hook=unique, parse_constant=invalid)


def write_bundle(records_bytes, metadata_bytes, protocol_bytes, output):
    """Validate supplied facts, then reserve a new directory and retain exact inputs.

    A bundle is complete only if manifest.json exists AND INCOMPLETE is absent.
    Any write failure preserves the reserved directory and its written prefix;
    retries must use another path. Hashes bind bytes, never gameplay provenance.
    """
    records, metadata = parse_input(records_bytes), parse_input(metadata_bytes)
    envelopes = assemble_envelopes(records, metadata, protocol_bytes)
    baseline = render_baseline_tables(envelopes["development"], envelopes["calibration"], protocol_bytes)
    files = {
        "records.json": records_bytes,
        "metadata.json": metadata_bytes,
        "protocol.json": protocol_bytes,
        "development.json": json_bytes(envelopes["development"]),
        "calibration.json": json_bytes(envelopes["calibration"]),
        "baseline-derived.csv": baseline.encode("utf-8"),
    }
    manifest = {
        "schema": "goal-window-bundle-v1",
        "evidence_kind": metadata["evidence_kind"],
        "derived": True,
        "provenance_authenticated": False,
        "eligible_for_runtime_validation": False,
        "files": {name: {"sha256": hashlib.sha256(raw).hexdigest(), "bytes": len(raw)}
                  for name, raw in files.items()},
    }
    output = Path(output)
    # mkdir is the reservation, including when another invocation races us.
    # Neither existing directories nor files (including dangling links) are reused.
    output.mkdir()
    marker = output / "INCOMPLETE"
    marker.write_bytes(b"Incomplete until all files and manifest are written.\n")
    for name, raw in files.items():
        with (output / name).open("xb") as stream:
            stream.write(raw)
    with (output / "manifest.json").open("xb") as stream:
        stream.write(json_bytes(manifest))
    marker.unlink()
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--records", type=Path, required=True)
    parser.add_argument("--metadata", type=Path, required=True)
    parser.add_argument("--protocol", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        result = write_bundle(args.records.read_bytes(), args.metadata.read_bytes(),
                              args.protocol.read_bytes(), args.output)
    except (ValueError, OSError) as failure:
        print("GoalWindow bundle rejected: " + str(failure), file=sys.stderr)
        return 2
    print(json.dumps(result, sort_keys=True))
    return 0


if __name__ == "__main__":
    sys.exit(main())
