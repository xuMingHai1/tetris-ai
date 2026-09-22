/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import java.util.Locale;
import java.util.Objects;

/**
 * Built-in occupancy-only creative targets.
 *
 * <p>Targets are anchored to the bottom center of the board. V1 intentionally models only occupied
 * versus empty cells; color is not part of {@link GameSnapshot} yet and belongs to a later creative
 * objective revision.</p>
 */
public enum ShapeTarget {

    HEART(
            "heart",
            ".##..##.",
            "########",
            "########",
            ".######.",
            "..####..",
            "...##...");

    private final String configValue;
    private final boolean[][] mask;
    private final int targetCells;

    ShapeTarget(String configValue, String... rows) {
        this.configValue = configValue;
        this.mask = parseMask(rows);
        this.targetCells = countTargetCells(mask);
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

    public int targetCells() {
        return targetCells;
    }

    /**
     * Evaluates the target against one post-lock/post-row-clear board.
     */
    public ShapeProgress progress(boolean[][] board) {
        Objects.requireNonNull(board, "board");
        if (board.length < height() || board.length == 0 || board[0].length < width()) {
            throw new IllegalArgumentException("board is smaller than shape target");
        }

        int cols = board[0].length;
        for (boolean[] row : board) {
            if (row.length != cols) {
                throw new IllegalArgumentException("board rows must have a consistent column count");
            }
        }

        int rowOffset = board.length - height();
        int colOffset = (cols - width()) / 2;
        int matched = 0;
        int intrusions = 0;

        for (int row = 0; row < height(); row++) {
            for (int col = 0; col < width(); col++) {
                boolean occupied = board[rowOffset + row][colOffset + col];
                if (mask[row][col]) {
                    if (occupied) {
                        matched++;
                    }
                } else if (occupied) {
                    intrusions++;
                }
            }
        }

        return new ShapeProgress(targetCells, matched, intrusions);
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

    private static boolean[][] parseMask(String[] rows) {
        Objects.requireNonNull(rows, "rows");
        if (rows.length == 0 || rows[0].isEmpty()) {
            throw new IllegalArgumentException("shape target must not be empty");
        }

        int width = rows[0].length();
        boolean[][] parsed = new boolean[rows.length][width];
        for (int row = 0; row < rows.length; row++) {
            if (rows[row].length() != width) {
                throw new IllegalArgumentException("shape target rows must have equal width");
            }
            for (int col = 0; col < width; col++) {
                char cell = rows[row].charAt(col);
                if (cell == '#') {
                    parsed[row][col] = true;
                } else if (cell != '.') {
                    throw new IllegalArgumentException(
                            "shape target supports only '#' and '.' cells");
                }
            }
        }
        return parsed;
    }

    private static int countTargetCells(boolean[][] mask) {
        int count = 0;
        for (boolean[] row : mask) {
            for (boolean cell : row) {
                if (cell) {
                    count++;
                }
            }
        }
        if (count == 0) {
            throw new IllegalArgumentException("shape target must contain occupied cells");
        }
        return count;
    }
}
