/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

/**
 * One live-game control that can be composed into an {@link AiPlan}.
 *
 * <p>The vocabulary is intentionally independent of JavaFX input so agents can describe gameplay
 * without depending on the interactive game package.</p>
 */
public enum AiAction {
    LEFT,
    RIGHT,
    ROTATE_CLOCKWISE,
    ROTATE_COUNTER_CLOCKWISE,
    SOFT_DROP,
    HARD_DROP
}
