/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai.benchmark;

import org.junit.jupiter.api.Test;
import xyz.xuminghai.tetris.ai.AiMove;
import xyz.xuminghai.tetris.ai.AiPlan;
import xyz.xuminghai.tetris.ai.ObjectiveRiskController;
import xyz.xuminghai.tetris.ai.ObjectiveRiskProfile;
import xyz.xuminghai.tetris.ai.ObjectiveSafetyBudget;
import xyz.xuminghai.tetris.ai.ShapeProgress;
import xyz.xuminghai.tetris.ai.ShapeWitnessConstraintAudit;
import xyz.xuminghai.tetris.core.TetrominoType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShapeFeasibilityApplicationTest {

    @Test
    void formatsShapeWitnessAuditCsvWithoutPlaceholderMismatch() {
        ShapeProgress before = new ShapeProgress(32, 4, 16, 0, 0);
        ShapeProgress survival = new ShapeProgress(32, 4, 16, 1, 0);
        ShapeProgress witness = new ShapeProgress(32, 8, 16, 0, 1);
        ShapeWitnessConstraintAudit.Step step = new ShapeWitnessConstraintAudit.Step(
                1,
                TetrominoType.T,
                AiPlan.fromPlacement(AiMove.NONE),
                20,
                8,
                true,
                false,
                ObjectiveRiskController.RiskLevel.NORMAL,
                ObjectiveRiskProfile.CONSERVATIVE,
                12,
                3,
                new ObjectiveSafetyBudget.Assessment(false, 0, 3, 2, 1),
                new ShapeWitnessConstraintAudit.HoleBreakdown(3, 1, 1, 0, 1),
                new ShapeWitnessConstraintAudit.HoleBreakdown(5, 1, 3, 0, 1),
                0,
                true,
                ObjectiveRiskController.RiskLevel.LOW,
                ObjectiveRiskProfile.BALANCED,
                true,
                false,
                false,
                ShapeWitnessConstraintAudit.Blocker.TOP_FIVE,
                before,
                survival,
                witness);

        String line = ShapeFeasibilityApplication.formatWitnessAuditLine(1008L, step);
        String[] columns = line.split(",", -1);

        assertEquals(44, columns.length);
        assertEquals("shape_witness_audit", columns[0]);
        assertEquals("1008", columns[1]);
        assertEquals("8", columns[4]);
        assertEquals("3", columns[36]);
        assertEquals("low", columns[41]);
        assertEquals("balanced", columns[42]);
        assertTrue(Boolean.parseBoolean(columns[43]));
    }
}
