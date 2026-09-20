/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.core;

/**
 * Supplies the next tetromino independently from the JavaFX game runtime.
 */
@FunctionalInterface
public interface PieceGenerator {

    Tetris next();
}
