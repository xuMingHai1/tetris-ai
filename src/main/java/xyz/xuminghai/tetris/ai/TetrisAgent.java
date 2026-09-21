/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

/**
 * Decides how to place the current piece from an immutable game snapshot.
 */
@FunctionalInterface
public interface TetrisAgent {

    AiMove decide(GameSnapshot snapshot);
}
