/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JevDecisionObservationTest {

    @Test
    void acceptsActionOnlySelectionWithinShortlist() {
        JevDecisionObservation observation = new JevDecisionObservation(
                0.7,
                100,
                10,
                5,
                2,
                0,
                1,
                -1,
                2,
                2,
                true);

        assertEquals(2, observation.actionOnlyCandidateCount());
        assertTrue(observation.selectedActionOnly());
    }

    @Test
    void rejectsActionOnlyCountOutsideShortlist() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new JevDecisionObservation(
                        0.7,
                        100,
                        10,
                        5,
                        1,
                        0,
                        0,
                        0,
                        0,
                        6,
                        false));
    }

    @Test
    void rejectsSelectedActionOnlyWhenShortlistContainsNone() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new JevDecisionObservation(
                        0.7,
                        100,
                        10,
                        5,
                        1,
                        0,
                        0,
                        0,
                        0,
                        0,
                        true));
    }
}
