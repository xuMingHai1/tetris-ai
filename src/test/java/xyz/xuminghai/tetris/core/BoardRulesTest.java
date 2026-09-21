/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BoardRulesTest {

    @Test
    void placementAllowsRowsAboveBoardButRejectsCollisionAndBounds() {
        boolean[][] board = new boolean[4][4];
        board[3][2] = true;

        assertTrue(BoardRules.canPlace(board, 4, 4, List.of(new BoardPosition(-1, 1))));
        assertFalse(BoardRules.canPlace(board, 4, 4, List.of(new BoardPosition(3, 2))));
        assertFalse(BoardRules.canPlace(board, 4, 4, List.of(new BoardPosition(4, 1))));
        assertFalse(BoardRules.canPlace(board, 4, 4, List.of(new BoardPosition(1, -1))));
        assertFalse(BoardRules.canPlace(board, 4, 4, List.of(new BoardPosition(1, 4))));
    }

    @Test
    void clearFullRowsCompactsRemainingRows() {
        boolean[][] board = {
                {true, false, false, false},
                {false, true, false, false},
                {true, true, true, true},
                {false, false, true, false}
        };

        assertEquals(1, BoardRules.clearFullRows(board));

        assertArrayEquals(new boolean[]{false, false, false, false}, board[0]);
        assertArrayEquals(new boolean[]{true, false, false, false}, board[1]);
        assertArrayEquals(new boolean[]{false, true, false, false}, board[2]);
        assertArrayEquals(new boolean[]{false, false, true, false}, board[3]);
    }
}
