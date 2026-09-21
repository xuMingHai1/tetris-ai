/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.core;

/**
 * Immutable board coordinate shared by live collision checks and headless simulation.
 */
public record BoardPosition(int row, int col) {

    public BoardPosition down() {
        return new BoardPosition(row + 1, col);
    }

    public BoardPosition horizontal(int delta) {
        return new BoardPosition(row, col + delta);
    }
}
