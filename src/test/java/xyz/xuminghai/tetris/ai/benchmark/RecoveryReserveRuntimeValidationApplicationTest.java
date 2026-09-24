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

class RecoveryReserveRuntimeValidationApplicationTest {

    @Test
    void delegatesToFrozenOutOfSampleWarningContract() {
        assertTrue(RecoveryReserveRuntimeValidationApplication.warning(probe(2, 15)));
        assertFalse(RecoveryReserveRuntimeValidationApplication.warning(probe(3, 15)));
        assertFalse(RecoveryReserveRuntimeValidationApplication.warning(probe(2, 14)));

        RecoveryRobustnessBenchmark.Probe unrecoverable = unrecoverableProbe();
        assertTrue(RecoveryReserveRuntimeValidationApplication.warning(unrecoverable));
        assertEquals(
                "preview-unrecoverable",
                RecoveryReserveRuntimeValidationApplication.warningReason(unrecoverable));
    }

    @Test
    void formatsRuntimeFailureValidationCsv() {
        RecoveryReserveRuntimeValidationApplication.Sample sample =
                new RecoveryReserveRuntimeValidationApplication.Sample(
                        RecoveryReserveRuntimeValidationApplication.Source.FAILED_RUNTIME,
                        3007L,
                        612,
                        9,
                        probe(2, 18));

        String[] columns =
                RecoveryReserveRuntimeValidationApplication.formatLine(sample).split(",", -1);

        assertEquals(23, columns.length);
        assertEquals("recovery_reserve_runtime_validation", columns[0]);
        assertEquals("failed-runtime", columns[1]);
        assertEquals("3007", columns[2]);
        assertEquals("9", columns[4]);
        assertEquals("true", columns[5]);
        assertEquals("low-reserve-plus-structural-debt", columns[6]);
        assertEquals("2", columns[10]);
        assertEquals("18", columns[12]);
    }

    @Test
    void sampleRequiresFailureDistanceOnlyForFailedRuntime() {
        new RecoveryReserveRuntimeValidationApplication.Sample(
                RecoveryReserveRuntimeValidationApplication.Source.HEALTHY_RUNTIME,
                3000L,
                1000,
                -1,
                probe(8, 4));

        new RecoveryReserveRuntimeValidationApplication.Sample(
                RecoveryReserveRuntimeValidationApplication.Source.FAILED_RUNTIME,
                3001L,
                400,
                0,
                probe(2, 20));
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
