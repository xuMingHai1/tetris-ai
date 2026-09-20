/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.integration.typesafe;

import java.util.Map;

/**
 * Structured result returned by a single Choice question.
 */
public record TypeSafeChoiceResult(
        String model,
        String choice,
        double confidence,
        Map<String, Double> probabilities,
        int inputTokens,
        int outputTokens) {

    public TypeSafeChoiceResult {
        probabilities = Map.copyOf(probabilities);
    }
}
