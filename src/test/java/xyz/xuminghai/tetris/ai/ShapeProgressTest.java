/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShapeProgressTest {

    @Test
    void visualErrorCombinesMissingRequiredAndOccupiedForbiddenCells() {
        ShapeProgress progress = new ShapeProgress(32, 20, 16, 3, 7);

        assertEquals(15, progress.visualErrorCells());
        assertEquals(17, progress.netScore());
        assertEquals(0.625, progress.requiredCompletionRate());
        assertFalse(progress.cleanCompletion());
    }

    @Test
    void supportOccupancyDoesNotPreventCleanCompletion() {
        ShapeProgress progress = new ShapeProgress(32, 32, 16, 0, 16);

        assertEquals(0, progress.visualErrorCells());
        assertTrue(progress.cleanCompletion());
    }

    @Test
    void rejectsCountsOutsideTargetSemantics() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ShapeProgress(32, 33, 16, 0, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ShapeProgress(32, 20, 16, 17, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ShapeProgress(32, 20, 16, 3, -1));
    }
}
