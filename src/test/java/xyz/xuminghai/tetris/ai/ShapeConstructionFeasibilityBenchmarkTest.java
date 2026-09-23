/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import org.junit.jupiter.api.Test;
import xyz.xuminghai.tetris.core.TetrominoType;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShapeConstructionFeasibilityBenchmarkTest {

    @Test
    void reportsEmptySearchWithoutClaimingInfeasibility() {
        ShapeConstructionFeasibilityBenchmark.Result result =
                ShapeConstructionFeasibilityBenchmark.search(
                        ShapeTarget.HEART,
                        List.of(),
                        20,
                        10,
                        32);

        assertFalse(result.cleanCompletionFound());
        assertEquals(0, result.searchedPieces());
        assertEquals(-1, result.piecesToCompletion());
        assertEquals(32, result.bestVisualErrorCells());
        assertEquals(0, result.maxRequiredWithZeroForbidden());
        assertEquals(-1, result.minForbiddenAtFullRequired());
        assertTrue(result.witness().isEmpty());
    }

    @Test
    void expandsRealActionNativeLandingsForFixedPieceSequence() {
        ShapeConstructionFeasibilityBenchmark.Result result =
                ShapeConstructionFeasibilityBenchmark.search(
                        ShapeTarget.HEART,
                        List.of(TetrominoType.I),
                        20,
                        10,
                        32);

        assertFalse(result.cleanCompletionFound());
        assertEquals(1, result.searchedPieces());
        assertTrue(result.expandedStates() > 0);
        assertTrue(result.generatedPlacements() > 0);
        assertTrue(result.uniqueStates() > 0);
        assertTrue(result.maxFrontierSize() <= 32);
        assertTrue(result.witness().isEmpty());
    }

    @Test
    void rejectsInvalidSearchBudget() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ShapeConstructionFeasibilityBenchmark.search(
                        ShapeTarget.HEART,
                        List.of(TetrominoType.T),
                        20,
                        10,
                        0));
    }
}
