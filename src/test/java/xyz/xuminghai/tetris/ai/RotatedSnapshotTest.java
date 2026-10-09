/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import xyz.xuminghai.tetris.core.BoardPosition;
import xyz.xuminghai.tetris.core.BoardRules;
import xyz.xuminghai.tetris.core.Cell;
import xyz.xuminghai.tetris.core.Tetris;
import xyz.xuminghai.tetris.core.TetrisFactory;
import xyz.xuminghai.tetris.core.TetrominoType;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RotatedSnapshotTest {
    @ParameterizedTest
    @EnumSource(value = TetrominoType.class, names = "O", mode = EnumSource.Mode.EXCLUDE)
    void replayMatchesLiveRotationFromAnAlreadyRotatedSnapshot(TetrominoType type) {
        Tetris live = TetrisFactory.create(type);
        for (int step = 0; step < 8; step++) {
            live.downMove();
        }
        live.rotateClockwise();
        GameSnapshot snapshot = new GameSnapshot(
                20, 10, new boolean[20][10], type, positions(live),
                live.rotationState(), Optional.empty());
        live.rotateClockwise();
        assertEquals(positions(live), ActionStateSearch.replay(
                snapshot, List.of(AiAction.ROTATE_CLOCKWISE)));
    }

    @ParameterizedTest
    @EnumSource(TetrominoType.class)
    void allOrientationsReplayBothDirectionsAndDirectionChanges(TetrominoType type) {
        for (int orientation = 0; orientation < type.rotationStates(); orientation++) {
            for (List<AiAction> actions : List.of(
                    List.of(AiAction.ROTATE_CLOCKWISE),
                    List.of(AiAction.ROTATE_COUNTER_CLOCKWISE),
                    List.of(AiAction.ROTATE_CLOCKWISE, AiAction.ROTATE_COUNTER_CLOCKWISE),
                    List.of(AiAction.ROTATE_COUNTER_CLOCKWISE, AiAction.ROTATE_CLOCKWISE))) {
                Tetris live = piece(type, orientation);
                GameSnapshot snapshot = snapshot(live, new boolean[20][10]);
                actions.forEach(action -> apply(live, action));
                assertEquals(positions(live), ActionStateSearch.replay(snapshot, actions),
                        type + " orientation=" + orientation + " actions=" + actions);
            }
        }
    }

    @ParameterizedTest
    @EnumSource(value = TetrominoType.class, names = "O", mode = EnumSource.Mode.EXCLUDE)
    void replansFromRotatedPieceAfterAnActionIsBlocked(TetrominoType type) {
        Tetris live = piece(type, 0);
        // A successful prefix has already changed both orientation and position.
        live.rotateCounterClockwise();
        live.downMove();
        List<BoardPosition> before = positions(live);
        Cell[] beforeCells = live.copy();
        live.rightMove();
        BoardPosition obstacle = positions(live).stream()
                .filter(cell -> !before.contains(cell)).findFirst().orElseThrow();
        live.setCells(beforeCells);
        boolean[][] board = new boolean[20][10];
        board[obstacle.row()][obstacle.col()] = true;
        GameSnapshot snapshot = snapshot(live, board);
        assertNull(ActionStateSearch.replay(snapshot, List.of(AiAction.RIGHT)));

        AiPlan replanned = new DeterministicActionPlanningAgent().plan(snapshot);
        assertFalse(replanned.actions().isEmpty());
        boolean[][] expected = executeToBoard(live, board, replanned);
        assertArrayEquals(expected,
                ActionPlanSimulator.requireTerminalPlacement(snapshot, replanned).resultingBoard());
    }

    @ParameterizedTest
    @EnumSource(TetrominoType.class)
    void placementFallbackCandidatesMatchLiveExecutionFromEveryOrientation(TetrominoType type) {
        for (int orientation = 0; orientation < type.rotationStates(); orientation++) {
            GameSnapshot snapshot = snapshot(piece(type, orientation), new boolean[20][10]);
            List<PlacementCandidate> candidates = BoardSimulator.candidates(snapshot);
            assertFalse(candidates.isEmpty());
            for (PlacementCandidate candidate : candidates) {
                boolean[][] actual = executeToBoard(piece(type, orientation), snapshot.occupied(),
                        AiPlan.fromPlacement(candidate.move()));
                assertArrayEquals(candidate.resultingBoard(), actual,
                        type + " orientation=" + orientation + " move=" + candidate.move());
            }
        }
    }

    @ParameterizedTest
    @EnumSource(TetrominoType.class)
    void rejectsRotationStatesOutsideThePieceCycle(TetrominoType type) {
        List<BoardPosition> cells = positions(piece(type, 0));
        assertThrows(IllegalArgumentException.class, () -> new GameSnapshot(
                20, 10, new boolean[20][10], type, cells, -1, Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new GameSnapshot(
                20, 10, new boolean[20][10], type, cells, type.rotationStates(), Optional.empty()));
    }

    private static Tetris piece(TetrominoType type, int orientation) {
        Tetris live = TetrisFactory.create(type);
        for (int step = 0; step < 8; step++) {
            live.downMove();
        }
        for (int step = 0; step < orientation; step++) {
            live.rotateClockwise();
        }
        return live;
    }

    private static GameSnapshot snapshot(Tetris live, boolean[][] board) {
        return new GameSnapshot(20, 10, board, TetrominoType.from(live), positions(live),
                live.rotationState(), Optional.of(TetrominoType.I));
    }

    private static boolean[][] executeToBoard(Tetris live, boolean[][] board, AiPlan plan) {
        for (AiAction action : plan.actions()) {
            if (action == AiAction.HARD_DROP) {
                while (true) {
                    Cell[] before = live.copy();
                    live.downMove();
                    if (!BoardRules.canPlace(board, 20, 10, positions(live))) {
                        live.setCells(before);
                        break;
                    }
                }
            } else {
                apply(live, action);
                assertTrue(BoardRules.canPlace(board, 20, 10, positions(live)));
            }
        }
        for (BoardPosition cell : positions(live)) {
            board[cell.row()][cell.col()] = true;
        }
        BoardRules.clearFullRows(board);
        return board;
    }

    private static void apply(Tetris live, AiAction action) {
        switch (action) {
            case LEFT -> live.leftMove();
            case RIGHT -> live.rightMove();
            case SOFT_DROP -> live.downMove();
            case ROTATE_CLOCKWISE -> live.rotateClockwise();
            case ROTATE_COUNTER_CLOCKWISE -> live.rotateCounterClockwise();
            case HARD_DROP -> throw new IllegalArgumentException("terminal action");
        }
    }

    private static List<BoardPosition> positions(Tetris tetris) {
        return Arrays.stream(tetris.getCells())
                .map(cell -> new BoardPosition(cell.getRow(), cell.getCol())).toList();
    }
}
