/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai.benchmark;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RecoveryInterventionComparisonApplicationTest {

    @Test
    void classifiesPairedPieceDeltaWithoutIntroducingPolicyThresholds() {
        assertEquals(
                "improved",
                RecoveryInterventionComparisonApplication.outcome(1));
        assertEquals(
                "regressed",
                RecoveryInterventionComparisonApplication.outcome(-1));
        assertEquals(
                "tied",
                RecoveryInterventionComparisonApplication.outcome(0));
    }

    @Test
    void formatsPairedGameComparison() {
        GameBenchmarkResult baseline = result(5001L, 620, false);
        GameBenchmarkResult intervention = result(5001L, 1000, true);
        RecoveryInterventionComparisonApplication.GameComparison comparison =
                new RecoveryInterventionComparisonApplication.GameComparison(
                        5001L,
                        baseline,
                        intervention,
                        new RecoveryInterventionComparisonApplication.ShapeSummarySnapshot(
                                4,
                                false),
                        new RecoveryInterventionComparisonApplication.ShapeSummarySnapshot(
                                6,
                                false),
                        new RecoveryInterventionComparisonApplication.InterventionSummary(
                                8,
                                5,
                                6,
                                2,
                                3,
                                1000,
                                2_000_000_000L,
                                6_000_000L));

        String[] columns =
                RecoveryInterventionComparisonApplication.formatGame(comparison)
                        .split(",", -1);

        assertEquals(20, columns.length);
        assertEquals("recovery_intervention_game", columns[0]);
        assertEquals("5001", columns[1]);
        assertEquals("620", columns[2]);
        assertEquals("1000", columns[3]);
        assertEquals("380", columns[4]);
        assertEquals("improved", columns[5]);
        assertEquals("8", columns[8]);
        assertEquals("5", columns[9]);
        assertEquals("4", columns[13]);
        assertEquals("6", columns[14]);
        assertEquals("2", columns[15]);
    }

    private static GameBenchmarkResult result(
            long seed,
            int piecesPlaced,
            boolean reachedLimit) {
        return new GameBenchmarkResult(
                seed,
                1000,
                piecesPlaced,
                0,
                piecesPlaced,
                0,
                0,
                0L,
                0L,
                BoardHealthSummary.empty(),
                reachedLimit);
    }
}
