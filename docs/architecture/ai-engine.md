# AI Engine Architecture

## Purpose

Tetris AI must evaluate candidate placements without driving JavaFX controls, animations, audio, keyboard input, or observable properties. The interactive game remains the runtime owner; the AI receives a detached snapshot and returns a compact move.

## Boundary

```text
GameWorld
   |
   | immutable snapshot
   v
GameSnapshot ---> BoardSimulator ---> PlacementCandidate(s)
                                         |
                                         v
                           placement strategy / action planner
                              |                     |
                              v                     v
                           AiMove                 AiPlan
                              |                     |
                              +-> compatibility <-+
                                      boundary
                                         |
                                         v
                                   AiAction(s)
```

`GameSnapshot` contains board occupancy, the current tetromino type, current cell coordinates, and the optional known preview-piece type. The occupied board excludes the falling piece. Live runtime and deterministic benchmark snapshots populate the preview piece; nested simulations may intentionally omit it.

`BoardSimulator` is the deterministic candidate generator. It owns rotate-then-move reachability, drop simulation, row clearing and board metrics, and returns only legal `PlacementCandidate` values. Each candidate contains the `AiMove`, resulting board and objective facts such as cleared lines, aggregate height, holes and bumpiness; it contains no strategy-specific weight or score.

`TetrisAgent` is the placement-oriented strategy contract. Existing heuristic and Jev strategies consume deterministic game facts and choose an `AiMove`; they must not depend on `game` or `view` packages. `AiPlanningAgent` is the action-native contract and returns an `AiPlan` directly. `AiPlanningAgent.fromPlacementAgent(...)` adapts existing placement agents so both styles enter the same runtime execution path.

`AiMove` remains the compact placement contract used by the current deterministic and Jev strategies. The compatibility adapter expands that placement through `AiPlan.fromPlacement(...)` into ordered `AiAction` controls and appends `HARD_DROP`. `AiAction` defines the reusable control vocabulary (`LEFT`, `RIGHT`, clockwise/counter-clockwise rotation, `SOFT_DROP`, `HARD_DROP`). `GameWorld` validates and executes the resulting plan against the live model. This preserves current agent behavior while establishing an execution model that can later express non-placement action sequences.

## Deterministic generation

`BagPieceGenerator` owns its random generator and 7-bag state. A seed constructor is provided for tests and future benchmarks. The interactive game owns its own generator instance; AI simulations do not share global bag state.

`TetrisFactory.randomCreateTetris()` remains as a compatibility entry point, but runtime orchestration should prefer an owned `PieceGenerator`.

## Current algorithm

`BoardSimulator.candidates(...)` performs deterministic one-ply simulation:

1. Enumerate the current tetromino's unique rotation states.
2. Enumerate horizontal shifts.
3. Reject candidates that cannot follow the rotate-then-move control path.
4. Drop each reachable candidate until collision.
5. Clear full rows in the simulated board.
6. Capture the resulting board and objective metrics in `PlacementCandidate`.

`HeuristicTetrisAgent` then applies its weights to cleared lines, aggregate height, holes and bumpiness and returns the highest-scoring move with deterministic tie-breaking. It also exposes that same package-local deterministic ranking so hybrid strategies can reuse the existing safety signal without duplicating weights or tie-break rules. The weights belong to the strategy, not to board simulation.

`NextPieceHeuristicTetrisAgent` is a deterministic local comparison strategy. It uses the same top-five current heuristic shortlist as Jev. For each current candidate it computes `NextPieceOutlook`, which simulates the known preview piece through `BoardSimulator` and identifies that preview piece's best response with the existing heuristic. The current candidate whose best preview response ranks highest is selected. No new weights are introduced. This strategy exists primarily to measure how much value comes from deterministic one-piece look-ahead before attributing gains to the remote model.

The simulator reuses existing tetromino rotation behavior and `core.BoardRules`. Live `GameGrid` uses the same board rules for placement and full-row detection, so collision and row semantics do not become a second source of truth. Future model-backed agents must choose only from these deterministic candidates instead of recreating move legality or board rules.

## Objective layer

