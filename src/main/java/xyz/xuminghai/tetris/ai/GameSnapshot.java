/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import xyz.xuminghai.tetris.core.BoardPosition;
import xyz.xuminghai.tetris.core.Cell;
import xyz.xuminghai.tetris.core.Tetris;
import xyz.xuminghai.tetris.core.TetrisFactory;
import xyz.xuminghai.tetris.core.TetrominoType;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable input to an AI decision.
 *
 * <p>The board excludes the falling piece and contains no JavaFX property, animation, audio or input
 * state. {@code nextType} is present when the runtime already knows the preview piece; simulations
 * that intentionally model only the current piece may leave it empty. {@code currentRotation}
 * preserves the core piece's zero-based internal orientation, including snapshots taken after
 * partial playback. Constructors without it describe factory-orientation pieces only.</p>
 */
public record GameSnapshot(
        int rows,
        int cols,
        boolean[][] occupied,
        TetrominoType currentType,
        List<BoardPosition> currentCells,
        int currentRotation,
        Optional<TetrominoType> nextType) {

    public GameSnapshot(
            int rows, int cols, boolean[][] occupied, TetrominoType currentType,
            List<BoardPosition> currentCells, Optional<TetrominoType> nextType) {
        this(rows, cols, occupied, currentType, currentCells, 0, nextType);
    }

    public GameSnapshot(
            int rows, int cols, boolean[][] occupied, TetrominoType currentType,
            List<BoardPosition> currentCells, int currentRotation, TetrominoType nextType) {
        this(rows, cols, occupied, currentType, currentCells, currentRotation,
                Optional.of(Objects.requireNonNull(nextType, "nextType")));
    }

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
        if (currentRotation < 0 || currentRotation >= currentType.rotationStates()) {
            throw new IllegalArgumentException("currentRotation is outside the piece's rotation states");
        }
        currentCells = List.copyOf(currentCells);
        nextType = Objects.requireNonNull(nextType, "nextType");
        occupied = copyBoard(occupied, rows, cols);
    }

    @Override
    public boolean[][] occupied() {
        return copyBoard(occupied, rows, cols);
    }

    /** Restores orientation through core transformations before installing the captured cells. */
    Tetris restoreCurrentPiece() {
        Tetris tetris = TetrisFactory.create(currentType);
        for (int rotation = 0; rotation < currentRotation; rotation++) {
            tetris.rotateClockwise();
        }
        Cell[] template = tetris.getCells();
        Cell[] cells = new Cell[template.length];
        for (int i = 0; i < cells.length; i++) {
            BoardPosition position = currentCells.get(i);
            cells[i] = new Cell(position.row(), position.col(), template[i].getColor());
        }
        tetris.setCells(cells);
        return tetris;
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
