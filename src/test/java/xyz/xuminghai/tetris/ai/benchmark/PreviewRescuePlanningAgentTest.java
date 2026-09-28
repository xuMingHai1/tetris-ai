/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai.benchmark;

import org.junit.jupiter.api.Test;
import xyz.xuminghai.tetris.ai.RecoveryRobustnessBenchmark;
import xyz.xuminghai.tetris.core.TetrominoType;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreviewRescuePlanningAgentTest {

    @Test
    void candidateMustRestorePreviewAndClearFrozenWarning() {
        assertFalse(PreviewRescuePlanningAgent.clearsWarning(probe(false, -1, -1)));
        assertFalse(PreviewRescuePlanningAgent.clearsWarning(probe(true, 2, 15)));
        assertTrue(PreviewRescuePlanningAgent.clearsWarning(probe(true, 3, 15)));
    }

    @Test
    void replacementRequiresFirstMatchingRank() {
        assertThrows(IllegalArgumentException.class, () ->
                new PreviewRescuePlanningAgent.Observation(
                        true, true, true, 3, 1, 10L, 20L));
        new PreviewRescuePlanningAgent.Observation(
                true, true, true, 3, 2, 10L, 20L);
    }

    private static RecoveryRobustnessBenchmark.Probe probe(
            boolean previewRecoverable, int headroom, int holes) {
        if (!previewRecoverable) {
            return new RecoveryRobustnessBenchmark.Probe(TetrominoType.I,
                    0, false, -1, -1, -1, -1,
                    7, 7, 0, 0.0, 0, -1, -1, -1);
        }
        return new RecoveryRobustnessBenchmark.Probe(TetrominoType.I,
                4, true, headroom, 20, holes, 10,
                7, 0, 1, 5.0, 8, 2, holes + 1, 21);
    }
}