`AiObjective` represents high-level intent above deterministic movement legality. The default `SURVIVAL` objective preserves the existing action baseline exactly. `TUCK_HUNTER` explores action-only opportunities, while `BUILD_SHAPE` is the first persistent creative objective and is implemented by `BuildShapeActionPlanningAgent`.

Objective planning does not invent controls or treat heuristic rank as a safety guarantee. Tuck Hunter first ranks the current action-native candidates with the existing heuristic and uses the top five only as a bounded objective search envelope. `ObjectiveSafetyBudget` compares each candidate with the SURVIVAL top-1 board facts. `ObjectiveRiskProfile` names calibrated budget presets: `STRICT (0/0/2)`, `CONSERVATIVE (0/4/4)`, `BALANCED (0/8/8)` and `RISKY (1/8/8)` for additional holes / aggregate-height delta / bumpiness delta. `ObjectiveRiskController` now selects among STRICT / CONSERVATIVE / BALANCED from the SURVIVAL top-1 resulting board. It classifies LOW risk when headroom is at least half the board (minimum 8 rows) and holes <= 1; DANGER when headroom is at most one quarter of the board (minimum 4 rows) or holes reach half the board width (minimum 4); everything else is NORMAL. LOW -> BALANCED, NORMAL -> CONSERVATIVE, DANGER -> STRICT. RISKY remains calibration-only and is never selected by the adaptive controller. Cleared-line delta is recorded but is not a hard guard in this revision.

A current action-only outcome may execute only when it passes that budget. Otherwise, when a preview piece is known, only safety-eligible current candidates are evaluated as setups. The preview piece is spawned through the shared post-spawn boundary, searched through `ActionStateSearch`, classified by `ActionPlanProvenance`, and evaluated with the same safety budget relative to its own future SURVIVAL top-1. A setup counts as useful only when it creates a safety-eligible action-only preview outcome inside the preview piece's top five. If no objective opportunity survives these guards, the current SURVIVAL top choice is returned unchanged.

This creates a three-stage planning model:

```text
AiObjective
    ↓
current heuristic top-5 search envelope
    ↓
ObjectiveSafetyBudget vs SURVIVAL top-1
    ↓
safety-eligible objective candidates
    ↓
current tuck?
    ├─ yes -> execute reachable AiPlan
    └─ no  -> deterministic preview setup evaluation
                   ↓
          future Risk Controller
                   ↓
          future safety budget
                   ↓
             reachable AiPlan
```

### BUILD_SHAPE creative objective

`ShapeTarget` defines a bottom-centered, Tetris-aware target canvas. The built-in `HEART` uses three explicit cell roles:

- `REQUIRED (#)`: visible silhouette cells that should be occupied.
- `FORBIDDEN (.)`: visible background cells that should remain empty.
- `SUPPORT_ALLOWED (+)`: physical support cells that may be occupied without affecting visual correctness.

The six-row heart silhouette is placed above a two-row support zone. This separates visual correctness from the support structures needed by gravity instead of penalizing every non-heart cell inside one rectangular occupancy mask. Board cells outside the target canvas remain outside creative scoring.

`ShapeProgress` records matched required cells, occupied forbidden cells and occupied support cells. Its visual error count is:

```text
missing required + occupied forbidden
```

Required-cell coverage alone is not completion. `cleanCompletion` is true only when every REQUIRED cell is occupied and every FORBIDDEN cell is empty. SUPPORT_ALLOWED occupancy is neutral.

`BuildShapeActionPlanningAgent` keeps SURVIVAL heuristic rank 1 as the risk baseline and uses the heuristic top five as a survival-quality prior before creative scoring. This shortlist is not itself a safety proof: every non-baseline candidate must still pass the active `ObjectiveSafetyBudget` relative to SURVIVAL top-1. LOW and NORMAL states may choose a safety-eligible shortlisted candidate that reduces visual error; ties prefer fewer occupied forbidden cells and then better survival rank. DANGER is a hard creative stop: the planner returns SURVIVAL top-1 unchanged even if another candidate would improve the shape. A full-reachable-envelope experiment was benchmarked and rejected because it increased the average reachable set from about 5 considered candidates to about 23 total reachable outcomes while adding almost no extra safety-eligible freedom, produced no clean completion improvement, and regressed survival. The top-five boundary is therefore retained as an evidence-backed quality prior rather than treated as an arbitrary search limit.

