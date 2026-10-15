/*
 * Copyright (C) 2024-2026 xuMingHai
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

/** Artificial occupancy fixtures shared across test packages, not reachable-game evidence. */
public final class GoalWindowFixtures {
    private GoalWindowFixtures() { }

    public static boolean[][] heart() {
        var board = new boolean[20][10];
        for (int row = 0; row < 20; row++) {
            for (int col = 0; col < 10; col++) {
                var role = ShapeTarget.HEART.roleAtBoardCell(20, 10, row, col);
                board[row][col] = role == ShapeTarget.CellRole.REQUIRED
                        || role == ShapeTarget.CellRole.SUPPORT_ALLOWED;
            }
        }
        return board;
    }
}
