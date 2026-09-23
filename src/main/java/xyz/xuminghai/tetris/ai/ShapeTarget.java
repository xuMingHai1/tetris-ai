/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import java.util.Locale;
import java.util.Objects;

/**
 * Built-in occupancy-only creative targets with explicit visual and support semantics.
 *
 * <p>Targets are anchored to the bottom center of the board. {@code #} marks required visual
 * cells, {@code .} marks forbidden visual background, and {@code +} marks support cells that may be
 * occupied without changing visual correctness. Board cells outside the target canvas are ignored
 * by shape scoring and remain governed by the survival heuristic.</p>
 */
public enum ShapeTarget {

    /**
     * Filled heart silhouette above a two-row support zone.
     */
    HEART(
            "heart",
            ".##..##.",
            "########",
            "########",
            ".######.",
            "..####..",
            "...##...",
            "++++++++",
            "++++++++");

    private final String configValue;
    private final CellRole[][] mask;
    private final int requiredCells;
    private final int forbiddenCells;

    ShapeTarget(String configValue, String... rows) {
        this.configValue = configValue;
        this.mask = parseMask(rows);
        this.requiredCells = count(mask, CellRole.REQUIRED);
        this.forbiddenCells = count(mask, CellRole.FORBIDDEN);
        if (requiredCells == 0) {
            throw new IllegalArgumentException("shape target must contain required cells");
        }
    }

    public String configValue() {
        return configValue;
    }

    public int height() {
        return mask.length;
    }

    public int width() {
        return mask[0].length;
    }

    public int requiredCells() {
        return requiredCells;
    }

    public int forbiddenCells() {
        return forbiddenCells;
    }

    /**
     * Maps one board coordinate back to the target role using the same bottom-centered anchor as
     * {@link #progress(boolean[][])}.
     *
     * <p>This package-local view exists for diagnostics that need to explain occupancy facts by
     * target semantics without duplicating the target mask or its anchoring rules.</p>
     */
    CellRole roleAtBoardCell(
            int boardRows,
            int boardCols,
            int boardRow,
            int boardCol) {
        if (boardRows < height() || boardCols < width()) {
            throw new IllegalArgumentException("board is smaller than shape target");
        }
        if (boardRow < 0 || boardRow >= boardRows || boardCol < 0 || boardCol >= boardCols) {
            throw new IllegalArgumentException("board coordinate is outside board dimensions");
        }

        int rowOffset = boardRows - height();
        int colOffset = (boardCols - width()) / 2;
        int targetRow = boardRow - rowOffset;
        int targetCol = boardCol - colOffset;
        if (targetRow < 0
                || targetRow >= height()
                || targetCol < 0
                || targetCol >= width()) {
            return CellRole.OUTSIDE_TARGET;
        }
        return mask[targetRow][targetCol];
    }

    /**
     * Evaluates the target against one post-lock/post-row-clear board.
     */
    public ShapeProgress progress(boolean[][] board) {
        Objects.requireNonNull(board, "board");
        if (board.length == 0 || board.length < height() || board[0].length < width()) {
            throw new IllegalArgumentException("board is smaller than shape target");
        }

        int cols = board[0].length;
        for (boolean[] row : board) {
            if (row.length != cols) {
                throw new IllegalArgumentException(
                        "board rows must have a consistent column count");
            }
        }

        int rowOffset = board.length - height();
        int colOffset = (cols - width()) / 2;
        int matchedRequired = 0;
        int forbiddenOccupied = 0;
        int supportOccupied = 0;

        for (int row = 0; row < height(); row++) {
            for (int col = 0; col < width(); col++) {
                if (!board[rowOffset + row][colOffset + col]) {
                    continue;
                }

                switch (mask[row][col]) {
                    case REQUIRED -> matchedRequired++;
                    case FORBIDDEN -> forbiddenOccupied++;
                    case SUPPORT_ALLOWED -> supportOccupied++;
                }
            }
        }

        return new ShapeProgress(
                requiredCells,
                matchedRequired,
                forbiddenCells,
                forbiddenOccupied,
                supportOccupied);
    }

    public static ShapeTarget parse(String configured) {
        String normalized = Objects.requireNonNull(configured, "configured")
                .trim()
                .toLowerCase(Locale.ROOT);
        for (ShapeTarget target : values()) {
            if (target.configValue.equals(normalized)) {
                return target;
            }
        }
        throw new IllegalArgumentException(
                "Unsupported shape target: " + configured + ". Expected heart.");
    }

    private static CellRole[][] parseMask(String[] rows) {
        Objects.requireNonNull(rows, "rows");
        if (rows.length == 0 || rows[0].isEmpty()) {
            throw new IllegalArgumentException("shape target must not be empty");
        }

        int width = rows[0].length();
        CellRole[][] parsed = new CellRole[rows.length][width];
        for (int row = 0; row < rows.length; row++) {
            if (rows[row].length() != width) {
                throw new IllegalArgumentException("shape target rows must have equal width");
            }
            for (int col = 0; col < width; col++) {
                parsed[row][col] = switch (rows[row].charAt(col)) {
                    case '#' -> CellRole.REQUIRED;
                    case '.' -> CellRole.FORBIDDEN;
                    case '+' -> CellRole.SUPPORT_ALLOWED;
                    default -> throw new IllegalArgumentException(
                            "shape target supports only '#', '.' and '+' cells");
                };
            }
        }
        return parsed;
    }

    private static int count(CellRole[][] mask, CellRole role) {
        int count = 0;
        for (CellRole[] row : mask) {
            for (CellRole cell : row) {
                if (cell == role) {
                    count++;
                }
            }
        }
        return count;
    }

    enum CellRole {
        REQUIRED,
        FORBIDDEN,
        SUPPORT_ALLOWED,
        OUTSIDE_TARGET
    }
}
