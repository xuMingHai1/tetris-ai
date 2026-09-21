/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AiPlanValidatorTest {

    @Test
    void acceptsActionNativePlan() {
        AiPlan plan = new AiPlan(List.of(
                AiAction.LEFT,
                AiAction.SOFT_DROP,
                AiAction.ROTATE_CLOCKWISE,
                AiAction.RIGHT,
                AiAction.HARD_DROP));

        assertEquals(plan, AiPlanValidator.requireValid(plan));
    }

    @Test
    void rejectsEmptyPlan() {
        assertThrows(IllegalArgumentException.class, () -> AiPlanValidator.requireValid(new AiPlan(List.of())));
    }

    @Test
    void rejectsActionsAfterHardDrop() {
        AiPlan plan = new AiPlan(List.of(AiAction.HARD_DROP, AiAction.LEFT));

        assertThrows(IllegalArgumentException.class, () -> AiPlanValidator.requireValid(plan));
    }

    @Test
    void rejectsUnboundedPlan() {
        AiPlan plan = new AiPlan(Collections.nCopies(AiPlanValidator.MAX_ACTIONS + 1, AiAction.SOFT_DROP));

        assertThrows(IllegalArgumentException.class, () -> AiPlanValidator.requireValid(plan));
    }
}