Because the target is fixed while the settled board persists across pieces, the board still carries multi-turn progress without a second mutable objective-state store. Runtime BUILD_SHAPE remains the greedy one-turn planner. A bounded preview-lookahead variant was evaluated as a benchmark-only hypothesis: each safety-eligible current top-five candidate was projected through the known preview piece, whose own action-native top five was independently guarded by its future SURVIVAL baseline, Risk Controller and Safety Budget. On the 20 × 500, seed-1000 comparison, preview reduced average visual error only from 20.386 to 20.002, reduced 500-piece survival from 19/20 to 18/20, and still produced zero clean completions. The hypothesis is therefore not adopted as the runtime planner; it remains only as a reproducible benchmark variant.

The rejected preview result also shows why aggregate required-cell coverage is not enough to diagnose creative construction. A board may reach all 32 HEART required cells while still occupying many forbidden visual cells. BUILD_SHAPE telemetry therefore includes per-game best visual error, maximum required coverage while forbidden occupancy is zero, and minimum forbidden occupancy observed at full required coverage.

`ShapeConstructionFeasibilityBenchmark` is an offline diagnostic for whether a clean HEART can be constructed through the real action-native rules for a fixed piece sequence. It expands full `ActionPlanCandidates` reachability for each seeded 7-bag piece, advances only from production `PlacementCandidate.resultingBoard()` after row clearing, deduplicates resulting boards, and retains a bounded beam ordered toward target progress. AI Benchmark #29 provided constructive evidence: among seeds 1000-1019 at depth 24 / beam 128, seed 1001 reached a clean HEART in 12 pieces and seed 1008 in 10 pieces, while every sample reached visual error <= 3. HEART is therefore known to be constructible under the real action-native rules; a bounded miss for another sequence remains inconclusive. This feasibility search is not a runtime strategy and is not constrained by the runtime BUILD_SHAPE top-five safety prior, because its purpose is reachability evidence rather than gameplay policy.

`ShapeWitnessConstraintAudit` consumes any clean feasibility witness and replays it from the empty board against the current runtime policy without changing the witness. At each step it measures the witness outcome's survival rank, whether it is action-only, whether it is inside the runtime top-five prior, the adaptive risk level/profile, the corresponding `ObjectiveSafetyBudget` assessment, and whether the current greedy BUILD_SHAPE planner would choose the same resulting board. The first blocker is classified in runtime order: `TOP_FIVE`, `DANGER_SUPPRESSION`, `SAFETY_BUDGET`, then `GREEDY_SELECTION`. AI Benchmark #30 showed that the two known clean witnesses have 19/22 steps outside top-five, 17/22 steps in DANGER and 22/22 steps rejected by the raw Safety Budget, so widening only one boundary cannot reproduce the successful paths.

The follow-up target-aware hole audit keeps runtime unchanged and explains the hole component instead of redefining it in benchmark code. `BoardOccupancyMetrics` is the single occupancy scan used by production candidate metrics; diagnostics may additionally request the exact hole coordinates from that same scan. `ShapeTarget.roleAtBoardCell(...)` maps each hole through the existing bottom-centered target anchor into `REQUIRED`, `FORBIDDEN`, `SUPPORT_ALLOWED` or `OUTSIDE_TARGET`. The only counterfactual currently evaluated is excluding `FORBIDDEN` holes, because those cells must remain empty for a clean silhouette. Required, support-allowed and outside-target holes remain separate and counted; support permission is not treated as proof of safety. The audit reports both a current-profile safety result with that adjusted hole delta and a second result after reclassifying adaptive Risk Level/Profile with forbidden holes excluded from the SURVIVAL baseline. These are evidence fields, not runtime policy.

