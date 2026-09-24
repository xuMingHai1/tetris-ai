/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai.benchmark;

import org.junit.jupiter.api.Test;
import xyz.xuminghai.tetris.ai.ShapeProgress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecoveryInterventionPlanningAgentTest {

    @Test
    void reportsConstructionCostForWarningReplacement() {
        RecoveryInterventionPlanningAgent.Observation observation =
                new RecoveryInterventionPlanningAgent.Observation(
                        true,
                        "low-reserve-plus-structural-debt",
                        3,
                        true,
                        true,
                        false,
                        progress(6),
                        progress(8),
                        1_000L,
                        2_000L);

        assertTrue(observation.warningClearedBySurvival());
        assertEquals(2, observation.appliedVisualErrorDelta());
    }

    private static ShapeProgress progress(int visualError) {
        int required = 10;
        return new ShapeProgress(
                required,
                required - visualError,
                20,
                0,
                0);
    }
}
