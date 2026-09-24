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

class RecoveryClearingPlanningAgentTest {

    @Test
    void warnedReplacementUsesFirstProbedClearingRank() {
        RecoveryClearingPlanningAgent.Observation observation =
                new RecoveryClearingPlanningAgent.Observation(
                        true,
                        true,
                        "low-reserve-plus-structural-debt",
                        1,
                        false,
                        true,
                        3,
                        2,
                        12,
                        progress(6),
                        progress(6),
                        1_000L,
                        2_000L);

        assertTrue(observation.planReplaced());
        assertEquals(0, observation.appliedVisualErrorDelta());
        assertEquals(3_000L, observation.recoveryOverheadNanos());
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