AI Benchmark #31 showed that target-required negative space explains only part of the conflict: across the two clean witnesses, 408 raw hole observations consisted of 161 FORBIDDEN, 202 SUPPORT_ALLOWED, 9 REQUIRED and 36 OUTSIDE_TARGET cells, yet excluding only FORBIDDEN holes still left 0/22 witness steps safety-eligible. `ConstructionSafetyEnvelopeBenchmark` therefore measures the absolute states traversed by each known clean witness instead of comparing them only with SURVIVAL top-1. Per step it records headroom, maximum column height, aggregate height, raw and role-classified holes, bumpiness, survival rank, shape progress, current reachable outcomes, and the next witness piece's reachable action-native outcomes. Its min/max summaries are observations of successful construction paths, not recommended thresholds. Runtime BUILD_SHAPE, `ObjectiveSafetyBudget` and `ObjectiveRiskController` remain unchanged until separate benchmark evidence supports a policy change.

`ShapeConstructionViabilityBenchmark` adds the next calibration layer without defining a new policy. At each clean-witness step it compares the witness resulting board with that step's SURVIVAL top-1 resulting board under the exact same deterministic future 7-bag suffix. A greedy probe repeatedly follows production survival rank 1 for a longer horizon; a separate bounded existential probe expands production action-native outcomes, deduplicates resulting boards, and retains a survival-ranked beam. Reaching the requested bounded horizon is constructive continuation evidence. Frontier exhaustion is exact only when no earlier depth was pruned; exhaustion after pruning is explicitly inconclusive. This distinction prevents a bounded search miss from being mislabeled as a death proof.

The first viability run strengthened the case for a BUILD_SHAPE-specific boundary: all 22 clean-witness states survived the four-piece bounded continuation horizon, 20/22 survived a full 24-piece production-SURVIVAL greedy rollout, the remaining two survived 21 and 20 pieces, and 21/22 witness greedy horizons were at least their same-step SURVIVAL-baseline horizons. `ConstructionSafetyGuard` was therefore introduced only as a benchmark calibration primitive. It replaces raw board-metric deltas with the known preview piece's real action-native reachable-outcome count. `BuildShapeConstructionGuardPlanningAgent` deliberately evaluates the full reachable current candidate set and tests three retention profiles—preserve the SURVIVAL baseline preview capacity, retain at least half, or require any non-zero continuation. The first 10×250 comparison rejected all three as runtime safety models: current BUILD_SHAPE reached the 250-piece limit in 10/10 games, while preserve-baseline reached it in 3/10 (122.1 average pieces), retain-half in 2/10 (82.3 average), and any-continuation in 2/10 (87.2 average). Shape error improved (minimum 4~6 vs current 10) but clean completion remained zero. This demonstrates that preserving one preview piece's branching capacity is only a local viability fact; it does not prevent cumulative board degradation. The experiment remains unregistered in `AiPlanningAgentFactory`, and production BUILD_SHAPE remains unchanged.

