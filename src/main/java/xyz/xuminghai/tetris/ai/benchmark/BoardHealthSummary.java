/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai.benchmark;

/**
 * Aggregate board-health facts observed after each placed piece in one benchmark game.
 *
 * <p>All values come from the selected production PlacementCandidate; the benchmark does not
 * recalculate board heuristics independently.</p>
 */
public record BoardHealthSummary(
        int finalAggregateHeight,
        double averageAggregateHeight,
        int maxAggregateHeight,
        int finalHoles,
        double averageHoles,
        int maxHoles,
        int finalBumpiness,
        double averageBumpiness,
        int maxBumpiness) {

    public BoardHealthSummary {
        if (finalAggregateHeight < 0
                || averageAggregateHeight < 0
                || maxAggregateHeight < 0
                || finalHoles < 0
                || averageHoles < 0
                || maxHoles < 0
                || finalBumpiness < 0
                || averageBumpiness < 0
                || maxBumpiness < 0) {
            throw new IllegalArgumentException("board health metrics must not be negative");
        }
    }

    static BoardHealthSummary empty() {
        return new BoardHealthSummary(0, 0.0, 0, 0, 0.0, 0, 0, 0.0, 0);
    }
}
