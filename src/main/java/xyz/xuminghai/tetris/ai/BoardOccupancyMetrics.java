/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import xyz.xuminghai.tetris.core.BoardPosition;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Shared occupancy-only board metrics used by production candidate facts and diagnostics.
 *
 * <p>The production planner only needs aggregate values, while benchmark diagnostics may request
 * the exact hole coordinates. Both paths intentionally use the same scan so a benchmark cannot
 * silently redefine the meaning of a hole.</p>
 */
final class BoardOccupancyMetrics {

    private BoardOccupancyMetrics() {
    }

    static Analysis analyze(boolean[][] board) {
        return analyze(board, false);
    }

    static Analysis analyzeWithHoleCells(boolean[][] board) {
        return analyze(board, true);
    }

    private static Analysis analyze(boolean[][] board, boolean includeHoleCells) {
        Objects.requireNonNull(board, "board");
        if (board.length == 0 || board[0].length == 0) {
            throw new IllegalArgumentException("board dimensions must be positive");
        }

        int rows = board.length;
        int cols = board[0].length;
        for (boolean[] row : board) {
            if (row.length != cols) {
                throw new IllegalArgumentException(
                        "board rows must have a consistent column count");
            }
        }

        int[] heights = new int[cols];
        int aggregateHeight = 0;
        int holes = 0;
        List<BoardPosition> holeCells =
                includeHoleCells ? new ArrayList<>() : List.of();

        for (int col = 0; col < cols; col++) {
            boolean blockSeen = false;
            for (int row = 0; row < rows; row++) {
                if (board[row][col]) {
                    if (!blockSeen) {
                        heights[col] = rows - row;
                        aggregateHeight += heights[col];
                        blockSeen = true;
                    }
                }
                else if (blockSeen) {
                    holes++;
                    if (includeHoleCells) {
                        holeCells.add(new BoardPosition(row, col));
                    }
                }
            }
        }

        int bumpiness = 0;
        for (int col = 0; col < cols - 1; col++) {
            bumpiness += Math.abs(heights[col] - heights[col + 1]);
        }

        return new Analysis(
                aggregateHeight,
                holes,
                bumpiness,
                includeHoleCells ? List.copyOf(holeCells) : List.of());
    }

    record Analysis(
            int aggregateHeight,
            int holes,
            int bumpiness,
            List<BoardPosition> holeCells) {

        Analysis {
            if (aggregateHeight < 0 || holes < 0 || bumpiness < 0) {
                throw new IllegalArgumentException("board metrics must not be negative");
            }
            holeCells = List.copyOf(Objects.requireNonNull(holeCells, "holeCells"));
            if (!holeCells.isEmpty() && holeCells.size() != holes) {
                throw new IllegalArgumentException(
                        "holeCells must either be empty or contain every hole");
            }
        }
    }
}
