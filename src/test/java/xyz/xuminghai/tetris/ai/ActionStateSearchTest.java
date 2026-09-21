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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActionStateSearchTest {

    @Test
    void exploresInterleavedSoftDropAndHorizontalMovement() {
        boolean[][] board = new boolean[6][6];
        board[2][0] = true;
        GameSnapshot snapshot = new GameSnapshot(
                6,
                6,
                board,
                TetrominoType.O,
                List.of(
                        new BoardPosition(0, 0),
                        new BoardPosition(0, 1),
                        new BoardPosition(1, 0),
                        new BoardPosition(1, 1)));

        List<ActionStateSearch.ReachableLanding> landings = ActionStateSearch.landings(snapshot);

        assertTrue(landings.stream().anyMatch(landing -> containsOrdered(
                landing.plan().actions(), AiAction.RIGHT, AiAction.SOFT_DROP, AiAction.LEFT)));
    }

    @Test
    void everyReachableLandingEndsWithHardDrop() {
        GameSnapshot snapshot = new GameSnapshot(
                6,
                6,
                new boolean[6][6],
                TetrominoType.T,
                List.of(
                        new BoardPosition(0, 2),
                        new BoardPosition(1, 1),
                        new BoardPosition(1, 2),
                        new BoardPosition(1, 3)));

        List<ActionStateSearch.ReachableLanding> landings = ActionStateSearch.landings(snapshot);

        assertFalse(landings.isEmpty());
        assertTrue(landings.stream().allMatch(
                landing -> landing.plan().actions().getLast() == AiAction.HARD_DROP));
    }

    private static boolean containsOrdered(List<AiAction> actions, AiAction first, AiAction second, AiAction third) {
        int firstIndex = actions.indexOf(first);
        if (firstIndex < 0) {
            return false;
        }
        int secondIndex = actions.subList(firstIndex + 1, actions.size()).indexOf(second);
        if (secondIndex < 0) {
            return false;
        }
        secondIndex += firstIndex + 1;
        int thirdIndex = actions.subList(secondIndex + 1, actions.size()).indexOf(third);
        return thirdIndex >= 0;
    }
}
