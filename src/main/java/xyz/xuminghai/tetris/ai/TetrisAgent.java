/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

/**
 * Decides how to place the current piece from an immutable game snapshot.
 *
 * <p>The contract is intentionally synchronous so implementations stay simple and composable.
 * Potentially blocking implementations, such as remote model adapters, must be invoked through
 * {@link AiDecisionExecutor} instead of directly from the JavaFX application thread.</p>
 */
@FunctionalInterface
public interface TetrisAgent {

    AiMove decide(GameSnapshot snapshot);
}
