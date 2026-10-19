# GoalWindow raw stream integrity audit

This is an offline component of #94, independent of process supervision (#120)
and the prototype gate evaluator (#121). It consumes the existing
[`GoalWindowPairedApplication`](../../src/test/java/xyz/xuminghai/tetris/ai/benchmark/GoalWindowPairedApplication.java)
`goal-window-raw-v1` JSONL. It never starts a JVM or a game.

```sh
python3 scripts/audit_goal_window_raw.py --input raw.jsonl --output new-audit.json
python3 -m unittest discover -s scripts -p test_audit_goal_window_raw.py -v
```

The audit requires continuous event sequence numbers, one provenance record,
one full session start, both fixed 100-decision warmups, and all 160 measured
games in the CLI's seed/parity order. Turns must belong to the active game and
be continuous within its horizon. Occupancy/clean facts must agree with actual
progress. Elapsed times must be monotonic and cover all recorded decision costs.
Game result counts, timing totals, failure/fallback/illegal counts, horizon and
the existing completion CSV must agree with turns. Candidate/Q and prediction
observations must be present; Q cannot turn a non-clean board into a clean fact.
Prediction records must have valid decision identities and resolution tags;
each preview setup resolves on the next turn, or appears once as pending at the
end. The final prediction list must exactly match the streamed resolutions plus
that permitted pending record. This checks serialization consistency, not the
truth of the prediction or board/type comparison. Nanosecond inputs are bounded
to the nonnegative Java `long` range before CSV formatting.
Only a final session-complete event with all 180 games permits success.

Missing/truncated lines, duplicate JSON keys, non-finite numbers, verify-only,
failure events, trailing events, missing games and contradictory summaries are
rejected. Exit 0 writes a new report exclusively; exit 2 rejects invalid input
or output errors. Existing reports and raw inputs are not overwritten. The
report binds the exact raw bytes with SHA256 and counts clean facts independently
of Q (including clean non-Q turns).

**Scope:** success means structural consistency of untrusted event claims.
Manifest/loaded-class values are retained as required records, not authenticated.
The auditor does not reconstruct boards or validate candidate choice semantics,
Q geometry, prediction resolution semantics, seed/bag sequences, loaded class
origins, or build hashes. In particular, a fully synthetic stream can pass.
`build_provenance_verified`, `seed_sequence_attested`,
`prototype_gates_evaluated` and `runtime_permission` remain false.

This does not produce the #105 envelopes or the baseline analyzer's compatible
logs, apply #121's gates, authenticate measurement provenance, or complete the
driver/workflow/selector. Those remain integration work; a future driver must
also reject parent-process timeout/cancellation even if raw contains complete.
The focused tests fabricate event streams and never consume any population.
No new collection, #95/#96 unlock, reserved samples or runtime adoption is
authorized by this component. Production/build inputs, frozen protocol and
existing analyzers/workflows remain unchanged. The ordinary application's
architecture and operation are unaffected, so README/AGENTS need no change.
