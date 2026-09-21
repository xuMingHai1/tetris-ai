/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai.benchmark;

/**
 * Outcome and decision-cost metrics for one deterministic headless game.
 */
public record GameBenchmarkResult(
        long seed,
        int pieceLimit,
        int piecesPlaced,
        int linesCleared,
        int decisions,
        int primaryFailures,
        int fallbackDecisions,
        long totalDecisionNanos,
        long maxDecisionNanos,
        boolean reachedPieceLimit) {

    public GameBenchmarkResult {
        if (pieceLimit <= 0) {
            throw new IllegalArgumentException("pieceLimit must be greater than 0");
        }
        if (piecesPlaced < 0
                || linesCleared < 0
                || decisions < 0
                || primaryFailures < 0
                || fallbackDecisions < 0
                || totalDecisionNanos < 0
                || maxDecisionNanos < 0) {
            throw new IllegalArgumentException("benchmark metrics must not be negative");
        }
    }

    public double averageDecisionMillis() {
        return decisions == 0 ? 0.0 : totalDecisionNanos / 1_000_000.0 / decisions;
    }

    public double maxDecisionMillis() {
        return maxDecisionNanos / 1_000_000.0;
    }
}