`RecoveryRobustnessBenchmark` is the next diagnostic layer and still does not define policy. For one post-placement board it first applies the known preview piece using production SURVIVAL rank 1, then evaluates every tetromino type as the following unknown piece through production action-native reachability. The calibration compares known clean-witness states with the final five selected states before game-over from failed `preserve-baseline` guard games. It records immediate preview recovery, recovery board health, unplayable unknown piece types, min/average/max unknown-piece reachable outcomes, and worst post-unknown SURVIVAL board facts. Its purpose is feature separation: only if these facts distinguish successful construction from cumulative failure should a later PR turn them into a candidate safety policy. The first calibration found a strong observed separation in vertical reserve: all 22 clean-witness samples had recovery headroom 8–17 and worst-case post-unknown headroom 8–16, while all 35 failed-tail samples were at most 2 on both measures (or unrecoverable). The separation was already present four successful placements before game-over. By contrast, minimum unknown-piece reachable outcomes overlapped at 9 and recovery-hole counts overlapped between the classes, so branching and holes alone remain weak safety proxies. This evidence is promising but not yet a runtime threshold: the next calibration must add longer failure lead-time and healthy long-lived runtime controls to measure false positives. `RecoveryReserveLeadTimeApplication` performs that next calibration without changing the probe or runtime planner. It extends each failed preserve-baseline game to the final 30 successful placements and samples only current BUILD_SHAPE games that reach the complete piece limit, using a deterministic every-five-decisions cadence plus the final 30 decisions. Candidate recovery-headroom and post-unknown-headroom cutoffs 2/4/6/8 are swept only to measure clean/control warnings, healthy-control false positives by sample and game, failed-game detection coverage, and earliest observed warning lead-time. The sweep is evidence generation, not threshold selection. In the first 10×250 run, recovery-headroom cutoff 2 produced no clean-witness warnings, 4/740 sampled healthy-control warnings (all four consecutive states in seed 1003), and detected all 7 failed preserve-baseline games with 6–29 placements of observed lead-time (16.3 average). Higher cutoffs increased healthy false positives sharply. The four healthy low-reserve states still had only 10–11 recovery holes, whereas recoverable failed states with the same low-reserve condition had 15–60 holes. That conditional separation is a candidate feature interaction for independent validation, not permission to encode a holes threshold. `RecoveryReserveValidationApplication` performs that independent check without another parameter sweep. It freezes the calibrated interaction: an unrecoverable preview is a warning; otherwise both recovery headroom <= 2 and recovery holes >= 15 are required. On seeds 2000–2019, known clean witnesses produced 0/22 warnings; 15/20 current BUILD_SHAPE games reached 1000 pieces and contributed 3360 sampled healthy controls with zero warnings; all 16 failed preserve-baseline games were detected with 5–29 placements of lead-time (18.813 average). This confirms out-of-sample class separation but does not yet show that the signal predicts failures of the current production planner, because 5/20 current BUILD_SHAPE games themselves failed before the 1000-piece horizon and were excluded from the healthy-control population by protocol. `RecoveryReserveRuntimeValidationApplication` therefore runs the production `BuildShapeActionPlanningAgent` once per fresh seed 3000–3039 with a 1000-piece horizon. Games that reach the horizon remain sampled healthy controls; games that end early contribute only their final 30 successful placements as production failure trajectories. The frozen warning rule is reused unchanged and is not re-tuned. This stage remains observational and must pass before any candidate runtime guard is designed.

The target is occupancy-only. Existing `GameSnapshot`/resulting-board state does not preserve settled-piece colors, so BUILD_SHAPE must not infer color from tetromino type or UI rendering. Color-aware targets require an explicit future state-model change.

```text
ShapeTarget (REQUIRED / FORBIDDEN / SUPPORT_ALLOWED)
    ↓
ActionStateSearch + full survival ranking
    ↓
SURVIVAL top-1 baseline
    ↓
top-5 survival-quality prior
    ↓
ObjectiveRiskController
    ├─ DANGER -> SURVIVAL top-1
    └─ LOW/NORMAL
          ↓
ObjectiveSafetyBudget vs SURVIVAL top-1
          ↓
safety-eligible shortlist
          ↓
ShapeProgress(resultingBoard)
          ↓
best visual improvement
          ↓
AiPlan
```

The objective layer remains separate from provider integration. Non-survival objectives are currently supported only by the local `action` runtime. Jev objective integration should consume deterministic objective facts later rather than moving legality or objective simulation into the provider prompt.

## Runtime integration

`F2` toggles AI mode. The current mode is shown in the side panel.

`ActionStateSearch` is the deterministic reachability layer for action-native planning. It performs breadth-first search over legal primitive controls (`LEFT`, `RIGHT`, both rotations and `SOFT_DROP`), reusing core tetromino transformations plus `BoardRules` collision checks. Because the legacy tetromino implementations keep rotation orientation as mutable internal state, each BFS path is replayed from the captured post-spawn snapshot; this preserves the same multi-rotation sequence as the live piece instead of reconstructing every rotation from orientation 1. `ActionPlanCandidates` converts reachable landings into the same `PlacementCandidate` safety facts and ranks them with the existing heuristic. `DeterministicActionPlanningAgent` consumes the first ranked plan as the local action-native baseline.

Reachability changes are measured separately from strategy quality. The manual `AI Benchmark` workflow has a `reachability` mode that compares canonical post-lock/post-row-clear board outcomes from legacy placement search and action-native search on deterministic scenarios, including search-state count and elapsed time. This keeps movement-capability evidence independent from Jev/model quality.

`AiPlanValidator` is the structural guard between planning and live execution. Plans must be non-empty, are bounded to 64 actions, and may not contain actions after `HARD_DROP`. It deliberately does not simulate board legality; collision and reachability remain owned by the existing live game rules so validation does not create a second Tetris engine.

