/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.view;

import org.junit.jupiter.api.Test;
import xyz.xuminghai.tetris.ai.AiAction;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DashboardActionsTest {

    @Test
    void groupsOnlyAdjacentEqualActionsAndRetainsRealCompletionBoundaries() {
        List<AiAction> plan = List.of(AiAction.RIGHT, AiAction.RIGHT,
                AiAction.ROTATE_CLOCKWISE, AiAction.RIGHT, AiAction.SOFT_DROP,
                AiAction.SOFT_DROP, AiAction.HARD_DROP);

        assertEquals(List.of(
                new DashboardView.ActionGroup(AiAction.RIGHT, 0, 2),
                new DashboardView.ActionGroup(AiAction.ROTATE_CLOCKWISE, 2, 3),
                new DashboardView.ActionGroup(AiAction.RIGHT, 3, 4),
                new DashboardView.ActionGroup(AiAction.SOFT_DROP, 4, 6),
                new DashboardView.ActionGroup(AiAction.HARD_DROP, 6, 7)), DashboardView.groupActions(plan));
        assertTrue(DashboardView.groupActions(List.of()).isEmpty());
    }
}
