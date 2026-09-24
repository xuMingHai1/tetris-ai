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

class RecoveryReserveValidationApplicationTest {

    @Test
    void healthyControlSamplingKeepsCalibrationSamplingSemantics() {
        assertFalse(RecoveryReserveValidationApplication.shouldSampleHealthyControl(0, 970));
        assertTrue(RecoveryReserveValidationApplication.shouldSampleHealthyControl(4, 970));
        assertTrue(RecoveryReserveValidationApplication.shouldSampleHealthyControl(9, 970));
        assertFalse(RecoveryReserveValidationApplication.shouldSampleHealthyControl(968, 970));
        assertTrue(RecoveryReserveValidationApplication.shouldSampleHealthyControl(970, 970));
        assertTrue(RecoveryReserveValidationApplication.shouldSampleHealthyControl(999, 970));
    }

    @Test
    void warningRequiresBothFrozenThresholdsWhenPreviewIsRecoverable() {
        assertTrue(RecoveryReserveValidationApplication.warning(probe(2, 15)));
        assertTrue(RecoveryReserveValidationApplication.warning(probe(1, 20)));

        assertFalse(RecoveryReserveValidationApplication.warning(probe(3, 15)));
        assertFalse(RecoveryReserveValidationApplication.warning(probe(2, 14)));
    }

    @Test
    void warningTreatsUnrecoverablePreviewAsWarningWithoutInventingHoleData() {
        RecoveryRobustnessBenchmark.Probe probe = unrecoverableProbe();

        assertTrue(RecoveryReserveValidationApplication.warning(probe));
        assertEquals(
                "preview-unrecoverable",
                RecoveryReserveValidationApplication.warningReason(probe));
    }

    @Test
    void formatsValidationCsvWithFrozenWarningResult() {
        RecoveryReserveValidationApplication.Sample sample =
                new RecoveryReserveValidationApplication.Sample(
                        RecoveryReserveValidationApplication.Source.FAILED_GUARD_VALIDATION,
                        2004L,
                        72,
                        6,
                        probe(2, 15));

        String[] columns =
                RecoveryReserveValidationApplication.formatLine(sample).split(",", -1);

        assertEquals(23, columns.length);
        assertEquals("recovery_reserve_validation", columns[0]);
        assertEquals("failed-guard-validation", columns[1]);
        assertEquals("2004", columns[2]);
        assertEquals("6", columns[4]);
        assertEquals("true", columns[5]);
        assertEquals("low-reserve-plus-structural-debt", columns[6]);
        assertEquals("2", columns[10]);
        assertEquals("15", columns[12]);
    }

    private static RecoveryRobustnessBenchmark.Probe probe(
            int recoveryHeadroom,
            int recoveryHoles) {
        return new RecoveryRobustnessBenchmark.Probe(
                TetrominoType.T,
                20,
                true,
                recoveryHeadroom,
                40,
                recoveryHoles,
                8,
                7,
                0,
                9,
                12.0,
                24,
                4,
                18,
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
