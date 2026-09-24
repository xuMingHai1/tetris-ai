/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai.benchmark;

import org.junit.jupiter.api.Test;
import xyz.xuminghai.tetris.ai.BuildShapeContinuationBenchmark;
import xyz.xuminghai.tetris.ai.PlacementCandidate;
import xyz.xuminghai.tetris.ai.ShapeTarget;
import xyz.xuminghai.tetris.core.TetrominoType;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RecoveryOneShotCounterfactualApplicationTest {

    @Test
    void classifiesContinuationDepthDelta() {
        assertEquals(
                RecoveryOneShotCounterfactualApplication.Outcome.IMPROVED,
                RecoveryOneShotCounterfactualApplication.outcome(1));
        assertEquals(
                RecoveryOneShotCounterfactualApplication.Outcome.REGRESSED,
                RecoveryOneShotCounterfactualApplication.outcome(-1));
        assertEquals(
                RecoveryOneShotCounterfactualApplication.Outcome.TIED,
                RecoveryOneShotCounterfactualApplication.outcome(0));
    }

    @Test
    void computesMedianForOddAndEvenSamples() {
        assertEquals(
                2.0,
                RecoveryOneShotCounterfactualApplication.median(
                        List.of(1, 2, 9)));
        assertEquals(
                2.5,
                RecoveryOneShotCounterfactualApplication.median(
                        List.of(1, 2, 3, 9)));
    }

    @Test
    void branchMetricsTreatInitialPlacementAsFinalWhenContinuationCannotAdvance() {
        PlacementCandidate initial = new PlacementCandidate(
                new xyz.xuminghai.tetris.ai.AiMove(0, 0),
                new boolean[20][10],
                0,
                0,
                0,
                0);
        BuildShapeContinuationBenchmark.Result continuation =
                new BuildShapeContinuationBenchmark.Result(
                        RecoveryOneShotCounterfactualApplication.CONTINUATION_HORIZON,
                        0,
                        false,
                        List.of());

        RecoveryOneShotCounterfactualApplication.BranchMetrics metrics =
                RecoveryOneShotCounterfactualApplication.analyzeBranch(
                        initial,
                        continuation);

        assertEquals(0, metrics.survivedDepth());
        assertEquals(0, metrics.warningRecurrences());
        assertEquals(-1, metrics.firstWarningDepth());
        assertEquals(
                ShapeTarget.HEART.requiredCells(),
                metrics.bestVisualError());
    }
}
