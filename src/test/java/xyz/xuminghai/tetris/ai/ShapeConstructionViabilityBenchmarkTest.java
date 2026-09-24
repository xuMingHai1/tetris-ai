/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import org.junit.jupiter.api.Test;
import xyz.xuminghai.tetris.core.BagPieceGenerator;
import xyz.xuminghai.tetris.core.TetrominoType;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShapeConstructionViabilityBenchmarkTest {

    @Test
    void comparesKnownCleanWitnessWithSurvivalBaselineOnSameFuturePieces() {
        List<TetrominoType> fullSequence = pieceSequence(1008L, 18);
        List<TetrominoType> constructionPieces = fullSequence.subList(0, 10);
        ShapeConstructionFeasibilityBenchmark.Result feasibility =
                ShapeConstructionFeasibilityBenchmark.search(
                        ShapeTarget.HEART,
                        constructionPieces,
                        20,
                        10,
                        128);

        assertTrue(feasibility.cleanCompletionFound());

        ShapeConstructionViabilityBenchmark.Result result =
                ShapeConstructionViabilityBenchmark.calibrate(
                        ShapeTarget.HEART,
                        feasibility.witness(),
                        fullSequence,
                        20,
                        10,
                        2,
                        16,
                        8);

        assertEquals(feasibility.piecesToCompletion(), result.comparisons().size());
        assertTrue(result.minWitnessInitialReachableOutcomes() > 0);

        for (ShapeConstructionViabilityBenchmark.Comparison comparison :
                result.comparisons()) {
            assertEquals(
                    ShapeConstructionViabilityBenchmark.Source.WITNESS,
                    comparison.witness().source());
            assertEquals(
                    ShapeConstructionViabilityBenchmark.Source.SURVIVAL_BASELINE,
                    comparison.survivalBaseline().source());
            assertEquals(
                    comparison.witness().probe().availableFuturePieces(),
                    comparison.survivalBaseline().probe().availableFuturePieces());
            assertEquals(8, comparison.witness().probe().requestedGreedyDepth());
            assertEquals(2, comparison.witness().probe().requestedSearchDepth());
        }

        ShapeConstructionViabilityBenchmark.Sample finalWitness =
                result.comparisons().getLast().witness();
        assertTrue(finalWitness.progress().cleanCompletion());
        assertTrue(finalWitness.probe().firstReachableOutcomes() > 0);
    }

    @Test
    void reportsExactExhaustionOnlyWhenNoSearchPruningOccurred() {
        boolean[][] fullBoard = new boolean[20][10];
        for (boolean[] row : fullBoard) {
            java.util.Arrays.fill(row, true);
        }

        ShapeConstructionViabilityBenchmark.Probe probe =
                ShapeConstructionViabilityBenchmark.probe(
                        fullBoard,
                        List.of(TetrominoType.T),
                        1,
                        8,
                        1);

        assertEquals(0, probe.searchSurvivedDepth());
        assertEquals(
                ShapeConstructionViabilityBenchmark.SearchStatus.EXHAUSTED_EXACT,
                probe.searchStatus());
        assertFalse(probe.searchPruned());
        assertEquals(0, probe.firstReachableOutcomes());
        assertEquals(0, probe.greedySurvivedDepth());
        assertTrue(probe.greedyExhausted());
    }

    private static List<TetrominoType> pieceSequence(long seed, int count) {
        BagPieceGenerator generator = new BagPieceGenerator(seed);
        List<TetrominoType> pieces = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            pieces.add(TetrominoType.from(generator.next()));
        }
        return List.copyOf(pieces);
    }
}
