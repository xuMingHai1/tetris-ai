/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import xyz.xuminghai.tetris.core.TetrominoType;

import java.util.List;
import java.util.Objects;

/**
 * Immutable input to an AI decision.
 *
 * <p>The board excludes the falling piece and contains no JavaFX property, animation, audio or input state.</p>
 */
public record GameSnapshot(
        int rows,
        int cols,
        boolean[][] occupied,
        TetrominoType currentType,
        List<BoardPosition> currentCells) {

    public GameSnapshot {
        if (rows <= 0 || cols <= 0) {
            throw new IllegalArgumentException("rows and cols must be greater than 0");
        }
        Objects.requireNonNull(occupied, "occupied");
        Objects.requireNonNull(currentType, "currentType");
        currentCells = List.copyOf(currentCells);
        occupied = copyBoard(occupied, rows, cols);
    }

    @Override
    public boolean[][] occupied() {
        return copyBoard(occupied, rows, cols);
    }

    private static boolean[][] copyBoard(boolean[][] source, int rows, int cols) {
        if (source.length != rows) {
            throw new IllegalArgumentException("occupied row count does not match rows");
        }
        boolean[][] copy = new boolean[rows][cols];
        for (int row = 0; row < rows; row++) {
            if (source[row].length != cols) {
                throw new IllegalArgumentException("occupied column count does not match cols");
            }
            System.arraycopy(source[row], 0, copy[row], 0, cols);
        }
        return copy;
    }
}
