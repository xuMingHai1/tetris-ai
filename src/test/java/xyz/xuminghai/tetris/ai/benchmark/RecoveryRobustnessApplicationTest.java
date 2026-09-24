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

class RecoveryRobustnessApplicationTest {

    @Test
    void formatsRecoveryRobustnessCsvWithoutColumnMismatch() {
        RecoveryRobustnessBenchmark.Probe probe =
                new RecoveryRobustnessBenchmark.Probe(
                        TetrominoType.L,
                        24,
                        true,
                        9,
                        42,
                        12,
                        8,
                        7,
                        1,
                        3,
                        12.5,
                        28,
                        7,
                        15,
                        76);
        RecoveryRobustnessApplication.Sample sample =
                new RecoveryRobustnessApplication.Sample(
                        RecoveryRobustnessApplication.Source.FAILED_GUARD_TAIL,
                        1008L,
                        29,
                        2,
                        probe);

        String[] columns =
                RecoveryRobustnessApplication.formatLine(sample).split(",", -1);

        assertEquals(21, columns.length);
        assertEquals("recovery_robustness", columns[0]);
        assertEquals("failed-guard-tail", columns[1]);
        assertEquals("1008", columns[2]);
        assertEquals("2", columns[4]);
        assertEquals("L", columns[5]);
        assertEquals("1", columns[13]);
        assertEquals("3", columns[14]);
        assertEquals("false", columns[20]);
    }
}