The AI decision is requested only when a new piece is spawned. Existing manual input remains available. A valid current `AiMove` is converted to an `AiPlan`, whose actions are executed in order. Any invalid non-hard-drop action aborts the remaining plan. `HARD_DROP` immediately descends the piece until collision instead of waiting for gravity to traverse the remaining rows; the normal gravity tick still owns lock, row-clear and next-piece lifecycle processing.

`TetrisAgent` and `AiPlanningAgent` remain synchronous strategy contracts, while `AiDecisionExecutor` owns runtime execution and normalizes both paths to validated `AiPlan` results. Each request runs on a virtual thread so a future remote agent cannot block the JavaFX application thread. If the configured primary agent throws, the executor evaluates the same immutable snapshot with the local `HeuristicTetrisAgent` fallback.

Each submission advances a decision generation. Completion is marshalled back through `Platform.runLater` and is applied only when the request is still current, AI mode is still enabled, the game is active, the live falling piece is the exact piece that produced the snapshot, and its cell coordinates still exactly match the snapshot.

A remote decision owns the snapshot only until the next live-state mutation. Before the next automatic gravity tick changes the piece, `GameWorld` checks the pending decision. If it has not completed, the remote generation is invalidated and the local heuristic fallback is evaluated immediately against the still-unchanged snapshot, then the game continues. Manual movement cancels the pending remote decision instead, so player input always wins. This makes the gameplay tick, rather than an arbitrary HTTP timeout, the effective deadline for a remote decision.

`GameWorld` accepts either the existing `TetrisAgent` placement contract or the new `AiPlanningAgent` action-native contract. Network/client details stay outside `GameWorld`. Placement agents follow `GameSnapshot -> TetrisAgent -> AiMove -> AiPlan`; action-native agents follow `GameSnapshot -> AiPlanningAgent -> AiPlan`. Both converge on the same `AiAction` execution boundary.

### Jev provider

`AiPlanningAgentFactory` selects the desktop runtime strategy through the action-native contract and reads `TETRIS_AI_OBJECTIVE` for the local `action` planner. The default remains the placement heuristic through `AiPlanningAgent.fromPlacementAgent(...)`. `TETRIS_AI_AGENT=action` selects `DeterministicActionPlanningAgent`; `jev` preserves the placement-oriented Jev strategy through the adapter; `jev-action` explicitly selects `JevActionPlanningAgent`. Both Jev modes require `TYPESAFE_API_KEY`. `TetrisAgentFactory` remains the placement-strategy factory used by placement-oriented callers and benchmarks.

`NextPieceOutlook` is the shared deterministic preview evaluation consumed by the local look-ahead baseline and both Jev modes; it is not provider-specific. `JevTetrisAgent` keeps the original placement-oriented hybrid strategy: `BoardSimulator` generates every legal placement and the heuristic retains at most the top five candidates. `JevActionPlanningAgent` uses the same safety principle over `ActionPlanCandidates`: `ActionStateSearch` first proves each path reachable, the existing heuristic ranks the resulting boards, and only the top five `AiPlan` candidates are sent to Jev. Each action candidate includes the explicit action sequence, action count, objective board metrics, resulting board and deterministic next-piece outlook when available. Jev chooses only a caller-owned candidate id (`c0`, `c1`, ...); an unknown id is rejected so `AiDecisionExecutor` can fall back locally. The provider never invents movement legality.

`TypeSafeSystemOneClient` is the provider adapter. It uses JDK `HttpClient`, the documented `POST https://api.typesafe.ai/v1/systemone` endpoint, `jev-latest`, bearer authentication and Jackson 3 for JSON. HTTP 429 and 529 responses are retried with bounded exponential backoff. Other provider, transport or response-shape failures are surfaced to `AiDecisionExecutor`, which falls back to the local heuristic agent.

## Headless evaluation

