/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai.benchmark;

import org.junit.jupiter.api.Test;
import xyz.xuminghai.tetris.ai.ShapeProgress;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ShapeCompletionTelemetryTest {
    private static final ShapeProgress CLEAN = new ShapeProgress(32, 32, 16, 0, 4);
    private static final ShapeProgress INTRUSION = new ShapeProgress(32, 32, 16, 1, 4);

    @Test
    void fullCoverageWithIntrusionIsNotCleanAndCompletionDoesNotStopContinuation() {
        ShapeCompletionTelemetry telemetry = new ShapeCompletionTelemetry();
        telemetry.record(1, INTRUSION, 100, 1_000_000);
        telemetry.record(2, CLEAN, 200, 2_000_000);
        telemetry.record(3, CLEAN, 300, 3_000_000);
        telemetry.record(4, INTRUSION, 400, 4_000_000);
        telemetry.record(5, CLEAN, 500, 5_000_000);

        assertEquals("build_shape_outcome,build-shape-heart,heart,42,5,2,2.000000,3,3,2,true,5.000000",
                telemetry.csv("build-shape-heart", "heart", 42));
        assertEquals(List.of(100L, 200L, 300L, 400L, 500L), telemetry.decisionNanos());
    }

    @Test
    void absentCompletionRemainsUnavailableInsteadOfZeroTime() {
        ShapeCompletionTelemetry telemetry = new ShapeCompletionTelemetry();
        telemetry.record(1, INTRUSION, 100, 1_000_000);
        assertEquals("build_shape_outcome,baseline,heart,7,1,-1,-1.000000,0,0,0,false,1.000000",
                telemetry.csv("baseline", "heart", 7));
    }

    @Test
    void rejectsMissingOrDuplicateTurnsAndBackwardsTiming() {
        ShapeCompletionTelemetry telemetry = new ShapeCompletionTelemetry();
        assertThrows(IllegalArgumentException.class, () -> telemetry.record(2, CLEAN, 1, 10));
        assertThrows(IllegalArgumentException.class, () -> telemetry.record(1, CLEAN, -1, 10));
        telemetry.record(1, CLEAN, 1, 10);
        assertThrows(IllegalArgumentException.class, () -> telemetry.record(1, CLEAN, 1, 20));
        assertThrows(IllegalArgumentException.class, () -> telemetry.record(2, CLEAN, 1, 9));
    }
}
