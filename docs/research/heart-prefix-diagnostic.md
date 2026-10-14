# HEART prefix-domain diagnostic (#111)

Status: implementation candidate; not accepted for merge or runtime use. The frozen
[design](../design/heart-prefix-domain.md) and HEART v1.1 are unchanged.

The existing `ShapeConstructionFeasibilityBenchmark` now has an explicit offline
HEART entry point. The ordinary search retains its unfiltered behavior. The diagnostic
filters actual production post-clear candidates through `HeartPrefixDomain`, streams
per-depth counts, and replays any clean witness through the production simulator.
It does not add an agent or change runtime factory, risk, budget, or game rules.

The isolated supervisor runs only seeds 20000–20019, depth 24, beam 128 and at most
2945 roots per seed. Each direct JVM child has a 512 MiB heap cap and a 60-second
external timeout. Timeout means `INCOMPLETE`, kills and waits for the JVM, and stops
the population. No-result/malformed output or nonzero exit means `ERROR`. Completed
unsuccessful searches are `BOUNDED_MISS`, never an impossibility proof. Metadata is
written before the first child; CPU and peak RSS are explicitly unavailable (`null`).

From a clean committed checkout with JDK 27:

```sh
./mvnw --batch-mode --no-transfer-progress verify dependency:build-classpath \
  -Dmdep.outputFile=target/prefix-classpath.txt
python3 -m unittest discover -s scripts -p 'test_prefix_diagnostic.py'
python3 scripts/run_prefix_diagnostic.py \
  --classpath "target/classes:$(cat target/prefix-classpath.txt)" \
  --source-sha "$(git rev-parse HEAD)"
```

On Windows use `mvnw.cmd`, quote the `-Dmdep.outputFile=...` argument and join
`target/classes` and the dependency classpath with `;`. Generated evidence stays in
`target/benchmark/prefix-*` and is excluded from version control. Logs contain exact
piece sequences, depth counts, terminal classification and (if found) full plans and
per-prefix hole roles, height, shape progress and line clears.

## Unresolved frozen-source integration gate

The baseline selector keeps protocol/shared-workflow changes ahead of the new mode.
The reusable workflow exposes `heart-prefix-diagnostic`; isolated diagnostic paths
select that mode once wiring is accepted. No baseline guard or protocol is relaxed.

However, the unchanged `verify_heart_production_base.py` rejects every production
change outside `ai/benchmark/` relative to its immutable registered base. Extending
the existing package-private feasibility machinery necessarily modifies
`ai/ShapeConstructionFeasibilityBenchmark.java`; the domain adapter also lives in
that package to reuse exact hole roles and spawn-gravity helpers. Consequently this
candidate cannot pass the automatic baseline gate, and merging it would also break
future baseline runs. Routing around that gate or silently updating the registered
base is not an acceptable repair.

An explicit follow-up decision on the frozen measurement extension boundary is
required before merge. Independent review identified this contract conflict; a
successful local search/test does not remove it. #94/#95/#96 remain stopped and the
independent/Windows populations remain reserved.

## Registered local diagnostic: negative result, stop this direction

On 2026-10-14 at 18:18:46 Beijing time, source
`76e8203c5339ba449bd4fef2b94866b552f5a423`, protocol SHA-256
`facddfb67ec0b26673e9372a1647770c9074987b624267e88fc4be5cf1870c4b`,
Windows 11 / OpenJDK 27+35, completed all 20 registered seeds. There were **0 clean
witnesses**, 20 `BOUNDED_MISS`, no timeout/error and no unrun seeds. The minimum
observed visual error was 2 (seed 20005), which is not clean. Aggregate generated
placements: 288572; domain-admitted placements: 16541; beam-pruned unique states:
3313. Seeds 20010/20013/20018/20019 reached depth 24; the other frontiers ended
earlier. Per-seed supervisor elapsed times ranged from 0.250 to 4.359 seconds;
these are offline process observations, not decision latency or desktop acceptance.

The fixed investment produced no witness: **stop this geometric-domain direction**
under the accepted design. Do not widen the domain, substitute seeds or increase
beam/depth to rescue it. Beam pruning and depth limits prevent an impossibility
claim; no online strategy or survival evidence follows. The frozen-source integration
conflict is separate and also remains unresolved. #94/#95/#96 stay stopped.

Generated local evidence (not committed) is preserved at `target/benchmark/`:

| File | SHA-256 |
| --- | --- |
| `prefix-raw.log` | `ba70328ad551cecdcd65a7ce5dc63927ec7cfd6272fdb68bb301379d5a2a5025` |
| `prefix-summary.json` | `f698cda2b57850f7158568b305ccdc3667574554f256ad7f309fb3d1e337769c` |
| `prefix-metadata.json` | `9d980a434734195565b033b6ff9d9c8ae33211339a56a64af4e297d0406337fb` |

Each `prefix-<seed>.log` retains its actual sequence and complete depth records.
Later edits to supervisor malformed-output handling, tests and this summary do not
change the measured Java search or domain. This population is not rerun to obtain
a different result. Local Wrapper verify passed 270 tests with zero failures/errors/
skips and Spotless; `ci-quality` passed with zero SpotBugs bugs/errors.
