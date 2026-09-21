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

`F2` toggles AI mode. The current mode is shown in the side panel.

The AI decision is made only when a new piece is spawned. Existing manual input remains available. AI calculation is intentionally small enough to run synchronously at the spawn boundary; deeper search must re-evaluate this latency assumption before being added.

## Constraints

- AI code must not mutate the live board while searching.
- JavaFX animation/audio/input classes must not enter the `ai` package.
- A candidate selected by the simulator must still pass the live `GameWorld` collision checks.
- Invalid live moves restore the previous grid state; failed movement must not remove the falling piece from collision data.
- Shared board semantics belong in `core.BoardRules`; AI-only heuristics belong in `ai`.
- Search improvements should extend the current state/action boundary rather than create a second game engine with duplicated rules.
