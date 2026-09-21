/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import org.junit.jupiter.api.Test;
import xyz.xuminghai.tetris.core.BoardPosition;
import xyz.xuminghai.tetris.core.TetrominoType;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoardSimulatorTest {

    @Test
    void describesPlacementMetricsWithoutHeuristicWeights() {
        GameSnapshot snapshot = emptyBoardWithHorizontalIBlock();

        PlacementCandidate candidate = BoardSimulator.candidates(snapshot).stream()
                .filter(value -> value.move().equals(AiMove.NONE))
                .findFirst()
                .orElseThrow();

        assertEquals(0, candidate.clearedLines());
        assertEquals(4, candidate.aggregateHeight());
        assertEquals(0, candidate.holes());
        assertEquals(2, candidate.bumpiness());
    }

    @Test
    void describesBoardAfterLineClear() {
        boolean[][] board = new boolean[20][10];
        for (int col = 0; col < 6; col++) {
            board[19][col] = true;
        }

        PlacementCandidate candidate = BoardSimulator.candidates(horizontalIBlock(board)).stream()
                .filter(value -> value.move().equals(new AiMove(0, 3)))
                .findFirst()
                .orElseThrow();

        assertEquals(1, candidate.clearedLines());
        assertEquals(0, candidate.aggregateHeight());
        assertEquals(0, candidate.holes());
        assertEquals(0, candidate.bumpiness());
        assertFalse(hasOccupiedCell(candidate.resultingBoard()));
    }

    @Test
    void candidateOrderIsDeterministicAndResultingBoardIsDefensive() {
        GameSnapshot snapshot = emptyBoardWithHorizontalIBlock();

        List<PlacementCandidate> first = BoardSimulator.candidates(snapshot);
        List<PlacementCandidate> second = BoardSimulator.candidates(snapshot);

        assertEquals(first.stream().map(PlacementCandidate::move).toList(),
                second.stream().map(PlacementCandidate::move).toList());
        assertTrue(first.stream().map(PlacementCandidate::move).distinct().count() > 1);

        PlacementCandidate candidate = first.getFirst();
        boolean originalCell = candidate.resultingBoard()[0][0];
        boolean[][] exposedBoard = candidate.resultingBoard();
        exposedBoard[0][0] = !originalCell;

        assertEquals(originalCell, candidate.resultingBoard()[0][0]);
    }

    private static GameSnapshot emptyBoardWithHorizontalIBlock() {
        return horizontalIBlock(new boolean[20][10]);
    }

    private static GameSnapshot horizontalIBlock(boolean[][] board) {
        return new GameSnapshot(
                20,
                10,
                board,
                TetrominoType.I,
                List.of(
                        new BoardPosition(-1, 3),
                        new BoardPosition(-1, 4),
                        new BoardPosition(-1, 5),
                        new BoardPosition(-1, 6)));
    }

    private static boolean hasOccupiedCell(boolean[][] board) {
        for (boolean[] row : board) {
            for (boolean occupied : row) {
                if (occupied) {
                    return true;
                }
            }
        }
        return false;
    }
}
