/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AiPlanTest {

    @Test
    void expandsPlacementIntoOrderedActionsAndHardDrop() {
        AiPlan plan = AiPlan.fromPlacement(new AiMove(2, -3));

        assertEquals(List.of(
                AiAction.ROTATE_CLOCKWISE,
                AiAction.ROTATE_CLOCKWISE,
                AiAction.LEFT,
                AiAction.LEFT,
                AiAction.LEFT,
                AiAction.HARD_DROP), plan.actions());
    }

    @Test
    void zeroPlacementStillTerminatesWithHardDrop() {
        assertEquals(List.of(AiAction.HARD_DROP), AiPlan.fromPlacement(AiMove.NONE).actions());
    }

    @Test
    void defensivelyCopiesActions() {
        var actions = new java.util.ArrayList<>(List.of(AiAction.LEFT));
        AiPlan plan = new AiPlan(actions);

        actions.add(AiAction.RIGHT);

        assertEquals(List.of(AiAction.LEFT), plan.actions());
        assertThrows(UnsupportedOperationException.class, () -> plan.actions().add(AiAction.RIGHT));
    }
}
