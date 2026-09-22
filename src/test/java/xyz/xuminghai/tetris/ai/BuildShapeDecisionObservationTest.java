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

class BuildShapeDecisionObservationTest {

    @Test
    void exposesObjectiveProgressDeltas() {
        BuildShapeDecisionObservation observation = new BuildShapeDecisionObservation(
                ShapeTarget.HEART,
                5,
                3,
                ObjectiveRiskController.RiskLevel.LOW,
                ObjectiveRiskProfile.BALANCED,
                12,
                0,
                2,
                new ShapeProgress(32, 10, 3),
                new ShapeProgress(32, 12, 2),
                0,
                2,
                0,
                1);

        assertTrue(observation.objectiveApplied());
        assertEquals(3, observation.netScoreDelta());
        assertEquals(2, observation.matchedCellsDelta());
        assertEquals(-1, observation.intrusionCellsDelta());
        assertEquals(2, observation.safetyRejectedCandidates());
    }

    @Test
    void rejectsDifferentTargetsBetweenBaselineAndSelection() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new BuildShapeDecisionObservation(
                        ShapeTarget.HEART,
                        5,
                        3,
                        ObjectiveRiskController.RiskLevel.NORMAL,
                        ObjectiveRiskProfile.CONSERVATIVE,
                        8,
                        2,
                        2,
                        new ShapeProgress(32, 10, 3),
                        new ShapeProgress(31, 12, 2),
                        0,
                        2,
                        0,
                        1));
    }
}
