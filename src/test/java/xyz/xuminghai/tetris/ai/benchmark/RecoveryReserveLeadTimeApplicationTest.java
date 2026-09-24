/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai.benchmark;

import org.junit.jupiter.api.Test;
import xyz.xuminghai.tetris.ai.RecoveryRobustnessBenchmark;
import xyz.xuminghai.tetris.core.TetrominoType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecoveryReserveLeadTimeApplicationTest {

    @Test
    void healthyControlSamplingKeepsFixedIntervalAndFullTail() {
        assertFalse(RecoveryReserveLeadTimeApplication.shouldSampleHealthyControl(0, 220));
        assertTrue(RecoveryReserveLeadTimeApplication.shouldSampleHealthyControl(4, 220));
        assertTrue(RecoveryReserveLeadTimeApplication.shouldSampleHealthyControl(9, 220));
        assertFalse(RecoveryReserveLeadTimeApplication.shouldSampleHealthyControl(218, 220));
        assertTrue(RecoveryReserveLeadTimeApplication.shouldSampleHealthyControl(220, 220));
        assertTrue(RecoveryReserveLeadTimeApplication.shouldSampleHealthyControl(249, 220));
    }

    @Test
    void warningTreatsUnavailableRecoveryAsDangerous() {
        RecoveryReserveLeadTimeApplication.Sample unrecoverable =
                sample(-1, -1, false);

        assertTrue(RecoveryReserveLeadTimeApplication.warning(
                unrecoverable,
                RecoveryReserveLeadTimeApplication.Metric.RECOVERY_HEADROOM,
                2));
        assertTrue(RecoveryReserveLeadTimeApplication.warning(
                unrecoverable,
                RecoveryReserveLeadTimeApplication.Metric.POST_UNKNOWN_HEADROOM,
                8));
    }

    @Test
    void warningUsesObservedHeadroomWithoutInventingAnotherMetric() {
        RecoveryReserveLeadTimeApplication.Sample sample =
                sample(7, 5, true);

        assertFalse(RecoveryReserveLeadTimeApplication.warning(
                sample,
                RecoveryReserveLeadTimeApplication.Metric.RECOVERY_HEADROOM,
                6));
        assertTrue(RecoveryReserveLeadTimeApplication.warning(
                sample,
                RecoveryReserveLeadTimeApplication.Metric.RECOVERY_HEADROOM,
                8));
        assertTrue(RecoveryReserveLeadTimeApplication.warning(
                sample,
                RecoveryReserveLeadTimeApplication.Metric.POST_UNKNOWN_HEADROOM,
                6));
    }

    @Test
    void formatsRecoveryReserveCsvWithoutColumnMismatch() {
        RecoveryReserveLeadTimeApplication.Sample sample =
                new RecoveryReserveLeadTimeApplication.Sample(
                        RecoveryReserveLeadTimeApplication.Source.FAILED_GUARD_LEAD_TIME,
                        1004L,
                        32,
                        4,
                        probe(2, 1, true));

        String[] columns =
                RecoveryReserveLeadTimeApplication.formatLine(sample).split(",", -1);

        assertEquals(21, columns.length);
        assertEquals("recovery_reserve", columns[0]);
        assertEquals("failed-guard-lead-time", columns[1]);
        assertEquals("1004", columns[2]);
        assertEquals("4", columns[4]);
        assertEquals("2", columns[8]);
        assertEquals("1", columns[17]);
    }

    private static RecoveryReserveLeadTimeApplication.Sample sample(
            int recoveryHeadroom,
            int postUnknownHeadroom,
            boolean recoverable) {
        return new RecoveryReserveLeadTimeApplication.Sample(
                RecoveryReserveLeadTimeApplication.Source.HEALTHY_RUNTIME_CONTROL,
                42L,
                10,
                -1,
                recoverable
                        ? probe(recoveryHeadroom, postUnknownHeadroom, true)
                        : unrecoverableProbe());
    }

    private static RecoveryRobustnessBenchmark.Probe probe(
            int recoveryHeadroom,
            int postUnknownHeadroom,
            boolean fullyRecoverable) {
        return new RecoveryRobustnessBenchmark.Probe(
                TetrominoType.T,
                20,
                true,
                recoveryHeadroom,
                40,
                10,
                8,
                7,
                fullyRecoverable ? 0 : 2,
                fullyRecoverable ? 9 : 0,
                fullyRecoverable ? 12.0 : 4.0,
                24,
                postUnknownHeadroom,
                14,
                55);
    }

    private static RecoveryRobustnessBenchmark.Probe unrecoverableProbe() {
        return new RecoveryRobustnessBenchmark.Probe(
                TetrominoType.I,
                0,
                false,
                -1,
                -1,
                -1,
                -1,
                7,
                7,
                0,
                0.0,
                0,
                -1,
                -1,
                -1);
    }
}
