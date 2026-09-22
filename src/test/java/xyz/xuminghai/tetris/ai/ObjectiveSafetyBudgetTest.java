/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObjectiveSafetyBudgetTest {

    private static final boolean[][] BOARD = new boolean[20][10];

    private final ObjectiveSafetyBudget budget = ObjectiveSafetyBudget.conservative();

    @Test
    void rejectsNegativeBudgetLimit() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ObjectiveSafetyBudget(-1, 4, 4));
    }

    @Test
    void allowsCandidateAtConservativeBoundary() {
        PlacementCandidate baseline = candidate(1, 20, 2, 6);
        PlacementCandidate objective = candidate(0, 24, 2, 10);

        ObjectiveSafetyBudget.Assessment assessment =
                budget.assess(baseline, objective);

        assertTrue(assessment.allowed());
    }

    @Test
    void rejectsNewHole() {
        PlacementCandidate baseline = candidate(0, 20, 2, 6);
        PlacementCandidate objective = candidate(0, 20, 3, 6);

        assertFalse(budget.assess(baseline, objective).allowed());
    }

    @Test
    void rejectsAggregateHeightBeyondBudget() {
        PlacementCandidate baseline = candidate(0, 20, 2, 6);
        PlacementCandidate objective = candidate(0, 25, 2, 6);

        assertFalse(budget.assess(baseline, objective).allowed());
    }

    @Test
    void rejectsBumpinessBeyondBudget() {
        PlacementCandidate baseline = candidate(0, 20, 2, 6);
        PlacementCandidate objective = candidate(0, 20, 2, 11);

        assertFalse(budget.assess(baseline, objective).allowed());
    }

    @Test
    void allowsMetricImprovementRegardlessOfClearedLineDelta() {
        PlacementCandidate baseline = candidate(2, 20, 2, 6);
        PlacementCandidate objective = candidate(0, 18, 1, 5);

        ObjectiveSafetyBudget.Assessment assessment =
                budget.assess(baseline, objective);

        assertTrue(assessment.allowed());
        assertTrue(assessment.clearedLinesDelta() < 0);
    }

    private static PlacementCandidate candidate(
            int clearedLines,
            int aggregateHeight,
            int holes,
            int bumpiness) {
        return new PlacementCandidate(
                AiMove.NONE,
                BOARD,
                clearedLines,
                aggregateHeight,
                holes,
                bumpiness);
    }
}
