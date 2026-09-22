/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ShapeTargetTest {

    @Test
    void evaluatesHeartAgainstBottomCenteredBoardRegion() {
        boolean[][] board = new boolean[20][10];

        ShapeProgress empty = ShapeTarget.HEART.progress(board);

        assertEquals(32, empty.targetCells());
        assertEquals(0, empty.matchedCells());
        assertEquals(0, empty.intrusionCells());

        String[] heart = {
                ".##..##.",
                "########",
                "########",
                ".######.",
                "..####..",
                "...##..."
        };
        int rowOffset = 14;
        int colOffset = 1;
        for (int row = 0; row < heart.length; row++) {
            for (int col = 0; col < heart[row].length(); col++) {
                if (heart[row].charAt(col) == '#') {
                    board[rowOffset + row][colOffset + col] = true;
                }
            }
        }

        ShapeProgress complete = ShapeTarget.HEART.progress(board);

        assertEquals(32, complete.matchedCells());
        assertEquals(0, complete.intrusionCells());
        assertEquals(32, complete.netScore());
        assertEquals(1.0, complete.completionRate());
    }

    @Test
    void countsWrongOccupancyOnlyInsideTargetBounds() {
        boolean[][] board = new boolean[20][10];
        board[14][1] = true;
        board[0][0] = true;

        ShapeProgress progress = ShapeTarget.HEART.progress(board);

        assertEquals(0, progress.matchedCells());
        assertEquals(1, progress.intrusionCells());
        assertEquals(-1, progress.netScore());
    }

    @Test
    void rejectsBoardSmallerThanTarget() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ShapeTarget.HEART.progress(new boolean[5][7]));
    }
}
