# Core mutation testing baseline

## Scope and reproduction

Issue [#84](https://github.com/xuMingHai1/tetris-ai/issues/84) strengthens the existing
`xyz.xuminghai.tetris.core.*` mutation scope. PIT only selects `core.*Test` tests;
AI-package tests are deliberately not counted as core mutation evidence. No target,
mutator or exclusion is removed. Runtime code is unchanged.

Use JDK 27 and the repository Maven 3.9.16 Wrapper:

```bash
./mvnw --batch-mode --no-transfer-progress verify
./mvnw --batch-mode --no-transfer-progress -Pci-mutation verify
```

On Windows use `mvnw.cmd`. Reports are generated in `target/pit-reports/`
(`index.html` and `mutations.xml`); retain them as CI artifacts rather than commit
generated reports. The existing Mutation Test CI job runs this same profile.

## Observed baseline and classification

The unmodified baseline is default-branch commit
[`9995c0f`](https://github.com/xuMingHai1/tetris-ai/commit/9995c0f1e7c5e73f323a552b8b152cc8059c25e6).
Local JDK 27/PIT 1.30.0 measurements on 2026-10-09:

| Measurement | Before | After behavior tests |
| --- | ---: | ---: |
| Generated mutations | 313 | 313 |
| Killed | 166 (53%) | 308 (98%) |
| No coverage | 26 | 0 |
| Survived | 121 | 5 |
| Mutated-class line coverage | 355/396 (90%) | 383/396 (97%) |
| Test strength | 58% | 98% |

These are observed results, not an assertion that every future PIT/JDK version
will generate the same mutations. The older issue's 309-mutation report predates
the captured rotation-state change; it is not the current baseline.

The baseline XML was classified by method, mutation and status:

- **Real behavior gaps:** J/L/T/S/Z intermediate rotation coordinate updates can
  be corrupted and then overwritten before a full-cycle assertion. Tests now
  check ordered coordinates, direction, wrap-around, rotation state and translated
  live pieces for every orientation, using explicit fixtures. Movement and
  rotation return arrays are also part of the caller-facing contract.
- **Real rule/validation gaps:** top-row occupancy and last visible boundaries;
  candidate full-row discovery including null/out-of-range entries; consecutive,
  separated, all and no full-row compaction; empty/zero-width/ragged/mismatched
  boards; immutable position translations. Assertions check resulting boards and
  rejection behavior, not just method execution.
- **Real equality gaps:** identity, null, other types, row differences and null
  colors were unexercised branches. Equal-cell hash consistency is tested.
- **Remaining contract-equivalent hash variants:** five `Cell.hashCode` mutants
  change arithmetic or return a constant. Their numeric outputs are not equivalent
  to the original, and distribution can be worse, but equal Cells still have equal
  hashes. There is no specified exact hash formula or distribution guarantee.
  Tests do not freeze numeric hash values merely to improve the mutation score.
  All five remain in the denominator; no exclusions hide them.
- **Invalid mutations:** none identified among the generated mutations. Rejected
  input tests kill validation boundary mutations; they are not dismissed as
  invalid merely because normal gameplay supplies valid boards.

## Regression gate and limits

The `ci-mutation` profile now fails below **95% mutation score** or **95% mutated-class
line coverage**. The measured 98%/97% baseline leaves three/two percentage points
of headroom for modest mutation/line-count changes while detecting substantial
regression from this test suite. These are aggregate floors, not a guarantee that
a specific rule has adequate assertions. Review the XML after core, test, PIT or
JDK changes; improve tests or explain a changed baseline instead of lowering gates
or expanding exclusions to obtain a green build.

To exercise the failure path without editing the POM, run the existing profile
with an intentionally unreachable score after compiling tests:

```bash
./mvnw --batch-mode --no-transfer-progress -Pci-mutation "-Dpit.mutationThreshold=100" org.pitest:pitest-maven:mutationCoverage
```

This must fail because the retained hash mutants survive. Similarly,
`"-Dpit.coverageThreshold=100"` exercises the coverage floor. These overrides are
local diagnostic commands, not CI settings.

Ordinary `verify` validates the full unit suite but does not run mutation testing.
PIT measures selected core tests' ability to detect synthetic implementation
changes; its line coverage applies only to mutated classes. Desktop AI Timing
observes real Linux JavaFX playback, outside this scope. Neither passing core tests
nor PIT proves JavaFX interaction, animation, Windows gravity timing or DPI behavior.
