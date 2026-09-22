/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TuckHunterDecisionObservationTest {

    @Test
    void distinguishesExecutedTuckFromFutureSetup() {
        TuckHunterDecisionObservation executed = new TuckHunterDecisionObservation(
                5, 2, true, 0, 0, 0, 0);
        TuckHunterDecisionObservation setup = new TuckHunterDecisionObservation(
                5, 3, false, 2, 4, 1, 1);

        assertTrue(executed.selectedCurrentActionOnly());
        assertTrue(executed.objectiveApplied());
        assertFalse(executed.createsTopFiveOpportunity());

        assertFalse(setup.selectedCurrentActionOnly());
        assertTrue(setup.objectiveApplied());
        assertTrue(setup.createsTopFiveOpportunity());
    }

    @Test
    void rejectsCurrentTuckCombinedWithFutureSetupFacts() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new TuckHunterDecisionObservation(
                        5, 1, true, 1, 1, 1, 1));
    }

    @Test
    void rejectsFutureRankWithoutFutureActionOnlyCandidate() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new TuckHunterDecisionObservation(
                        5, 1, false, 0, 0, 0, 1));
    }
}
