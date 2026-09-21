/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.core;

/**
 * Stable tetromino identity used by deterministic generation and AI simulation.
 */
public enum TetrominoType {
    I(2),
    J(4),
    L(4),
    O(1),
    S(2),
    T(4),
    Z(2);

    private final int rotationStates;

    TetrominoType(int rotationStates) {
        this.rotationStates = rotationStates;
    }

    public int rotationStates() {
        return rotationStates;
    }

    public static TetrominoType from(Tetris tetris) {
        if (tetris instanceof IBlock) return I;
        if (tetris instanceof JBlock) return J;
        if (tetris instanceof LBlock) return L;
        if (tetris instanceof OBlock) return O;
        if (tetris instanceof SBlock) return S;
        if (tetris instanceof TBlock) return T;
        if (tetris instanceof ZBlock) return Z;
        throw new IllegalArgumentException("Unsupported tetromino type: " + tetris.getClass().getName());
    }
}
