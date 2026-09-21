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
import static org.junit.jupiter.api.Assertions.assertFalse;

class DeterministicActionPlanningAgentTest {

    @Test
    void producesValidatedActionNativePlan() {
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

        AiPlan plan = new DeterministicActionPlanningAgent().plan(snapshot);

        assertFalse(plan.actions().isEmpty());
        assertEquals(AiAction.HARD_DROP, plan.actions().getLast());
        assertEquals(plan, AiPlanValidator.requireValid(plan));
    }
}
