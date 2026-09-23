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
    void exposesTetrisAwareProgressDeltas() {
        BuildShapeDecisionObservation observation = new BuildShapeDecisionObservation(
                ShapeTarget.HEART,
                5,
                3,
                ObjectiveRiskController.RiskLevel.LOW,
                ObjectiveRiskProfile.BALANCED,
                12,
                0,
                false,
                2,
                new ShapeProgress(32, 10, 16, 3, 4),
                new ShapeProgress(32, 12, 16, 2, 5),
                0,
                2,
                0,
                1);

        assertTrue(observation.objectiveApplied());
        assertEquals(3, observation.netScoreDelta());
        assertEquals(-3, observation.visualErrorDelta());
        assertEquals(2, observation.matchedRequiredDelta());
        assertEquals(-1, observation.forbiddenOccupiedDelta());
        assertEquals(2, observation.safetyRejectedCandidates());
    }

    @Test
    void acceptsExactSurvivalFallbackWhenDangerSuppressesCreativity() {
        ShapeProgress progress = new ShapeProgress(32, 20, 16, 4, 6);

        BuildShapeDecisionObservation observation = new BuildShapeDecisionObservation(
                ShapeTarget.HEART,
                5,
                2,
                ObjectiveRiskController.RiskLevel.DANGER,
                ObjectiveRiskProfile.STRICT,
                5,
                6,
                true,
                1,
                progress,
                progress,
                0,
                0,
                0,
                0);

        assertTrue(observation.creativeSuppressedByDanger());
        assertEquals(0, observation.visualErrorDelta());
    }

    @Test
    void rejectsDangerSuppressionThatChangesShapeProgress() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new BuildShapeDecisionObservation(
                        ShapeTarget.HEART,
                        5,
                        2,
                        ObjectiveRiskController.RiskLevel.DANGER,
                        ObjectiveRiskProfile.STRICT,
                        5,
                        6,
                        true,
                        1,
                        new ShapeProgress(32, 20, 16, 4, 6),
                        new ShapeProgress(32, 21, 16, 4, 6),
                        0,
                        0,
                        0,
                        0));
    }

    @Test
    void rejectsDifferentTargetSemanticsBetweenBaselineAndSelection() {
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
                        false,
                        2,
                        new ShapeProgress(32, 10, 16, 3, 4),
                        new ShapeProgress(31, 12, 16, 2, 5),
                        0,
                        2,
                        0,
                        1));
    }
}
