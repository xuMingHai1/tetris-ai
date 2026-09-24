/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai.benchmark;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RecoveryClearingPlannerComparisonApplicationTest {

    @Test
    void classifiesPairedPieceDelta() {
        assertEquals(
                "improved",
                RecoveryClearingPlannerComparisonApplication.outcome(1));
        assertEquals(
                "regressed",
                RecoveryClearingPlannerComparisonApplication.outcome(-1));
        assertEquals(
                "tied",
                RecoveryClearingPlannerComparisonApplication.outcome(0));
    }

    @Test
    void formatsPairedRecoveryGame() {
        GameBenchmarkResult baseline = result(7001L, 620, false, 2_000_000L);
        GameBenchmarkResult recovery = result(7001L, 1000, true, 4_000_000L);

        RecoveryClearingPlannerComparisonApplication.GameComparison comparison =
                new RecoveryClearingPlannerComparisonApplication.GameComparison(
                        7001L,
                        baseline,
                        recovery,
                        new RecoveryClearingPlannerComparisonApplication.ShapeSummarySnapshot(
                                4,
                                false),
                        new RecoveryClearingPlannerComparisonApplication.ShapeSummarySnapshot(
                                4,
                                false),
                        new RecoveryClearingPlannerComparisonApplication.RecoverySummary(
                                100,
                                8,
                                5,
                                3,
                                12,
                                14,
                                100,
                                500_000_000L,
                                20_000_000L));

        String[] columns =
                RecoveryClearingPlannerComparisonApplication.formatGame(comparison)
                        .split(",", -1);

        assertEquals(24, columns.length);
        assertEquals("recovery_clearing_game", columns[0]);
        assertEquals("7001", columns[1]);
        assertEquals("620", columns[2]);
        assertEquals("1000", columns[3]);
        assertEquals("380", columns[4]);
        assertEquals("improved", columns[5]);
        assertEquals("5", columns[10]);
        assertEquals("3", columns[11]);
        assertEquals("0", columns[16]);
    }

    private static GameBenchmarkResult result(
            long seed,
            int piecesPlaced,
            boolean reachedLimit,
            long totalDecisionNanos) {
        return new GameBenchmarkResult(
                seed,
                1000,
                piecesPlaced,
                0,
                piecesPlaced,
                0,
                0,
                totalDecisionNanos,
                totalDecisionNanos,
                BoardHealthSummary.empty(),
                reachedLimit);
    }
}
