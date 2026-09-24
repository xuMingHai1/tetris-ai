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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShapeWitnessConstraintAuditTest {

    @Test
    void auditsKnownCleanHeartWitnessAgainstRuntimeBoundaries() {
        List<TetrominoType> pieces = pieceSequence(1008L, 10);
        ShapeConstructionFeasibilityBenchmark.Result feasibility =
                ShapeConstructionFeasibilityBenchmark.search(
                        ShapeTarget.HEART,
                        pieces,
                        20,
                        10,
                        128);

        assertTrue(feasibility.cleanCompletionFound());

        ShapeWitnessConstraintAudit.Result audit =
                ShapeWitnessConstraintAudit.audit(
                        ShapeTarget.HEART,
                        feasibility.witness(),
                        20,
                        10);

        assertEquals(feasibility.piecesToCompletion(), audit.steps().size());
        assertTrue(audit.finalProgress().cleanCompletion());
        assertNotEquals(ShapeWitnessConstraintAudit.Blocker.NONE, audit.firstBlocker());
        assertTrue(audit.firstBlockedStep() > 0);
        assertTrue(audit.steps().stream().allMatch(step ->
                step.survivalRank() >= 1
                        && step.survivalRank() <= step.reachableCandidates()));
        assertTrue(audit.steps().stream().allMatch(step ->
                step.witnessHoles().totalHoles()
                        == step.witnessHoles().requiredHoles()
                                + step.witnessHoles().forbiddenHoles()
                                + step.witnessHoles().supportAllowedHoles()
                                + step.witnessHoles().outsideTargetHoles()));
        assertTrue(audit.finalWitnessHoles().forbiddenHoles() > 0);
        assertTrue(audit.finalWitnessHoles().totalHoles()
                >= audit.finalWitnessHoles().forbiddenHoles());

        ConstructionSafetyEnvelopeBenchmark.Result envelope =
                ConstructionSafetyEnvelopeBenchmark.analyze(
                        ShapeTarget.HEART,
                        feasibility.witness(),
                        20,
                        10);

        assertEquals(feasibility.piecesToCompletion(), envelope.steps().size());
        assertTrue(envelope.finalProgress().cleanCompletion());
        assertTrue(envelope.minHeadroom() > 0);
        assertTrue(envelope.maxRawHoles() >= audit.finalWitnessHoles().totalHoles());
        assertTrue(envelope.steps().stream()
                .filter(step -> step.nextPieceType().isPresent())
                .allMatch(step -> step.nextReachableOutcomes() > 0));
        assertEquals(-1, envelope.steps().getLast().nextReachableOutcomes());
    }

    @Test
    void classifiesCleanHeartNegativeSpaceWithoutTreatingRequiredCellsAsHoles() {
        boolean[][] board = new boolean[20][10];
        String[] heart = {
                ".##..##.",
                "########",
                "########",
                ".######.",
                "..####..",
                "...##..."
        };
        int rowOffset = 12;
        int colOffset = 1;
        for (int row = 0; row < heart.length; row++) {
            for (int col = 0; col < heart[row].length(); col++) {
                if (heart[row].charAt(col) == '#') {
                    board[rowOffset + row][colOffset + col] = true;
                }
            }
        }
        board[18][4] = true;
        board[19][4] = true;

        ShapeWitnessConstraintAudit.HoleBreakdown holes =
                ShapeWitnessConstraintAudit.classifyHoles(ShapeTarget.HEART, board);

        assertEquals(26, holes.totalHoles());
        assertEquals(0, holes.requiredHoles());
        assertEquals(12, holes.forbiddenHoles());
        assertEquals(14, holes.supportAllowedHoles());
        assertEquals(0, holes.outsideTargetHoles());
        assertEquals(14, holes.nonForbiddenHoles());
    }

    @Test
    void rejectsEmptyWitness() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ShapeWitnessConstraintAudit.audit(
                        ShapeTarget.HEART,
                        List.of(),
                        20,
                        10));
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
