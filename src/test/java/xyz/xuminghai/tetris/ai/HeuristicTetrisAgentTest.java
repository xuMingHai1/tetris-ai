/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import org.junit.jupiter.api.Test;
import xyz.xuminghai.tetris.core.TetrominoType;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HeuristicTetrisAgentTest {

    @Test
    void completesAnAlmostFullBottomRowWithHorizontalIBlock() {
        boolean[][] board = new boolean[20][10];
        for (int col = 0; col < 6; col++) {
            board[19][col] = true;
        }

        GameSnapshot snapshot = new GameSnapshot(
                20,
                10,
                board,
                TetrominoType.I,
                List.of(
                        new BoardPosition(-1, 3),
                        new BoardPosition(-1, 4),
                        new BoardPosition(-1, 5),
                        new BoardPosition(-1, 6)));

        AiMove move = new HeuristicTetrisAgent().decide(snapshot);

        assertEquals(new AiMove(0, 3), move);
    }
}
