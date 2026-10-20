"""Project validated paired baseline facts into the frozen baseline analyzer's tables."""
import csv
import io
import json

import analyze_heart_baseline as baseline
import analyze_heart_paired as paired


def render_baseline_tables(development, calibration, protocol_bytes):
    """Return derived CSV text, never raw evidence or a provenance/measurement attestation.

    Both complete #105 envelopes are required. The original validators enforce
    occupancy and timing algebra; this adapter neither reconstructs gameplay nor
    fills absent telemetry. Non-baseline facts cannot enter the exported rows.
    """
    try:
        return _render(development, calibration, protocol_bytes)
    except (KeyError, TypeError, AttributeError, OverflowError) as failure:
        raise ValueError("missing or malformed paired input") from failure


def _render(development, calibration, protocol_bytes):
    for data, population in ((development, "development"), (calibration, "calibration")):
        paired.require(data["population"] == population, "expected development then calibration")
        paired.analyze(data, protocol_bytes)
    # Matching references establish consistency, not actual execution provenance.
    for key in ("schema", "protocol_sha256", "evidence_kind", "timing_scope",
                "input_boundary", "reset_strategy_per_game", "environment"):
        paired.require(development[key] == calibration[key], "mixed session metadata: " + key)
    for name in ("baseline", "candidate"):
        left, right = development["arms"][name], calibration["arms"][name]
        paired.require({k: v for k, v in left.items() if k != "games"}
                       == {k: v for k, v in right.items() if k != "games"},
                       "mixed arm metadata: " + name)
    games = sorted(development["arms"]["baseline"]["games"]
                   + calibration["arms"]["baseline"]["games"], key=lambda g: g["seed"])
    paired.require(all(g[k] == 0 for g in games
                       for k in ("primary_failures", "fallback_decisions", "illegal_plans")),
                   "failed baseline cannot become normal game evidence")
    output = io.StringIO(newline="")
    output.write("# Derived paired baseline projection; not original benchmark stdout or authenticated evidence\n")
    output.write("# evidence_kind=" + development["evidence_kind"] + "\n")
    warmup = development["arms"]["baseline"]["warmup"]
    output.write("# build_shape_warmup seed={seed_start} games={games} pieces={max_pieces} decisions={decisions}\n"
                 .format(**warmup))
    writer = csv.writer(output, lineterminator="\n")

    def table(name, columns, rows):
        writer.writerow([name, *columns])
        writer.writerows(rows)

    def boolean(value):
        return "true" if value else "false"

    strategy = "build-shape-heart"
    table("agent", ("seed", "piece_limit", "pieces_placed", "decisions", "primary_failures",
                    "fallback_decisions", "avg_decision_ms", "max_decision_ms", "reached_piece_limit"),
          ([strategy, g["seed"], g["piece_limit"], g["pieces_placed"], g["decisions"],
            g["primary_failures"], g["fallback_decisions"],
            format(g["total_decision_nanos"] / max(1, g["decisions"]) / 1e6, ".3f"),
            format(g["max_decision_nanos"] / 1e6, ".3f"), boolean(g["reached_piece_limit"])] for g in games))
    shape = ("best_visual_error_cells", "max_required_with_zero_forbidden", "min_forbidden_at_full_required")
    table("build_shape_game", ("strategy", "target", "seed", *shape, "clean_completion"),
          (["build_shape_game", strategy, "heart", g["seed"], *[g[k] for k in shape],
            boolean(g["clean_turns"] > 0)] for g in games))
    outcome = ("decisions", "first_clean_piece", "first_clean_elapsed_ms", "post_clean_pieces",
               "clean_turns", "max_clean_streak", "clean_at_end", "elapsed_game_ms")
    table("build_shape_outcome", ("strategy", "target", "seed", *outcome),
          (["build_shape_outcome", strategy, "heart", g["seed"],
            *[boolean(g[k]) if type(g[k]) is bool else g[k] for k in outcome]] for g in games))
    table("build_shape_timing", ("strategy", "target", "seed", "decision", "decision_nanos"),
          (["build_shape_timing", strategy, "heart", g["seed"], f["decision"], f["decision_nanos"]]
           for g in games for f in g["decision_facts"]))
    table("build_shape_decision", ("strategy", "target", "seed", "decision", "clean_completion",
          "selected_forbidden_occupied_cells", "required_completion_rate",
          "selected_matched_required_cells", "selected_visual_error_cells"),
          (["build_shape_decision", strategy, "heart", g["seed"], f["decision"],
            boolean(f["clean_completion"]), f["selected_forbidden_occupied_cells"],
            f["selected_matched_required_cells"] / baseline.HEART_REQUIRED,
            f["selected_matched_required_cells"], f["selected_visual_error_cells"]]
           for g in games for f in g["decision_facts"]))
    text = output.getvalue()
    # Fail closed if the unchanged consumer cannot read this projection.
    baseline.analyze(text, json.loads(protocol_bytes))
    return text
