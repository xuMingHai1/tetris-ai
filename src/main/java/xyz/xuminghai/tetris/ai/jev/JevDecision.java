/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai.jev;

import xyz.xuminghai.tetris.ai.AiMove;

import java.util.Map;

/**
 * Jev decision together with model confidence and token usage.
 */
public record JevDecision(
        AiMove move,
        String model,
        double confidence,
        Map<AiMove, Double> probabilities,
        int inputTokens,
        int outputTokens) {

    public JevDecision {
        probabilities = Map.copyOf(probabilities);
    }
}
