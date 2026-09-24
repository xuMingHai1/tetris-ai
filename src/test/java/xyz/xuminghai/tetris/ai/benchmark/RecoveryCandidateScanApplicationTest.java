/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai.benchmark;

import org.junit.jupiter.api.Test;
import xyz.xuminghai.tetris.ai.AiPlan;
import xyz.xuminghai.tetris.ai.PlacementCandidate;
import xyz.xuminghai.tetris.ai.RecoveryCandidateScanBenchmark;
import xyz.xuminghai.tetris.ai.RecoveryRobustnessBenchmark;
import xyz.xuminghai.tetris.core.TetrominoType;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RecoveryCandidateScanApplicationTest {

    @Test
    void analysisReportsEarliestWarningClearingRankWithoutRerankingCandidates() {
        var scan = List.of(
                candidate(1, probe(1, 20)),
                candidate(2, probe(4, 12)),
                candidate(3, probe(6, 10)));

        RecoveryCandidateScanApplication.ScanFacts facts =
                RecoveryCandidateScanApplication.analyze(scan);

        assertEquals(2, facts.clearCandidateCount());
        assertEquals(2, facts.earliestClear().survivalRank());
        assertEquals(3, facts.bestHeadroom().survivalRank());
        assertEquals(3, facts.bestPostUnknown().survivalRank());
    }

    private static RecoveryCandidateScanBenchmark.CandidateAnalysis candidate(
            int rank,
            RecoveryRobustnessBenchmark.Probe probe) {
        return new RecoveryCandidateScanBenchmark.CandidateAnalysis(
                rank,
                AiPlan.fromPlacement(new xyz.xuminghai.tetris.ai.AiMove(0, 0)),
                new PlacementCandidate(
                        new xyz.xuminghai.tetris.ai.AiMove(0, 0),
                        new boolean[20][10],
                        0,
                        0,
                        0,
                        0),
                probe);
    }

    private static RecoveryRobustnessBenchmark.Probe probe(
            int headroom,
            int holes) {
        return new RecoveryRobustnessBenchmark.Probe(
                TetrominoType.I,
                10,
                true,
                headroom,
                30,
                holes,
                8,
                7,
                0,
                8,
                10.0,
                12,
                headroom,
                holes,
                40);
    }
}
