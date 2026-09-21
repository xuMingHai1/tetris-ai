/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

/**
 * Runtime control mode for the interactive game.
 */
public enum AiMode {
    MANUAL,
    HEURISTIC,
    JEV;

    public AiMode next() {
        return switch (this) {
            case MANUAL -> HEURISTIC;
            case HEURISTIC -> JEV;
            case JEV -> MANUAL;
        };
    }
}
