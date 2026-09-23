/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai.benchmark;

import org.junit.jupiter.api.Test;
import xyz.xuminghai.tetris.ai.BuildShapeDecisionObservation;
import xyz.xuminghai.tetris.ai.ObjectiveRiskController;
import xyz.xuminghai.tetris.ai.ObjectiveRiskProfile;
import xyz.xuminghai.tetris.ai.ShapeProgress;
import xyz.xuminghai.tetris.ai.ShapeTarget;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BenchmarkApplicationTest {

    @Test
    void formatsBuildShapeDecisionCsvWithoutTypeMismatch() {
        ShapeProgress progress = new ShapeProgress(32, 16, 16, 4, 2);
        BuildShapeDecisionObservation observation = new BuildShapeDecisionObservation(
                ShapeTarget.HEART,
                20,
                5,
                2,
                ObjectiveRiskController.RiskLevel.LOW,
                ObjectiveRiskProfile.BALANCED,
                10,
                0,
                false,
                1,
                progress,
                progress,
                0,
                0,
                0,
                0);

        String line = BenchmarkApplication.formatBuildShapeDecisionLine(
                "build-shape-heart",
                1000L,
                1,
                observation);

        assertEquals(33, line.split(",", -1).length);
        assertTrue(line.startsWith(
                "build_shape_decision,build-shape-heart,heart,1000,1,low,balanced,"));
        assertTrue(line.contains(",0.5000,false,0,0,0,0"));
    }
}
