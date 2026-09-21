/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import java.util.Objects;

/**
 * Immutable facts for one reachable landing position produced by deterministic board simulation.
 *
 * <p>The metrics describe the board after the piece is dropped and full rows are cleared. They are
 * deliberately free of heuristic weights so different decision strategies can evaluate the same
 * legal candidates without duplicating Tetris rules.</p>
 */
public record PlacementCandidate(
        AiMove move,
        boolean[][] resultingBoard,
        int clearedLines,
        int aggregateHeight,
        int holes,
        int bumpiness) {

    public PlacementCandidate {
        Objects.requireNonNull(move, "move");
        resultingBoard = copyBoard(resultingBoard);
        if (clearedLines < 0 || aggregateHeight < 0 || holes < 0 || bumpiness < 0) {
            throw new IllegalArgumentException("placement metrics must not be negative");
        }
    }

    @Override
    public boolean[][] resultingBoard() {
        return copyBoard(resultingBoard);
    }

    private static boolean[][] copyBoard(boolean[][] source) {
        Objects.requireNonNull(source, "resultingBoard");
        if (source.length == 0 || source[0].length == 0) {
            throw new IllegalArgumentException("resultingBoard dimensions must be positive");
        }

        int cols = source[0].length;
        boolean[][] copy = new boolean[source.length][cols];
        for (int row = 0; row < source.length; row++) {
            if (source[row].length != cols) {
                throw new IllegalArgumentException("resultingBoard rows must have a consistent column count");
            }
            System.arraycopy(source[row], 0, copy[row], 0, cols);
        }
        return copy;
    }
}
