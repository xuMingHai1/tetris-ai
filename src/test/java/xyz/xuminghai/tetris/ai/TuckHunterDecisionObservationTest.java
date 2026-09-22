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

class TuckHunterDecisionObservationTest {

    @Test
    void distinguishesExecutedTuckFromFutureSetup() {
        TuckHunterDecisionObservation executed = new TuckHunterDecisionObservation(
                5, 3, 2, true,
                0, 0, 0, 0,
                0, 2, 0, 1);
        TuckHunterDecisionObservation setup = new TuckHunterDecisionObservation(
                5, 2, 2, false,
                1, 4, 1, 1,
                -1, 3, 0, 2);

        assertTrue(executed.selectedCurrentActionOnly());
        assertTrue(executed.objectiveApplied());
        assertFalse(executed.createsTopFiveOpportunity());
        assertEquals(2, executed.safetyRejectedCandidates());

        assertFalse(setup.selectedCurrentActionOnly());
        assertTrue(setup.objectiveApplied());
        assertTrue(setup.createsTopFiveOpportunity());
        assertEquals(3, setup.safetyRejectedCandidates());
    }

    @Test
    void rejectsCurrentTuckCombinedWithFutureSetupFacts() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new TuckHunterDecisionObservation(
                        5, 3, 2, true,
                        1, 1, 1, 1,
                        0, 2, 0, 1));
    }

    @Test
    void rejectsFutureRankWithoutFutureActionOnlyCandidate() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new TuckHunterDecisionObservation(
                        5, 3, 2, false,
                        0, 0, 0, 1,
                        0, 2, 0, 1));
    }

    @Test
    void rejectsSafetyEligibilityOutsideCandidateCount() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new TuckHunterDecisionObservation(
                        5, 6, 1, false,
                        0, 0, 0, 0,
                        0, 0, 0, 0));
    }

    @Test
    void rejectsNonZeroSafetyDeltaForSurvivalTopChoice() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new TuckHunterDecisionObservation(
                        5, 3, 1, false,
                        0, 0, 0, 0,
                        0, 1, 0, 0));
    }
}
