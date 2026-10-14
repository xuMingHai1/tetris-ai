/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import org.junit.jupiter.api.Test;
import xyz.xuminghai.tetris.core.BoardRules;
import xyz.xuminghai.tetris.core.TetrominoType;
import xyz.xuminghai.tetris.ai.benchmark.HeartPrefixDiagnosticApplication;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HeartPrefixDomainTest {

    @Test
    void acceptsStaticCleanWithTwelveRawHolesButRejectsVisuallyNeutralSupportGap() {
        boolean[][] board = supportedHeart();
        var clean = HeartPrefixDomain.observe(board);
        assertTrue(clean.allowed());
        assertTrue(clean.progress().cleanCompletion());
        assertEquals(12, clean.holes().totalHoles());
        board[18][1] = false;
        var gap = HeartPrefixDomain.observe(board);
        assertTrue(gap.progress().cleanCompletion());
        assertEquals(1, gap.holes().supportAllowedHoles());
        assertFalse(gap.allowed());
    }

    @Test
    void rejectsForbiddenRequiredOutsideHolesAndHeightSeparately() {
        boolean[][] board = supportedHeart();
        board[12][1] = true;
        assertFalse(HeartPrefixDomain.observe(board).allowed());
        board = supportedHeart();
        board[14][3] = false;
        assertEquals(1, HeartPrefixDomain.observe(board).holes().requiredHoles());
        assertFalse(HeartPrefixDomain.observe(board).allowed());
        board = new boolean[20][10];
        board[18][0] = true;
        assertEquals(1, HeartPrefixDomain.observe(board).holes().outsideTargetHoles());
        assertFalse(HeartPrefixDomain.observe(board).allowed());
        for (int row = 11; row < 20; row++) {
            board[row][0] = true;
        }
        assertEquals(0, HeartPrefixDomain.observe(board).holes().totalHoles());
        assertFalse(HeartPrefixDomain.observe(board).allowed());
    }

    @Test
    void recomputesFixedAnchorAfterRealRowClear() {
        boolean[][] board = new boolean[20][10];
        java.util.Arrays.fill(board[19], true);
        board[12][2] = true; // Still REQUIRED after shift; height and exact holes must be recomputed.
        assertEquals(1, BoardRules.clearFullRows(board));
        var shifted = HeartPrefixDomain.observe(board);
        assertEquals(1, shifted.progress().matchedRequiredCells());
        assertEquals(0, shifted.progress().forbiddenOccupiedCells());
        assertEquals(7, shifted.maxColumnHeight());
        assertFalse(shifted.allowed()); // Six REQUIRED/SUPPORT holes beneath the isolated cell.
    }

    @Test
    void boundedSearchProducesDomainCountsWithoutClaimingExhaustion() {
        List<ShapeConstructionFeasibilityBenchmark.DepthEvidence> rows = new ArrayList<>();
        var result = ShapeConstructionFeasibilityBenchmark.searchHeartPrefixes(
                java.util.Collections.nCopies(24, TetrominoType.O), rows::add);
        assertFalse(rows.isEmpty());
        assertTrue(rows.stream().anyMatch(row -> row.generated() > row.admitted()));
        assertTrue(rows.stream().allMatch(row -> row.retained() <= 128));
        assertTrue(result.expandedStates() <= 2945);
        assertFalse(result.cleanCompletionFound());
    }

    @Test
    void replayRejectsLegalIncompleteAndIllegalPlansRatherThanCertifyingWitness() {
        var snapshot = BoardSimulator.snapshotForSpawnedPiece(new boolean[20][10], TetrominoType.O);
        var candidate = ActionPlanCandidates.ranked(snapshot).stream()
                .filter(value -> HeartPrefixDomain.observe(value.placement().resultingBoard()).allowed())
                .findFirst().orElseThrow();
        assertThrows(IllegalStateException.class, () -> HeartPrefixDiagnosticApplication.replay(
                List.of(new ShapeConstructionFeasibilityBenchmark.WitnessStep(
                        TetrominoType.O, candidate.plan())), 20000));
        assertThrows(IllegalArgumentException.class, () -> HeartPrefixDiagnosticApplication.replay(
                List.of(new ShapeConstructionFeasibilityBenchmark.WitnessStep(
                        TetrominoType.O, new AiPlan(List.of(AiAction.LEFT)))), 20000));
    }

    private static boolean[][] supportedHeart() {
        boolean[][] board = new boolean[20][10];
        for (int row = 0; row < 20; row++) {
            for (int col = 0; col < 10; col++) {
                var role = ShapeTarget.HEART.roleAtBoardCell(20, 10, row, col);
                board[row][col] = role == ShapeTarget.CellRole.REQUIRED
                        || role == ShapeTarget.CellRole.SUPPORT_ALLOWED;
            }
        }
        return board;
    }
}
