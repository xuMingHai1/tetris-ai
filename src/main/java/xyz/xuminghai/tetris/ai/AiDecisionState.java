/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

/**
 * Observable runtime state of the current AI decision path.
 */
public enum AiDecisionState {
    IDLE,
    PENDING,
    REMOTE,
    FALLBACK,
    UNAVAILABLE
}
