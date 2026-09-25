/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai.benchmark;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecoveryEligibilityFeatureAuditApplicationTest {

    @Test
    void rocAucReportsPerfectHigherSeparation() {
        double auc = RecoveryEligibilityFeatureAuditApplication.rocAuc(List.of(
                new RecoveryEligibilityFeatureAuditApplication.LabeledValue(3.0, true),
                new RecoveryEligibilityFeatureAuditApplication.LabeledValue(4.0, true),
                new RecoveryEligibilityFeatureAuditApplication.LabeledValue(1.0, false),
                new RecoveryEligibilityFeatureAuditApplication.LabeledValue(2.0, false)));

        assertEquals(1.0, auc);
    }

    @Test
    void rocAucGivesHalfCreditToTies() {
        double auc = RecoveryEligibilityFeatureAuditApplication.rocAuc(List.of(
                new RecoveryEligibilityFeatureAuditApplication.LabeledValue(2.0, true),
                new RecoveryEligibilityFeatureAuditApplication.LabeledValue(2.0, false)));

        assertEquals(0.5, auc);
    }

    @Test
    void numericAuditCanIdentifyHelpfulLowerDirection() {
        var samples = List.of(
                sample(1L, 1, 10, 20),
                sample(2L, 1, 11, 20),
                sample(3L, -1, 20, 10),
                sample(4L, 0, 19, 10));

        var audit = RecoveryEligibilityFeatureAuditApplication.auditNumericFeature(
                "synthetic",
                samples,
                sample -> sample.features().rank1Placement().aggregateHeight());

        assertEquals(4, audit.usableSamples());
        assertEquals(2, audit.helpfulSamples());
        assertEquals(2, audit.nonHelpfulSamples());
        assertEquals(1.0, audit.bestAuc());
        assertEquals(
                RecoveryEligibilityFeatureAuditApplication.Direction.HELPFUL_LOWER,
                audit.direction());
    }

    @Test
    void sampleHeaderHasStableColumnCount() {
        var sample = sample(1L, 1, 10, 20);
        String row = RecoveryEligibilityFeatureAuditApplication.formatSample(sample);

        assertEquals(
                RecoveryEligibilityFeatureAuditApplication.SAMPLE_HEADER.split(",", -1).length,
                row.split(",", -1).length);
        assertTrue(row.startsWith("recovery_eligibility_sample,"));
    }

    private static RecoveryEligibilityFeatureAuditApplication.Sample sample(
            long seed,
            int depthDelta,
            int rank1AggregateHeight,
            int clearingAggregateHeight) {
        var baselineProbe = probe(2, 20, 18, 15, 5);
        var clearingProbe = probe(5, 15, 12, 10, 8);
        var baselinePlacement = new xyz.xuminghai.tetris.ai.PlacementCandidate(
                new xyz.xuminghai.tetris.ai.AiMove(0, 0),
                new boolean[20][10],
                0,
                rank1AggregateHeight,
                15,
                5);
        var clearingPlacement = new xyz.xuminghai.tetris.ai.PlacementCandidate(
                new xyz.xuminghai.tetris.ai.AiMove(0, 0),
                new boolean[20][10],
                0,
                clearingAggregateHeight,
                10,
                4);
        var progress = xyz.xuminghai.tetris.ai.ShapeTarget.HEART.progress(
                new boolean[20][10]);
        var features =
                new RecoveryEligibilityFeatureAuditApplication.FeatureSnapshot(
                        "low-reserve-plus-structural-debt",
                        xyz.xuminghai.tetris.core.TetrominoType.T,
                        xyz.xuminghai.tetris.core.TetrominoType.I,
                        xyz.xuminghai.tetris.ai.ObjectiveRiskController.RiskLevel.DANGER,
                        xyz.xuminghai.tetris.ai.ObjectiveRiskProfile.STRICT,
                        true,
                        20,
                        5,
                        1,
                        2,
                        15,
                        progress,
                        20,
                        1,
                        2,
                        baselinePlacement,
                        baselineProbe,
                        clearingPlacement,
                        clearingProbe,
                        progress);
        int baselineDepth = depthDelta > 0 ? 10 : 20;
        int recoveryDepth = baselineDepth + depthDelta;
        var baseline =
                new RecoveryOneShotCounterfactualApplication.BranchMetrics(
                        baselineDepth,
                        baselineDepth
                                == RecoveryOneShotCounterfactualApplication.CONTINUATION_HORIZON,
                        0,
                        -1,
                        rank1AggregateHeight,
                        15,
                        5,
                        progress.visualErrorCells(),
                        progress.visualErrorCells());
        var recovery =
                new RecoveryOneShotCounterfactualApplication.BranchMetrics(
                        recoveryDepth,
                        recoveryDepth
                                == RecoveryOneShotCounterfactualApplication.CONTINUATION_HORIZON,
                        0,
                        -1,
                        clearingAggregateHeight,
                        10,
                        4,
                        progress.visualErrorCells(),
                        progress.visualErrorCells());

        return new RecoveryEligibilityFeatureAuditApplication.Sample(
                seed,
                1,
                false,
                features,
                baseline,
                recovery,
                RecoveryOneShotCounterfactualApplication.outcome(depthDelta));
    }

    private static xyz.xuminghai.tetris.ai.RecoveryRobustnessBenchmark.Probe probe(
            int headroom,
            int aggregateHeight,
            int holes,
            int bumpiness,
            int minPostUnknownHeadroom) {
        return new xyz.xuminghai.tetris.ai.RecoveryRobustnessBenchmark.Probe(
                xyz.xuminghai.tetris.core.TetrominoType.I,
                10,
                true,
                headroom,
                aggregateHeight,
                holes,
                bumpiness,
                7,
                0,
                5,
                8.0,
                12,
                minPostUnknownHeadroom,
                holes + 2,
                aggregateHeight + 20);
    }
}
