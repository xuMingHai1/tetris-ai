/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import xyz.xuminghai.tetris.core.BoardPosition;
import xyz.xuminghai.tetris.core.TetrominoType;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable input to an AI decision.
 *
 * <p>The board excludes the falling piece and contains no JavaFX property, animation, audio or input
 * state. {@code nextType} is present when the runtime already knows the preview piece; simulations
 * that intentionally model only the current piece may leave it empty.</p>
 */
public record GameSnapshot(
        int rows,
        int cols,
        boolean[][] occupied,
        TetrominoType currentType,
        List<BoardPosition> currentCells,
        Optional<TetrominoType> nextType) {

    public GameSnapshot(
            int rows,
            int cols,
            boolean[][] occupied,
            TetrominoType currentType,
            List<BoardPosition> currentCells) {
        this(rows, cols, occupied, currentType, currentCells, Optional.empty());
    }

    public GameSnapshot(
            int rows,
            int cols,
            boolean[][] occupied,
            TetrominoType currentType,
            List<BoardPosition> currentCells,
            TetrominoType nextType) {
        this(rows, cols, occupied, currentType, currentCells, Optional.of(Objects.requireNonNull(nextType, "nextType")));
    }

    public GameSnapshot {
        if (rows <= 0 || cols <= 0) {
            throw new IllegalArgumentException("rows and cols must be greater than 0");
        }
        Objects.requireNonNull(occupied, "occupied");
        Objects.requireNonNull(currentType, "currentType");
        currentCells = List.copyOf(currentCells);
        nextType = Objects.requireNonNull(nextType, "nextType");
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
