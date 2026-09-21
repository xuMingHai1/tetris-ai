/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

/**
 * Provider telemetry emitted after one successful Jev Choice evaluation.
 *
 * <p>This record is observational only; gameplay decisions remain represented by {@link AiMove}.</p>
 */
public record JevDecisionObservation(
        double confidence,
        long inputTokens,
        long outputTokens,
        int candidateCount) {

    public JevDecisionObservation {
        if (confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be within [0, 1]");
        }
        if (inputTokens < 0 || outputTokens < 0) {
            throw new IllegalArgumentException("token usage must not be negative");
        }
        if (candidateCount <= 0) {
            throw new IllegalArgumentException("candidateCount must be greater than 0");
        }
    }
}
