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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActionPlanCandidatesTest {

    @Test
    void classifiesActionOnlyOutcomesAgainstLegacyPlacementReachability() {
        GameSnapshot snapshot = new GameSnapshot(
                20,
                10,
                new boolean[20][10],
                TetrominoType.T,
                List.of(
                        new BoardPosition(0, 4),
                        new BoardPosition(1, 3),
                        new BoardPosition(1, 4),
                        new BoardPosition(1, 5)));

        List<boolean[][]> placementBoards = BoardSimulator.candidates(snapshot).stream()
                .map(PlacementCandidate::resultingBoard)
                .toList();
        List<ActionPlanCandidates.PlannedCandidate> ranked =
                ActionPlanCandidates.ranked(snapshot);

        assertTrue(ranked.stream().anyMatch(ActionPlanCandidates.PlannedCandidate::actionOnly));
        assertTrue(ranked.stream().anyMatch(ActionPlanCandidates.PlannedCandidate::legacyPlacementReachable));

        for (ActionPlanCandidates.PlannedCandidate candidate : ranked) {
            boolean placementReachable = placementBoards.stream()
                    .anyMatch(board -> Arrays.deepEquals(
                            board,
                            candidate.placement().resultingBoard()));
            if (candidate.actionOnly()) {
                assertFalse(placementReachable);
            }
            else {
                assertTrue(placementReachable);
            }
        }
    }
}
