/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import org.junit.jupiter.api.Test;
import xyz.xuminghai.tetris.core.BoardPosition;
import xyz.xuminghai.tetris.core.Tetris;
import xyz.xuminghai.tetris.core.TetrisFactory;
import xyz.xuminghai.tetris.core.TetrominoType;

import java.util.Arrays;
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
    void sharedOccupancyMetricsExposeTheSameHoleDefinitionWithCoordinates() {
        boolean[][] board = new boolean[20][10];
        board[17][4] = true;
        board[19][4] = true;

        BoardOccupancyMetrics.Analysis metrics =
                BoardOccupancyMetrics.analyzeWithHoleCells(board);

        assertEquals(3, metrics.aggregateHeight());
        assertEquals(3, metrics.maxColumnHeight());
        assertEquals(17, metrics.headroom());
        assertEquals(1, metrics.holes());
        assertEquals(6, metrics.bumpiness());
        assertEquals(List.of(new BoardPosition(18, 4)), metrics.holeCells());
    }

    @Test
    void previewPieceCandidatesUseTheLivePostSpawnGravityBoundary() {
        boolean[][] board = new boolean[20][10];
        Tetris expectedPiece = TetrisFactory.create(TetrominoType.T);
        expectedPiece.downMove();
        GameSnapshot expectedSnapshot = new GameSnapshot(
                20,
                10,
                board,
                TetrominoType.T,
                Arrays.stream(expectedPiece.getCells())
                        .map(cell -> new BoardPosition(cell.getRow(), cell.getCol()))
                        .toList());

        assertEquals(
                BoardSimulator.candidates(expectedSnapshot).stream().map(PlacementCandidate::move).toList(),
                BoardSimulator.candidatesForSpawnedPiece(board, TetrominoType.T).stream()
                        .map(PlacementCandidate::move)
                        .toList());
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
