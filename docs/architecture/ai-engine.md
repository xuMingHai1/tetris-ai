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

`AiPlanningAgentFactory` selects the desktop runtime strategy through the action-native contract. The default remains the placement heuristic through `AiPlanningAgent.fromPlacementAgent(...)`. `TETRIS_AI_AGENT=action` selects `DeterministicActionPlanningAgent`; `jev` preserves the placement-oriented Jev strategy through the adapter; `jev-action` explicitly selects `JevActionPlanningAgent`. Both Jev modes require `TYPESAFE_API_KEY`. `TetrisAgentFactory` remains the placement-strategy factory used by placement-oriented callers and benchmarks.

`NextPieceOutlook` is the shared deterministic preview evaluation consumed by the local look-ahead baseline and both Jev modes; it is not provider-specific. `JevTetrisAgent` keeps the original placement-oriented hybrid strategy: `BoardSimulator` generates every legal placement and the heuristic retains at most the top five candidates. `JevActionPlanningAgent` uses the same safety principle over `ActionPlanCandidates`: `ActionStateSearch` first proves each path reachable, the existing heuristic ranks the resulting boards, and only the top five `AiPlan` candidates are sent to Jev. Each action candidate includes the explicit action sequence, action count, objective board metrics, resulting board and deterministic next-piece outlook when available. Jev chooses only a caller-owned candidate id (`c0`, `c1`, ...); an unknown id is rejected so `AiDecisionExecutor` can fall back locally. The provider never invents movement legality.

`TypeSafeSystemOneClient` is the provider adapter. It uses JDK `HttpClient`, the documented `POST https://api.typesafe.ai/v1/systemone` endpoint, `jev-latest`, bearer authentication and Jackson 3 for JSON. HTTP 429 and 529 responses are retried with bounded exponential backoff. Other provider, transport or response-shape failures are surfaced to `AiDecisionExecutor`, which falls back to the local heuristic agent.

## Headless evaluation

`ai.benchmark.HeadlessGameRunner` provides a deterministic evaluation path that does not start the JavaFX runtime. For each seeded 7-bag piece it first applies the same initial automatic `downMove()` that `GameWorld` performs before requesting AI, then builds the `GameSnapshot`. Placement-oriented runs resolve `AiMove` through an already-generated `BoardSimulator` candidate. Action-native runs use the distinct `runPlanning(...)` API and resolve terminal `AiPlan` values through `ActionPlanSimulator`, which reuses `ActionStateSearch` plus `BoardRules`. Both paths advance from production `PlacementCandidate` facts and keep top-edge reachability, movement, hard-drop and row-clear semantics aligned with the live decision boundary.

`BenchmarkApplication` runs one or more seeded games and reports gameplay outcome plus decision latency. It supports `heuristic`, `lookahead`, `jev`, `action` and `jev-action`. A primary strategy may be paired with a local fallback; primary exceptions, illegal moves or non-terminal action plans are counted before fallback is applied. The runner aggregates the selected `PlacementCandidate` health facts (aggregate height, holes and bumpiness) as final, average and maximum values. These metrics are observed from production candidates rather than recalculated by benchmark-specific board logic.

For Jev evaluation, `JevDecisionObservation` exposes successful valid Choice confidence, token usage, shortlist size, the one-based selected heuristic rank, and immediate selected-minus-heuristic-top metric deltas without changing the `TetrisAgent -> AiMove` contract. The benchmark aggregates these values and emits per-decision telemetry so confidence can be evaluated against actual deviations before any confidence gate is introduced. These observations do not participate in gameplay decisions.

The headless benchmark intentionally does not emulate JavaFX gravity deadlines. It measures raw strategy quality, provider reliability and full decision cost; interactive deadline behavior remains owned and tested by `GameWorld`.

## Constraints

- AI code must not mutate the live board while searching.
- Potentially blocking agents must execute through `AiDecisionExecutor`; never perform remote I/O on the JavaFX application thread.
- Asynchronous results must pass the current-generation and current-piece checks before they can mutate live game state.
- JavaFX animation/audio/input classes must not enter the `ai` package.
- Action-native planners should derive paths from `ActionStateSearch` (or an equivalent rules-backed reachability source) rather than inventing movement legality.
- Every AI plan must pass `AiPlanValidator` before live execution; structural limits belong there while board legality remains in the existing game rules.
- A candidate selected by the simulator must still pass the live `GameWorld` collision checks.
- Invalid live moves restore the previous grid state; failed movement must not remove the falling piece from collision data.
- Shared board semantics belong in `core.BoardRules`; AI-only heuristics belong in `ai`.
- Jev is opt-in through `TETRIS_AI_AGENT=jev` or `TETRIS_AI_AGENT=jev-action`; presence of an API key alone must not enable remote calls.
- Jev Choice must stay inside a deterministic reachability-backed heuristic safety shortlist; placement mode uses `BoardSimulator`, action mode uses `ActionStateSearch`/`ActionPlanCandidates`. Changing shortlist size or ranking semantics requires benchmark evidence because it changes the hybrid strategy boundary.
- Provider credentials come from environment variables and must not be committed or logged.
- Search improvements should extend the current state/action boundary rather than create a second game engine with duplicated rules.
- Benchmark simulations must advance placement strategies from `BoardSimulator` and action-native strategies from `ActionPlanSimulator`; both must converge on production `PlacementCandidate`/`BoardRules` facts rather than reimplement movement or row clearing.
- Jev benchmark runs are opt-in and may consume paid/provider quota; CI must not call the real TypeSafe API by default.
