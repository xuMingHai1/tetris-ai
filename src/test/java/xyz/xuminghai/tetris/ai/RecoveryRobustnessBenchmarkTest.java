/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import org.junit.jupiter.api.Test;
import xyz.xuminghai.tetris.core.TetrominoType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecoveryRobustnessBenchmarkTest {

    @Test
    void emptyBoardRemainsRecoverableAcrossEveryTetrominoType() {
        RecoveryRobustnessBenchmark.Probe probe =
                RecoveryRobustnessBenchmark.probe(
                        new boolean[20][10],
                        TetrominoType.T);

        assertTrue(probe.previewRecoverable());
        assertTrue(probe.previewReachableOutcomes() > 0);
        assertEquals(TetrominoType.values().length, probe.unknownPieceTypes());
        assertEquals(0, probe.unplayableUnknownTypes());
        assertTrue(probe.minUnknownReachableOutcomes() > 0);
        assertTrue(probe.averageUnknownReachableOutcomes() > 0.0);
        assertTrue(probe.minPostUnknownHeadroom() > 0);
        assertTrue(probe.fullyRecoverableAcrossTetrominoes());
    }

    @Test
    void blockedBoardReportsUnrecoverablePreviewWithoutInventingRecoveryFacts() {
        boolean[][] board = new boolean[20][10];
        for (boolean[] row : board) {
            java.util.Arrays.fill(row, true);
        }

        RecoveryRobustnessBenchmark.Probe probe =
                RecoveryRobustnessBenchmark.probe(
                        board,
                        TetrominoType.I);

        assertFalse(probe.previewRecoverable());
        assertEquals(0, probe.previewReachableOutcomes());
        assertEquals(-1, probe.recoveryHeadroom());
        assertEquals(TetrominoType.values().length, probe.unplayableUnknownTypes());
        assertEquals(0, probe.minUnknownReachableOutcomes());
        assertEquals(-1, probe.minPostUnknownHeadroom());
        assertFalse(probe.fullyRecoverableAcrossTetrominoes());
    }
}
