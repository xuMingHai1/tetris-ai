/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

/**
 * A legal candidate placement and its deterministic board features.
 */
public record MoveCandidate(
        AiMove move,
        int clearedLines,
        int aggregateHeight,
        int holes,
        int bumpiness,
        double heuristicScore) {
}
