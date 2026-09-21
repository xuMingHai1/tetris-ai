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
                                  TetrisAgent strategy
                                         |
                                         v
                                      AiMove
```

`GameSnapshot` contains board occupancy, the current tetromino type, and current cell coordinates. The occupied board excludes the falling piece.

`BoardSimulator` is the deterministic candidate generator. It owns rotate-then-move reachability, drop simulation, row clearing and board metrics, and returns only legal `PlacementCandidate` values. Each candidate contains the `AiMove`, resulting board and objective facts such as cleared lines, aggregate height, holes and bumpiness; it contains no strategy-specific weight or score.

`TetrisAgent` is the stable decision contract. Strategies consume the same deterministic game facts and choose an `AiMove`; they must not depend on `game` or `view` packages.

`AiMove` describes the control sequence supported by the current game: clockwise rotations followed by a horizontal shift. `GameWorld` remains responsible for validating and applying those actions to the live model.

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

`HeuristicTetrisAgent` then applies its weights to cleared lines, aggregate height, holes and bumpiness and returns the highest-scoring move with deterministic tie-breaking. The weights belong to the strategy, not to board simulation.

The simulator reuses existing tetromino rotation behavior and `core.BoardRules`. Live `GameGrid` uses the same board rules for placement and full-row detection, so collision and row semantics do not become a second source of truth. Future model-backed agents must choose only from these deterministic candidates instead of recreating move legality or board rules.

## Runtime integration

`F2` toggles AI mode. The current mode is shown in the side panel.

The AI decision is requested only when a new piece is spawned. Existing manual input remains available.

`TetrisAgent` remains a synchronous decision contract, while `AiDecisionExecutor` owns runtime execution. Each request runs on a virtual thread so a future remote agent cannot block the JavaFX application thread. If the configured primary agent throws, the executor evaluates the same immutable snapshot with the local `HeuristicTetrisAgent` fallback.

Each submission advances a decision generation. Completion is marshalled back through `Platform.runLater` and is applied only when the request is still current, AI mode is still enabled, the game is active, and the live falling piece is the exact piece that produced the snapshot. A newer request or an explicit AI-mode change invalidates older work, preventing a slow remote response from controlling a later piece.

`GameWorld(TetrisAgent)` is the injection boundary for remote implementations. Network/client details stay outside `GameWorld`; the world continues to consume only `GameSnapshot -> TetrisAgent -> AiMove`.

### Jev provider

`TetrisAgentFactory` selects the runtime strategy from environment configuration. The default is `HeuristicTetrisAgent`; `TETRIS_AI_AGENT=jev` explicitly selects `JevTetrisAgent` and requires `TYPESAFE_API_KEY`.

`JevTetrisAgent` asks TypeSafe System One to answer one Choice question. Candidate ids (`c0`, `c1`, ...) are generated locally and map to `PlacementCandidate` instances. The provider receives the current board plus, for each legal candidate, the move, resulting board and objective metrics. A returned id must exist in that local map before it can become an `AiMove`.

`TypeSafeSystemOneClient` is the provider adapter. It uses JDK `HttpClient`, the documented `POST https://api.typesafe.ai/v1/systemone` endpoint, `jev-latest`, bearer authentication and Jackson 3 for JSON. HTTP 429 and 529 responses are retried with bounded exponential backoff. Other provider, transport or response-shape failures are surfaced to `AiDecisionExecutor`, which falls back to the local heuristic agent.

## Constraints

- AI code must not mutate the live board while searching.
- Potentially blocking agents must execute through `AiDecisionExecutor`; never perform remote I/O on the JavaFX application thread.
- Asynchronous results must pass the current-generation and current-piece checks before they can mutate live game state.
- JavaFX animation/audio/input classes must not enter the `ai` package.
- A candidate selected by the simulator must still pass the live `GameWorld` collision checks.
- Invalid live moves restore the previous grid state; failed movement must not remove the falling piece from collision data.
- Shared board semantics belong in `core.BoardRules`; AI-only heuristics belong in `ai`.
- Jev is opt-in through `TETRIS_AI_AGENT=jev`; presence of an API key alone must not enable remote calls.
- Provider credentials come from environment variables and must not be committed or logged.
- Search improvements should extend the current state/action boundary rather than create a second game engine with duplicated rules.