`ai.benchmark.HeadlessGameRunner` provides a deterministic evaluation path that does not start the JavaFX runtime. For each seeded 7-bag piece it first applies the same initial automatic `downMove()` that `GameWorld` performs before requesting AI, then builds the `GameSnapshot`. Placement-oriented runs resolve `AiMove` through an already-generated `BoardSimulator` candidate. Action-native runs use the distinct `runPlanning(...)` API and resolve terminal `AiPlan` values through `ActionPlanSimulator`, which reuses `ActionStateSearch` plus `BoardRules`. Both paths advance from production `PlacementCandidate` facts and keep top-edge reachability, movement, hard-drop and row-clear semantics aligned with the live decision boundary.

`BenchmarkApplication` runs one or more seeded games and reports gameplay outcome plus decision latency. It supports `heuristic`, `lookahead`, `jev`, `action`, fixed-profile `tuck-hunter`, `adaptive-tuck-hunter`, `build-shape`, benchmark-only `build-shape-preview`, `action-provenance` and `jev-action`. A primary strategy may be paired with a local fallback; primary exceptions, illegal moves or non-terminal action plans are counted before fallback is applied. The runner aggregates the selected `PlacementCandidate` health facts (aggregate height, holes and bumpiness) as final, average and maximum values. These metrics are observed from production candidates rather than recalculated by benchmark-specific board logic. `action-provenance` is a local-only diagnostic mode: `ActionProvenanceBenchmark` ranks each real action state once, returns the same deterministic best `AiPlan`, then classifies the full ranking against legacy placement outcomes. It records total action candidates, total action-only candidates, the best action-only heuristic rank and how many action-only candidates fall inside the top-five shortlist.

For Jev evaluation, `JevDecisionObservation` exposes successful valid Choice confidence, token usage, shortlist size, the one-based selected heuristic rank, and immediate selected-minus-heuristic-top metric deltas without changing the strategy contracts. For `jev-action`, `ActionPlanProvenance` additionally classifies the already-ranked shortlist against legacy `BoardSimulator` outcomes using the same post-lock/post-row-clear board equivalence as the reachability benchmark. The benchmark reports action-only candidate count/rate and whether Jev selected an action-only outcome. Provenance is computed only after ranking, is not sent to the provider, and never participates in gameplay decisions. The benchmark aggregates these values and emits per-decision telemetry so confidence, heuristic deviation and actual use of expanded action reachability can be evaluated independently.

`build-shape` emits Tetris-aware target telemetry: full reachable-candidate count, actual creative shortlist count, safety-eligible count, selected survival rank, required matches, forbidden/support occupancy, visual-error delta, required-cell coverage, exact clean completion, danger suppression, risk profile and safety filtering. It also emits per-game construction diagnostics: best visual error, maximum required coverage while forbidden occupancy is zero, minimum forbidden occupancy at full required coverage, and whether that game ever achieved clean completion. Aggregate summaries count games reaching visual-error thresholds 8, 4 and 0. `build-shape-preview` emits the same telemetry with a distinct strategy label. `compare-shape` runs SURVIVAL vs greedy BUILD_SHAPE; `compare-shape-preview` remains available to reproduce the rejected one-piece preview experiment. `compare-shape-guard` keeps current BUILD_SHAPE as the baseline and compares the three benchmark-only preview-continuation guard profiles on identical seeds; guard telemetry records full reachable count, guard checks/rejections, selected survival rank, baseline/selected preview capacity and selected headroom. `recovery-robustness` is a separate classifier-style diagnostic over clean-witness and failed-tail states and never participates in gameplay selection.

The separate `ShapeFeasibilityApplication` runs the bounded construction search across one or more seeded 7-bag sequences. It uses dedicated depth and beam-width inputs instead of the normal 500-piece gameplay default, writes one result row per seed, and emits a witness row for each piece only when clean completion is found. Each clean witness is then passed through `ShapeWitnessConstraintAudit`, `ConstructionSafetyEnvelopeBenchmark`, and `ShapeConstructionViabilityBenchmark`; they respectively explain current runtime blockers, record absolute successful-path board health, and compare witness-vs-SURVIVAL continuation under the same future piece sequence. All three are observational telemetry only. This path remains fully local and deterministic.

