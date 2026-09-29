#!/usr/bin/env python3
"""Summarize desktop AI decision, playback, and preview rescue observations."""

import argparse
from collections import Counter
from dataclasses import dataclass
import math
from pathlib import Path
import re
import statistics
import sys


OUTCOMES = (
    "callback", "gravity-ready", "gravity-fallback", "cancelled", "failed", "fallback-failed"
)
TRACE = re.compile(
    r"AI_DECISION outcome=([a-z-]+) elapsed_ms=([0-9]+(?:\.[0-9]+)?) "
    r"fx_queue_ms=(-?[0-9]+(?:\.[0-9]+)?) fallback_ms=([0-9]+(?:\.[0-9]+)?)$"
)
RESCUE_TRACE = re.compile(
    r"AI_PREVIEW_RESCUE rank1_evaluated=(true|false) "
    r"preview_unrecoverable=(true|false) replaced=(true|false) "
    r"replacement_rank=(\d+) candidates_probed=(\d+) "
    r"detection_ms=([0-9]+(?:\.[0-9]+)?) search_ms=([0-9]+(?:\.[0-9]+)?)$"
)
PLAYBACK_OUTCOMES = (
    "completed", "blocked", "piece-changed", "manual", "paused", "ai-off", "superseded"
)
PLAYBACK_TRACE = re.compile(
    r"AI_PLAYBACK outcome=([a-z-]+) elapsed_ms=([0-9]+(?:\.[0-9]+)?) "
    r"controls=(\d+) executed=(\d+) drop_rows=(\d+)$"
)


@dataclass(frozen=True)
class Decision:
    outcome: str
    elapsed_ms: float
    fx_queue_ms: float
    fallback_ms: float


@dataclass(frozen=True)
class Rescue:
    rank1_evaluated: bool
    preview_unrecoverable: bool
    replaced: bool
    replacement_rank: int
    candidates_probed: int
    detection_ms: float
    search_ms: float


@dataclass(frozen=True)
class Playback:
    outcome: str
    elapsed_ms: float
    controls: int
    executed: int
    drop_rows: int


@dataclass(frozen=True)
class Capture:
    decisions: list[Decision]
    rescues: list[Rescue]
    playbacks: list[Playback]


def parse_capture(lines):
    decisions = []
    rescues = []
    playbacks = []
    for number, line in enumerate(lines, start=1):
        if "AI_PLAYBACK" in line:
            match = PLAYBACK_TRACE.search(line.strip())
            if match is None:
                raise ValueError(f"malformed AI_PLAYBACK at line {number}")
            outcome = match.group(1)
            elapsed = float(match.group(2))
            controls, executed, rows = map(int, match.groups()[2:])
            if (outcome not in PLAYBACK_OUTCOMES or not math.isfinite(elapsed)
                    or elapsed < 0 or controls < 1 or executed > controls
                    or (outcome == "completed" and executed != controls)):
                raise ValueError(f"invalid AI_PLAYBACK at line {number}")
            playbacks.append(Playback(outcome, elapsed, controls, executed, rows))
            continue
        if "AI_PREVIEW_RESCUE" in line:
            match = RESCUE_TRACE.search(line.strip())
            if match is None:
                raise ValueError(f"malformed AI_PREVIEW_RESCUE at line {number}")
            rank1, unrecoverable, replaced = (value == "true" for value in match.groups()[:3])
            rank, probed = map(int, match.groups()[3:5])
            detection, search = map(float, match.groups()[5:])
            if ((not rank1 and (unrecoverable or detection != 0))
                    or (not unrecoverable and (replaced or rank != 0 or probed != 0
                                               or search != 0))
                    or (replaced and (not unrecoverable or rank < 2 or probed != rank - 1))
                    or (not replaced and rank != 0)
                    or not all(map(math.isfinite, (detection, search)))):
                raise ValueError(f"invalid AI_PREVIEW_RESCUE at line {number}")
            rescues.append(Rescue(rank1, unrecoverable, replaced, rank, probed,
                                  detection, search))
            continue
        if "AI_DECISION" not in line:
            continue
        match = TRACE.search(line.strip())
        if match is None:
            raise ValueError(f"malformed AI_DECISION at line {number}")
        outcome = match.group(1)
        elapsed, queue, fallback = map(float, match.groups()[1:])
        if (outcome not in OUTCOMES or not all(map(math.isfinite, (elapsed, queue, fallback)))
                or elapsed < 0 or fallback < 0
                or (outcome == "callback" and queue < 0)
                or (outcome not in ("callback", "failed") and queue != -1.0)
                or (outcome == "failed" and queue < 0 and queue != -1.0)):
            raise ValueError(f"invalid AI_DECISION at line {number}")
        decisions.append(Decision(outcome, elapsed, queue, fallback))
    if not decisions:
        raise ValueError("no AI_DECISION lines found; enable TETRIS_AI_DECISION_TRACE=true")
    return Capture(decisions, rescues, playbacks)


