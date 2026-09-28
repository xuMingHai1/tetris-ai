/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import org.junit.jupiter.api.Test;
import xyz.xuminghai.tetris.core.TetrominoType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecoveryCandidateScanBenchmarkTest {

    @Test
    void earlyStopSearchBeginsAtRequestedSurvivalRank() {
        GameSnapshot base =
                BoardSimulator.snapshotForSpawnedPiece(
                        new boolean[20][10],
                        TetrominoType.T);
        GameSnapshot snapshot = new GameSnapshot(
                base.rows(),
                base.cols(),
                base.occupied(),
                base.currentType(),
                base.currentCells(),
                TetrominoType.I);

        var search = RecoveryCandidateScanBenchmark.findFirstMatching(
                snapshot,
                2,
                ignored -> true);

        assertTrue(search.match().isPresent());
        assertEquals(2, search.match().orElseThrow().survivalRank());
        assertEquals(1, search.candidatesProbed());
        assertTrue(search.totalCandidates() >= 2);
    }

    @Test
    void previewOnlySearchMatchesFullProbeChoiceWithoutChangingSurvivalOrder() {
        boolean[][] partial = new boolean[20][10];
        for (int col = 0; col < 10; col++) {
            partial[19][col] = col != 4;
            partial[18][col] = col != 4 && col != 5;
        }
        for (boolean[][] board : new boolean[][][] {new boolean[20][10], partial}) {
            GameSnapshot base = BoardSimulator.snapshotForSpawnedPiece(board, TetrominoType.T);
            GameSnapshot snapshot = new GameSnapshot(base.rows(), base.cols(),
                    base.occupied(), base.currentType(), base.currentCells(), TetrominoType.I);

            var full = RecoveryCandidateScanBenchmark.findFirstMatching(
                    snapshot, 2, RecoveryRobustnessBenchmark.Probe::previewRecoverable);
            var preview = RecoveryCandidateScanBenchmark.findFirstPreviewRecoverable(snapshot, 2);

            assertEquals(full.totalCandidates(), preview.totalCandidates());
            assertEquals(full.candidatesProbed(), preview.candidatesProbed());
            assertEquals(full.match().map(RecoveryCandidateScanBenchmark.CandidateAnalysis::plan),
                    preview.plan());
            assertEquals(full.match().map(RecoveryCandidateScanBenchmark.CandidateAnalysis::survivalRank),
                    preview.plan().map(ignored -> preview.candidatesProbed() + 1));
        }
    }

    @Test
    void preservesProductionSurvivalOrderWhileAttachingRecoveryProbes() {
        GameSnapshot base =
                BoardSimulator.snapshotForSpawnedPiece(
                        new boolean[20][10],
                        TetrominoType.T);
        GameSnapshot snapshot = new GameSnapshot(
                base.rows(),
                base.cols(),
                base.occupied(),
                base.currentType(),
                base.currentCells(),
                TetrominoType.I);

        var scan = RecoveryCandidateScanBenchmark.scan(snapshot);

        assertFalse(scan.isEmpty());
        for (int index = 0; index < scan.size(); index++) {
            assertEquals(index + 1, scan.get(index).survivalRank());
            assertEquals(TetrominoType.I, scan.get(index).probe().previewType());
        }

        AiPlan survivalPlan = new DeterministicActionPlanningAgent().plan(snapshot);
        assertEquals(survivalPlan, scan.getFirst().plan());
        assertTrue(scan.getFirst().probe().previewReachableOutcomes() >= 0);
    }
}
