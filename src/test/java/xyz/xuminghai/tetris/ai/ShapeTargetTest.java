/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShapeTargetTest {

    @Test
    void evaluatesCleanHeartAboveSupportZone() {
        boolean[][] board = new boolean[20][10];

        ShapeProgress empty = ShapeTarget.HEART.progress(board);

        assertEquals(32, empty.requiredCells());
        assertEquals(16, empty.forbiddenCells());
        assertEquals(0, empty.matchedRequiredCells());
        assertEquals(0, empty.forbiddenOccupiedCells());
        assertEquals(0, empty.supportOccupiedCells());
        assertEquals(32, empty.visualErrorCells());
        assertFalse(empty.cleanCompletion());

        String[] heart = {
                ".##..##.",
                "########",
                "########",
                ".######.",
                "..####..",
                "...##..."
        };
        int rowOffset = 12;
        int colOffset = 1;
        for (int row = 0; row < heart.length; row++) {
            for (int col = 0; col < heart[row].length(); col++) {
                if (heart[row].charAt(col) == '#') {
                    board[rowOffset + row][colOffset + col] = true;
                }
            }
        }

        // Support is physically allowed below the visual silhouette and must not spoil completion.
        board[18][4] = true;
        board[19][4] = true;

        ShapeProgress complete = ShapeTarget.HEART.progress(board);

        assertEquals(32, complete.matchedRequiredCells());
        assertEquals(0, complete.forbiddenOccupiedCells());
        assertEquals(2, complete.supportOccupiedCells());
        assertEquals(0, complete.visualErrorCells());
        assertEquals(32, complete.netScore());
        assertEquals(1.0, complete.requiredCompletionRate());
        assertTrue(complete.cleanCompletion());
    }

    @Test
    void distinguishesForbiddenOccupancyFromAllowedSupport() {
        boolean[][] board = new boolean[20][10];

        board[12][1] = true; // visual '.' in the first heart row
        board[18][1] = true; // support '+'
        board[0][0] = true; // outside the target canvas

        ShapeProgress progress = ShapeTarget.HEART.progress(board);

        assertEquals(0, progress.matchedRequiredCells());
        assertEquals(1, progress.forbiddenOccupiedCells());
        assertEquals(1, progress.supportOccupiedCells());
        assertEquals(33, progress.visualErrorCells());
        assertEquals(-1, progress.netScore());
        assertFalse(progress.cleanCompletion());
    }

    @Test
    void fullRequiredCoverageIsNotCleanWhenForbiddenCellIsOccupied() {
        boolean[][] board = new boolean[20][10];
        String[] heart = {
                ".##..##.",
                "########",
                "########",
                ".######.",
                "..####..",
                "...##..."
        };
        int rowOffset = 12;
        int colOffset = 1;
        for (int row = 0; row < heart.length; row++) {
            for (int col = 0; col < heart[row].length(); col++) {
                if (heart[row].charAt(col) == '#') {
                    board[rowOffset + row][colOffset + col] = true;
                }
            }
        }
        board[12][1] = true;

        ShapeProgress progress = ShapeTarget.HEART.progress(board);

        assertEquals(1.0, progress.requiredCompletionRate());
        assertEquals(1, progress.forbiddenOccupiedCells());
        assertFalse(progress.cleanCompletion());
    }

    @Test
    void mapsBoardCoordinatesToTheSameBottomCenteredTargetRoles() {
        assertEquals(
                ShapeTarget.CellRole.FORBIDDEN,
                ShapeTarget.HEART.roleAtBoardCell(20, 10, 12, 1));
        assertEquals(
                ShapeTarget.CellRole.REQUIRED,
                ShapeTarget.HEART.roleAtBoardCell(20, 10, 12, 2));
        assertEquals(
                ShapeTarget.CellRole.SUPPORT_ALLOWED,
                ShapeTarget.HEART.roleAtBoardCell(20, 10, 18, 1));
        assertEquals(
                ShapeTarget.CellRole.OUTSIDE_TARGET,
                ShapeTarget.HEART.roleAtBoardCell(20, 10, 11, 1));
        assertEquals(
                ShapeTarget.CellRole.OUTSIDE_TARGET,
                ShapeTarget.HEART.roleAtBoardCell(20, 10, 12, 0));
    }

    @Test
    void rejectsBoardSmallerThanTargetCanvas() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ShapeTarget.HEART.progress(new boolean[7][7]));
    }
}
