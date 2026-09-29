#!/usr/bin/env python3
"""Summarize the opt-in AI_DECISION lines emitted by the desktop game."""

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


@dataclass(frozen=True)
class Decision:
    outcome: str
    elapsed_ms: float
    fx_queue_ms: float
    fallback_ms: float


def parse(lines):
    decisions = []
    for number, line in enumerate(lines, start=1):
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
    return decisions


def timing(values):
    if not values:
        return "n/a"
    ordered = sorted(values)
    p95 = ordered[math.ceil(0.95 * len(ordered)) - 1]
    return f"mean={statistics.fmean(ordered):.3f} p95={p95:.3f} max={ordered[-1]:.3f} ms"


def summarize(decisions):
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


def main():
    argument_parser = argparse.ArgumentParser(description=__doc__)
    argument_parser.add_argument("log", type=Path, help="desktop console log; use - for stdin")
    args = argument_parser.parse_args()
    try:
        if str(args.log) == "-":
            decisions = parse(sys.stdin)
        else:
            with args.log.open(encoding="utf-8", errors="replace") as lines:
                decisions = parse(lines)
        summarize(decisions)
    except (OSError, UnicodeError, ValueError) as failure:
        argument_parser.exit(2, f"error: {failure}\n")


if __name__ == "__main__":
    main()
