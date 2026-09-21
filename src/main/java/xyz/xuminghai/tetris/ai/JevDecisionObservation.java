/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

/**
 * Observational telemetry emitted after one valid Jev Choice decision.
 *
 * <p>The selected rank is one-based within the heuristic safety shortlist. Metric deltas are
 * {@code selected - heuristicTopCandidate}; positive aggregate-height, holes or bumpiness deltas
 * therefore mean the remote choice made the immediate board metric worse than the local heuristic
 * first choice, while a positive cleared-lines delta means it cleared more lines immediately.
 * These values never participate in gameplay decisions.</p>
 */
public record JevDecisionObservation(
        double confidence,
        long inputTokens,
        long outputTokens,
        int candidateCount,
        int selectedRank,
        int clearedLinesDelta,
        int aggregateHeightDelta,
        int holesDelta,
        int bumpinessDelta) {

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
        if (selectedRank <= 0 || selectedRank > candidateCount) {
            throw new IllegalArgumentException("selectedRank must be within the candidate shortlist");
        }
    }
}
