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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ActionPlanSimulatorTest {

    @Test
    void placementCompatibilityPlanMatchesBoardSimulatorOutcome() {
        GameSnapshot snapshot = snapshot();
        PlacementCandidate expected = BoardSimulator.candidates(snapshot).stream()
                .filter(candidate -> candidate.move().equals(new AiMove(1, -2)))
                .findFirst()
                .orElseThrow();

        PlacementCandidate actual = ActionPlanSimulator.requireTerminalPlacement(
                snapshot,
                AiPlan.fromPlacement(expected.move()));

        assertBoardsEqual(expected.resultingBoard(), actual.resultingBoard());
        assertEquals(expected.clearedLines(), actual.clearedLines());
        assertEquals(expected.aggregateHeight(), actual.aggregateHeight());
        assertEquals(expected.holes(), actual.holes());
        assertEquals(expected.bumpiness(), actual.bumpiness());
    }

    @Test
    void rejectsNonTerminalPlanForHeadlessTurn() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ActionPlanSimulator.requireTerminalPlacement(
                        snapshot(),
                        new AiPlan(List.of(AiAction.LEFT))));
    }

    @Test
    void rejectsIllegalIntermediateAction() {
        GameSnapshot snapshot = new GameSnapshot(
                8,
                6,
                new boolean[8][6],
                TetrominoType.O,
                List.of(
                        new BoardPosition(0, 0),
                        new BoardPosition(0, 1),
                        new BoardPosition(1, 0),
                        new BoardPosition(1, 1)));

        assertThrows(
                IllegalStateException.class,
                () -> ActionPlanSimulator.requireTerminalPlacement(
                        snapshot,
                        new AiPlan(List.of(AiAction.LEFT, AiAction.HARD_DROP))));
    }

    private static GameSnapshot snapshot() {
        return new GameSnapshot(
                20,
                10,
                new boolean[20][10],
                TetrominoType.T,
                List.of(
                        new BoardPosition(0, 4),
                        new BoardPosition(1, 3),
                        new BoardPosition(1, 4),
                        new BoardPosition(1, 5)));
    }

    private static void assertBoardsEqual(boolean[][] expected, boolean[][] actual) {
        assertEquals(expected.length, actual.length);
        for (int row = 0; row < expected.length; row++) {
            assertArrayEquals(expected[row], actual[row]);
        }
    }
}
