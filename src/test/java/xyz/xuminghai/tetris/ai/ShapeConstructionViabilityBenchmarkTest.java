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

        boolean[][] board = new boolean[20][10];
        ConstructionSafetyGuard halfGuard =
                new ConstructionSafetyGuard(
                        ConstructionSafetyGuard.Profile.RETAIN_HALF);
        ConstructionSafetyGuard preserveGuard =
                new ConstructionSafetyGuard(
                        ConstructionSafetyGuard.Profile.PRESERVE_BASELINE);
        int preserveRejected = 0;

        for (int index = 0; index < feasibility.witness().size(); index++) {
            ShapeConstructionFeasibilityBenchmark.WitnessStep witnessStep =
                    feasibility.witness().get(index);
            GameSnapshot snapshot =
                    BoardSimulator.snapshotForSpawnedPiece(
                            board,
                            witnessStep.pieceType());
            List<ActionPlanCandidates.PlannedCandidate> ranked =
                    ActionPlanCandidates.ranked(snapshot);
            int witnessIndex = -1;
            for (int candidateIndex = 0; candidateIndex < ranked.size(); candidateIndex++) {
                if (ranked.get(candidateIndex).plan().equals(witnessStep.plan())) {
                    witnessIndex = candidateIndex;
                    break;
                }
            }
            assertTrue(witnessIndex >= 0);

            PlacementCandidate baseline = ranked.getFirst().placement();
            PlacementCandidate witnessPlacement =
                    ranked.get(witnessIndex).placement();
            TetrominoType nextType = fullSequence.get(index + 1);

            ConstructionSafetyGuard.Assessment halfAssessment =
                    halfGuard.begin(baseline, nextType)
                            .assess(witnessIndex + 1, witnessPlacement);
            ConstructionSafetyGuard.Assessment preserveAssessment =
                    preserveGuard.begin(baseline, nextType)
                            .assess(witnessIndex + 1, witnessPlacement);

            assertTrue(halfAssessment.allowed());
            if (!preserveAssessment.allowed()) {
                preserveRejected++;
            }
            board = witnessPlacement.resultingBoard();
        }

        assertTrue(preserveRejected > 0);
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
