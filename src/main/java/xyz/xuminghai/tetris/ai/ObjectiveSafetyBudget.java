/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import java.util.Objects;

/**
 * Explicit risk budget for a non-survival objective relative to the current survival top choice.
 *
 * <p>The budget is intentionally expressed in the existing objective board facts rather than as a
 * second weighted heuristic. A candidate may pursue a style/objective preference only when its
 * post-lock board remains within every configured delta from the survival baseline.</p>
 */
public record ObjectiveSafetyBudget(
        int maxAdditionalHoles,
        int maxAggregateHeightDelta,
        int maxBumpinessDelta) {

    private static final ObjectiveSafetyBudget CONSERVATIVE =
            new ObjectiveSafetyBudget(0, 4, 4);

    public ObjectiveSafetyBudget {
        if (maxAdditionalHoles < 0
                || maxAggregateHeightDelta < 0
                || maxBumpinessDelta < 0) {
            throw new IllegalArgumentException("objective safety budget limits must not be negative");
        }
    }

    /**
     * Conservative default used by non-survival objectives.
     *
     * <p>No new holes are allowed. Aggregate height and bumpiness may each exceed the survival
     * top choice by at most four points. Cleared-line delta is observed but is not a hard guard in
     * this first budget revision.</p>
     */
    public static ObjectiveSafetyBudget conservative() {
        return CONSERVATIVE;
    }

    /**
     * Compares one objective candidate to the survival baseline.
     */
    public Assessment assess(
            PlacementCandidate survivalBaseline,
            PlacementCandidate candidate) {
        Objects.requireNonNull(survivalBaseline, "survivalBaseline");
        Objects.requireNonNull(candidate, "candidate");

        int clearedLinesDelta =
                candidate.clearedLines() - survivalBaseline.clearedLines();
        int aggregateHeightDelta =
                candidate.aggregateHeight() - survivalBaseline.aggregateHeight();
        int holesDelta =
                candidate.holes() - survivalBaseline.holes();
        int bumpinessDelta =
                candidate.bumpiness() - survivalBaseline.bumpiness();

        boolean allowed = holesDelta <= maxAdditionalHoles
                && aggregateHeightDelta <= maxAggregateHeightDelta
                && bumpinessDelta <= maxBumpinessDelta;

        return new Assessment(
                allowed,
                clearedLinesDelta,
                aggregateHeightDelta,
                holesDelta,
                bumpinessDelta);
    }

    /**
     * Relative safety facts for one candidate.
     */
    public record Assessment(
            boolean allowed,
            int clearedLinesDelta,
            int aggregateHeightDelta,
            int holesDelta,
            int bumpinessDelta) {
    }
}