def parse(lines):
    """Preserve the decision-only reader for existing log consumers."""
    return parse_capture(lines).decisions


def timing(values):
    if not values:
        return "n/a"
    ordered = sorted(values)
    p95 = ordered[math.ceil(0.95 * len(ordered)) - 1]
    return f"mean={statistics.fmean(ordered):.3f} p95={p95:.3f} max={ordered[-1]:.3f} ms"


def summarize(decisions, rescues=(), playbacks=()):
    counts = Counter(decision.outcome for decision in decisions)
    completed = sum(counts[outcome] for outcome in ("callback", "gravity-ready", "gravity-fallback"))
    print(f"Requests: {len(decisions)}")
    for outcome in OUTCOMES:
        print(f"  {outcome}: {counts[outcome]}")
    if completed:
        print(f"Gravity fallback among accepted decisions: "
              f"{counts['gravity-fallback']}/{completed} "
              f"({100 * counts['gravity-fallback'] / completed:.2f}%)")
    else:
        print("Gravity fallback among accepted decisions: n/a")
    for outcome in ("callback", "gravity-ready", "gravity-fallback"):
        print(f"{outcome} elapsed: "
              f"{timing([d.elapsed_ms for d in decisions if d.outcome == outcome])}")
    print("Callback FX queue: "
          + timing([d.fx_queue_ms for d in decisions if d.outcome == "callback"]))
    print("Gravity fallback computation: "
          + timing([d.fallback_ms for d in decisions if d.outcome == "gravity-fallback"]))
    if playbacks:
        playback_counts = Counter(playback.outcome for playback in playbacks)
        print(f"Playback terminal events: {len(playbacks)}")
        for outcome in PLAYBACK_OUTCOMES:
            print(f"  {outcome}: {playback_counts[outcome]}")
        print("Completed playback: "
              f"{playback_counts['completed']}/{len(playbacks)} "
              f"({100 * playback_counts['completed'] / len(playbacks):.2f}%)")
        print("Completed playback duration: "
              + timing([p.elapsed_ms for p in playbacks if p.outcome == "completed"]))
        print(f"Animated drop rows: {sum(p.drop_rows for p in playbacks)}")
    else:
        print("Playback terminal events: unavailable")
    if not rescues:
        print("Preview rescue observations: unavailable")
        return
    rank1 = sum(o.rank1_evaluated for o in rescues)
    unrecoverable = sum(o.preview_unrecoverable for o in rescues)
    replaced = sum(o.replaced for o in rescues)
    print(f"Computed preview rescue plans: {len(rescues)} (may include discarded decisions)")
    print(f"  rank 1 evaluated: {rank1}")
    print(f"  preview unrecoverable: {unrecoverable}")
    print(f"  replacement found: {replaced}")
    print("Preview detection: "
          + timing([o.detection_ms for o in rescues if o.rank1_evaluated]))
    print("Preview replacement search: "
          + timing([o.search_ms for o in rescues if o.preview_unrecoverable]))


def main():
    argument_parser = argparse.ArgumentParser(description=__doc__)
    argument_parser.add_argument("log", type=Path, help="desktop console log; use - for stdin")
    args = argument_parser.parse_args()
    try:
        if str(args.log) == "-":
            capture = parse_capture(sys.stdin)
        else:
            with args.log.open(encoding="utf-8", errors="replace") as lines:
                capture = parse_capture(lines)
        summarize(capture.decisions, capture.rescues, capture.playbacks)
    except (OSError, UnicodeError, ValueError) as failure:
        argument_parser.exit(2, f"error: {failure}\n")


if __name__ == "__main__":
    main()
