/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

/**
 * Immutable board coordinate used by the headless AI boundary.
 */
public record BoardPosition(int row, int col) {

    BoardPosition down() {
        return new BoardPosition(row + 1, col);
    }

    BoardPosition horizontal(int delta) {
        return new BoardPosition(row, col + delta);
    }
}
