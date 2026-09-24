/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai.benchmark;

import org.junit.jupiter.api.Test;
import xyz.xuminghai.tetris.ai.AiMove;
import xyz.xuminghai.tetris.ai.AiPlan;
import xyz.xuminghai.tetris.ai.ConstructionSafetyEnvelopeBenchmark;
import xyz.xuminghai.tetris.ai.ObjectiveRiskController;
import xyz.xuminghai.tetris.ai.ObjectiveRiskProfile;
import xyz.xuminghai.tetris.ai.ObjectiveSafetyBudget;
import xyz.xuminghai.tetris.ai.ShapeConstructionViabilityBenchmark;
import xyz.xuminghai.tetris.ai.ShapeProgress;
import xyz.xuminghai.tetris.ai.ShapeWitnessConstraintAudit;
import xyz.xuminghai.tetris.core.TetrominoType;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShapeFeasibilityApplicationTest {

    @Test
    void formatsConstructionViabilityCsvWithoutPlaceholderMismatch() {
        ShapeConstructionViabilityBenchmark.Probe probe =
                new ShapeConstructionViabilityBenchmark.Probe(
                        24,
                        24,
                        18,
                        true,
                        4,
                        4,
                        ShapeConstructionViabilityBenchmark.SearchStatus.SURVIVED_HORIZON,
                        true,
                        17,
                        12,
                        32,
                        240,
                        96,
                        1800);
        ShapeConstructionViabilityBenchmark.Sample sample =
                new ShapeConstructionViabilityBenchmark.Sample(
                        ShapeConstructionViabilityBenchmark.Source.WITNESS,
                        5,
                        TetrominoType.L,
                        31,
                        13,
                        7,
                        42,
                        9,
                        new ShapeWitnessConstraintAudit.HoleBreakdown(15, 1, 6, 8, 0),
                        new ShapeProgress(32, 18, 16, 0, 2),
                        probe);

        String line = ShapeFeasibilityApplication.formatConstructionViabilityLine(1008L, sample);
        String[] columns = line.split(",", -1);

        assertEquals(33, columns.length);
        assertEquals("construction_viability", columns[0]);
        assertEquals("1008", columns[1]);
        assertEquals("witness", columns[3]);
        assertEquals("31", columns[5]);
        assertEquals("15", columns[9]);
        assertEquals("24", columns[20]);
        assertEquals("4", columns[24]);
        assertEquals("survived_horizon", columns[25]);
        assertEquals("1800", columns[32]);
    }

    @Test
    void formatsConstructionSafetyEnvelopeCsvWithoutPlaceholderMismatch() {
        ConstructionSafetyEnvelopeBenchmark.Step step =
                new ConstructionSafetyEnvelopeBenchmark.Step(
                        2,
                        TetrominoType.S,
                        17,
                        9,
                        24,
                        Optional.of(TetrominoType.I),
                        18,
                        0,
                        11,
                        9,
                        36,
                        8,
                        new ShapeWitnessConstraintAudit.HoleBreakdown(12, 1, 4, 6, 1),
                        new ShapeProgress(32, 14, 16, 0, 2));

        String line = ShapeFeasibilityApplication.formatConstructionSafetyEnvelopeLine(1008L, step);
        String[] columns = line.split(",", -1);

        assertEquals(24, columns.length);
        assertEquals("construction_safety_envelope", columns[0]);
        assertEquals("1008", columns[1]);
        assertEquals("17", columns[4]);
        assertEquals("I", columns[7]);
        assertEquals("11", columns[10]);
        assertEquals("12", columns[13]);
        assertEquals("8", columns[19]);
        assertEquals("18", columns[22]);
        assertEquals("false", columns[23]);
    }

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
