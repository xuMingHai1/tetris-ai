/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Shared headless board rules used by the live game and AI simulation.
 *
 * <p>Rows above the visible board are valid while a piece is entering. Columns outside the board and rows below the
 * board are invalid. The methods operate only on occupancy and coordinates, so they do not depend on JavaFX runtime
 * state.</p>
 */
public final class BoardRules {

    private BoardRules() {
    }

    public static boolean canPlace(
            boolean[][] occupied, int rows, int cols, Collection<BoardPosition> cells) {

        validateBoard(occupied, rows, cols);
        for (BoardPosition cell : cells) {
            if (cell.col() < 0 || cell.col() >= cols || cell.row() >= rows) {
                return false;
            }
            if (cell.row() >= 0 && occupied[cell.row()][cell.col()]) {
                return false;
            }
        }
        return true;
    }

    public static List<Integer> fullRows(boolean[][] occupied, Collection<Integer> candidates) {
        int rows = occupied.length;
        int cols = rows == 0 ? 0 : occupied[0].length;
        validateBoard(occupied, rows, cols);

        List<Integer> fullRows = new ArrayList<>();
        for (Integer row : candidates) {
            if (row == null || row < 0 || row >= rows) {
                continue;
            }
            if (isFull(occupied[row])) {
                fullRows.add(row);
            }
        }
        return fullRows;
    }

    /**
     * Removes every full row and compacts the remaining rows downward.
     *
     * @param occupied board mutated in place
     * @return number of removed rows
     */
    public static int clearFullRows(boolean[][] occupied) {
        int rows = occupied.length;
        int cols = rows == 0 ? 0 : occupied[0].length;
        validateBoard(occupied, rows, cols);

        int writeRow = rows - 1;
        int cleared = 0;
        for (int readRow = rows - 1; readRow >= 0; readRow--) {
            if (isFull(occupied[readRow])) {
                cleared++;
                continue;
            }
            if (writeRow != readRow) {
                System.arraycopy(occupied[readRow], 0, occupied[writeRow], 0, cols);
            }
            writeRow--;
        }

        while (writeRow >= 0) {
            for (int col = 0; col < cols; col++) {
                occupied[writeRow][col] = false;
            }
            writeRow--;
        }
        return cleared;
    }

    private static boolean isFull(boolean[] row) {
        for (boolean value : row) {
            if (!value) {
                return false;
            }
        }
        return true;
    }

    private static void validateBoard(boolean[][] occupied, int rows, int cols) {
        if (rows <= 0 || cols <= 0 || occupied.length != rows) {
            throw new IllegalArgumentException("board dimensions must be positive and consistent");
        }
        for (boolean[] row : occupied) {
            if (row.length != cols) {
                throw new IllegalArgumentException("board rows must have a consistent column count");
            }
        }
    }
}
