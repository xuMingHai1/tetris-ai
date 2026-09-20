/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

/**
 * A reachable move expressed in live-game order: rotate first, then move horizontally.
 */
public record AiMove(int clockwiseRotations, int horizontalShift) {

    public static final AiMove NONE = new AiMove(0, 0);

    public AiMove {
        if (clockwiseRotations < 0) {
            throw new IllegalArgumentException("clockwiseRotations must not be negative");
        }
    }
}
