# AI Engine Architecture

## Purpose

Tetris AI must evaluate candidate placements without driving JavaFX controls, animations, audio, keyboard input, or observable properties. The interactive game remains the runtime owner; the AI receives a detached snapshot and returns a compact move.

## Boundary

```text
GameWorld
   |
   | immutable snapshot
   v
GameSnapshot ---> TetrisAgent ---> AiMove
                      |
                      v
                BoardSimulator
```

`GameSnapshot` contains board occupancy, the current tetromino type, and current cell coordinates. The occupied board excludes the falling piece.

`TetrisAgent` is the stable decision contract. It must not depend on `game` or `view` packages.

`AiMove` describes the control sequence supported by the current game: clockwise rotations followed by a horizontal shift. `GameWorld` remains responsible for validating and applying those actions to the live model.

## Deterministic generation

`BagPieceGenerator` owns its random generator and 7-bag state. A seed constructor is provided for tests and future benchmarks. The interactive game owns its own generator instance; AI simulations do not share global bag state.

`TetrisFactory.randomCreateTetris()` remains as a compatibility entry point, but runtime orchestration should prefer an owned `PieceGenerator`.

## Current algorithm

`HeuristicTetrisAgent` performs one-ply search:

1. Enumerate the current tetromino's unique rotation states.
2. Enumerate horizontal shifts.
3. Reject candidates that cannot follow the rotate-then-move control path.
4. Drop the candidate until collision.
5. Clear full rows in the simulated board.
6. Score cleared lines, aggregate height, holes, and bumpiness.
7. Return the highest-scoring move with deterministic tie-breaking.

The simulator reuses existing tetromino rotation behavior and `core.BoardRules`. Live `GameGrid` uses the same board rules for placement and full-row detection, so collision and row semantics do not become a second source of truth.

## Runtime integration

`F2` cycles `MANUAL -> HEURISTIC -> JEV -> MANUAL`. The current mode and Jev state are shown in the side panel.

HEURISTIC remains synchronous at the spawn boundary. JEV is asynchronous:

1. The newly spawned piece is captured as an immutable `GameSnapshot`.
2. The current piece is frozen while the remote decision is pending; the JavaFX thread keeps running.
3. The completion callback is marshalled back through `Platform.runLater`.
4. The response is applied only if its generation token, mode, and spawned piece still match.
5. A stale response is ignored.
6. A remote error or missing API key uses the local heuristic fallback and exposes `FALLBACK` or `UNAVAILABLE` state.

Manual movement is accepted only in MANUAL mode, preventing player input from invalidating an automated decision after its snapshot was taken.

## Constraints

- AI code must not mutate the live board while searching.
- JavaFX animation/audio/input classes must not enter the `ai` package.
- A candidate selected by the simulator must still pass the live `GameWorld` collision checks.
- Invalid live moves restore the previous grid state; failed movement must not remove the falling piece from collision data.
- Shared board semantics belong in `core.BoardRules`; AI-only heuristics belong in `ai`.
- Search improvements should extend the current state/action boundary rather than create a second game engine with duplicated rules.

## TypeSafe Jev integration

The network-backed path is intentionally separate from the synchronous local heuristic agent:

```text
GameSnapshot
    |
    v
MoveCandidateGenerator
    |
    +--> HeuristicTetrisAgent
    |
    +--> JevTetrisAgent
              |
              v
      TypeSafeChoiceClient
              |
              v
 POST /v1/systemone
 model = jev-latest
```

Java remains responsible for enumerating legal rotate-then-shift placements and calculating deterministic board features. `JevTetrisAgent` sends those candidates as a TypeSafe `Choice` question and maps the selected option back to the original `AiMove`.

The TypeSafe client uses JDK `HttpClient` asynchronously and Jackson for structured JSON. It reads the API key from `TYPESAFE_API_KEY`. HTTP 429 and 529 responses use exponential backoff; authentication or validation failures are not hidden by fallback inside the integration layer.

The JavaFX runtime never blocks its UI thread on Jev. `TetrisApplication` creates the optional Jev agent from `TYPESAFE_API_KEY` and injects it into `GameWorld`; the game runtime owns mode selection, pending/stale-response protection, FX-thread application, and the explicit heuristic fallback policy.
