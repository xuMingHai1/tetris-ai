/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import org.junit.jupiter.api.Test;
import xyz.xuminghai.tetris.core.BoardPosition;
import xyz.xuminghai.tetris.core.TetrominoType;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NextPieceHeuristicTetrisAgentTest {

    @Test
    void returnsNoneWhenNoCurrentPlacementIsLegalEvenWithKnownPreview() {
        boolean[][] occupied = new boolean[20][10];
        for (boolean[] row : occupied) Arrays.fill(row, true);
        GameSnapshot snapshot = new GameSnapshot(20, 10, occupied, TetrominoType.I,
                List.of(new BoardPosition(-1, 3), new BoardPosition(-1, 4),
                        new BoardPosition(-1, 5), new BoardPosition(-1, 6)), TetrominoType.O);

        assertTrue(HeuristicTetrisAgent.rankCandidates(snapshot).isEmpty());
        assertEquals(AiMove.NONE, new NextPieceHeuristicTetrisAgent().decide(snapshot));
    }

    @Test
    void fallsBackToCurrentTopChoiceWhenEveryKnownPreviewResponseIsBlocked() {
        boolean[][] occupied = new boolean[20][10];
        Arrays.fill(occupied[0], true);
        Arrays.fill(occupied[1], true);
        occupied[0][0] = false;
        occupied[1][1] = false;
        GameSnapshot snapshot = new GameSnapshot(20, 10, occupied, TetrominoType.I,
                List.of(new BoardPosition(18, 3), new BoardPosition(18, 4),
                        new BoardPosition(18, 5), new BoardPosition(18, 6)), TetrominoType.O);
        List<PlacementCandidate> shortlist = HeuristicTetrisAgent.shortlist(
                snapshot, NextPieceHeuristicTetrisAgent.MAX_CURRENT_CANDIDATES);

        assertEquals(5, shortlist.size());
        assertTrue(shortlist.stream().allMatch(candidate ->
                !NextPieceOutlook.evaluate(candidate, TetrominoType.O).canPlace()));
        assertEquals(shortlist.getFirst().move(), new NextPieceHeuristicTetrisAgent().decide(snapshot));
    }

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