`tuck-hunter` emits separate objective telemetry: whether the selected move directly executes an action-only outcome, how many top-five candidates pass or fail the safety budget, the controller's risk level/profile plus baseline headroom/holes, the selected candidate's metric deltas versus SURVIVAL top-1, and the rank/count of the selected preview opportunity. `compare-adaptive` compares the SURVIVAL baseline with the adaptive runtime controller on identical seeds. `calibrate-objective` still runs the SURVIVAL baseline plus all four fixed `ObjectiveRiskProfile` values on the same seed range.

The local provenance scan is deliberately separate from Jev telemetry. It can run across thousands of deterministic states without provider cost and answers whether expanded action reachability is absent in ordinary play or merely filtered out by the top-five shortlist. Its provenance observations do not change ranking or the selected deterministic plan.

The headless benchmark intentionally does not emulate JavaFX gravity deadlines. It measures raw strategy quality, provider reliability and full decision cost; interactive deadline behavior remains owned and tested by `GameWorld`.

## Constraints

- AI code must not mutate the live board while searching.
- Potentially blocking agents must execute through `AiDecisionExecutor`; never perform remote I/O on the JavaFX application thread.
- Asynchronous results must pass the current-generation and current-piece checks before they can mutate live game state.
- JavaFX animation/audio/input classes must not enter the `ai` package.
- Action-native planners should derive paths from `ActionStateSearch` (or an equivalent rules-backed reachability source) rather than inventing movement legality.
- High-level objectives may change preference among deterministic reachable plans but must not replace movement legality, collision or row clearing. Heuristic rank is never proof of safety. Production BUILD_SHAPE currently retains heuristic top-5 plus the SURVIVAL-relative safety boundary because the earlier unguarded full-reachable experiment regressed long-term survival. Clean-witness blocker and viability evidence now justify benchmark-only experiments that widen to full reachable candidates behind an explicit construction viability guard; those experiments must not be promoted to runtime solely because known positive witnesses pass.
- Creative objectives must score immutable resulting-board facts after production row-clear semantics; they must not maintain a second simulated board or infer settled-piece color from occupancy-only state. Physical support that is intentionally excluded from the visible silhouette must be represented explicitly rather than silently treated as visual success or failure.
- A non-survival objective must pass an explicit `ObjectiveSafetyBudget` relative to the current SURVIVAL top choice and must fall back to that SURVIVAL choice when its objective-specific opportunity is unavailable or exceeds the budget. BUILD_SHAPE additionally disables creative deviation entirely while the Risk Controller reports DANGER.
- Adaptive runtime risk selection must be derived from the SURVIVAL baseline state, not from an objective candidate; this avoids changing the risk budget as a side effect of the candidate being evaluated.
- Adaptive runtime control must preserve `additional holes == 0`; `ObjectiveRiskProfile.RISKY` remains calibration-only unless new benchmark evidence justifies changing this boundary.
- Every AI plan must pass `AiPlanValidator` before live execution; structural limits belong there while board legality remains in the existing game rules.
- A candidate selected by the simulator must still pass the live `GameWorld` collision checks.
- Invalid live moves restore the previous grid state; failed movement must not remove the falling piece from collision data.
- Shared board semantics belong in `core.BoardRules`; AI-only heuristics belong in `ai`.
- Jev is opt-in through `TETRIS_AI_AGENT=jev` or `TETRIS_AI_AGENT=jev-action`; presence of an API key alone must not enable remote calls.
- Jev Choice must stay inside a deterministic reachability-backed heuristic safety shortlist; placement mode uses `BoardSimulator`, action mode uses `ActionStateSearch`/`ActionPlanCandidates`. Changing shortlist size or ranking semantics requires benchmark evidence because it changes the hybrid strategy boundary.
- Provider credentials come from environment variables and must not be committed or logged.
- Search improvements should extend the current state/action boundary rather than create a second game engine with duplicated rules.
- Benchmark simulations must advance placement strategies from `BoardSimulator` and action-native strategies from `ActionPlanSimulator`; both must converge on production `PlacementCandidate`/`BoardRules` facts rather than reimplement movement or row clearing. Offline feasibility search may traverse multiple production action-native outcomes directly through `ActionPlanCandidates`, but a bounded miss must never be described as proof that a target is impossible.
- Jev benchmark runs are opt-in and may consume paid/provider quota; CI must not call the real TypeSafe API by default.
