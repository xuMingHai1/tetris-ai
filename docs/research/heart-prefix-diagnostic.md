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
