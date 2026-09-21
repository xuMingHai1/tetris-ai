/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import org.junit.jupiter.api.Test;
import xyz.xuminghai.tetris.core.BoardPosition;
import xyz.xuminghai.tetris.core.TetrominoType;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NextPieceHeuristicTetrisAgentTest {

    @Test
    void fallsBackToOnePlyHeuristicWhenPreviewPieceIsUnknown() {
        GameSnapshot snapshot = snapshotWithoutPreview();

        assertEquals(
                new HeuristicTetrisAgent().decide(snapshot),
                new NextPieceHeuristicTetrisAgent().decide(snapshot));
    }

    @Test
    void selectsFromTheSameCurrentSafetyShortlistWhenPreviewPieceIsKnown() {
        GameSnapshot snapshot = snapshotWithPreview();

        AiMove selected = new NextPieceHeuristicTetrisAgent().decide(snapshot);
        List<AiMove> shortlist = HeuristicTetrisAgent
                .shortlist(snapshot, NextPieceHeuristicTetrisAgent.MAX_CURRENT_CANDIDATES)
                .stream()
                .map(PlacementCandidate::move)
                .toList();

        assertTrue(shortlist.contains(selected));
    }

    @Test
    void sharedOutlookMatchesPreviewPieceSimulationAndHeuristicRanking() {
        GameSnapshot snapshot = snapshotWithPreview();
        PlacementCandidate current = HeuristicTetrisAgent.rankCandidates(snapshot).getFirst();

        NextPieceOutlook outlook =
                NextPieceOutlook.evaluate(current, snapshot.nextType().orElseThrow());
        List<PlacementCandidate> expected = HeuristicTetrisAgent.rankCandidates(
                BoardSimulator.candidatesForSpawnedPiece(
                        current.resultingBoard(),
                        snapshot.nextType().orElseThrow()));

        assertEquals(expected.size(), outlook.legalPlacements());
        assertEquals(!expected.isEmpty(), outlook.canPlace());
        if (!expected.isEmpty()) {
            assertEquals(
                    expected.getFirst().move(),
                    outlook.bestLocalResponse().orElseThrow().move());
        }
    }

    private static GameSnapshot snapshotWithPreview() {
        GameSnapshot snapshot = snapshotWithoutPreview();
        return new GameSnapshot(
                snapshot.rows(),
                snapshot.cols(),
                snapshot.occupied(),
                snapshot.currentType(),
                snapshot.currentCells(),
                TetrominoType.T);
    }

    private static GameSnapshot snapshotWithoutPreview() {
        return new GameSnapshot(
                20,
                10,
                new boolean[20][10],
                TetrominoType.I,
                List.of(
                        new BoardPosition(0, 3),
                        new BoardPosition(0, 4),
                        new BoardPosition(0, 5),
                        new BoardPosition(0, 6)));
    }
}
