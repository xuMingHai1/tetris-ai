/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import java.util.List;
import java.util.Objects;

/**
 * Enforces structural safety limits before an AI plan can reach the live game.
 *
 * <p>Board legality remains owned by the live game and its existing collision checks. This
 * validator deliberately checks only plan-level invariants so it does not become a second Tetris
 * rules engine.</p>
 */
public final class AiPlanValidator {

    static final int MAX_ACTIONS = 64;

    private AiPlanValidator() {
    }

    public static AiPlan requireValid(AiPlan plan) {
        Objects.requireNonNull(plan, "AiPlanningAgent returned null");

        List<AiAction> actions = plan.actions();
        if (actions.isEmpty()) {
            throw new IllegalArgumentException("AI plan must contain at least one action");
        }
        if (actions.size() > MAX_ACTIONS) {
            throw new IllegalArgumentException("AI plan exceeds maximum action count: " + MAX_ACTIONS);
        }

        int hardDropIndex = actions.indexOf(AiAction.HARD_DROP);
        if (hardDropIndex >= 0 && hardDropIndex != actions.size() - 1) {
            throw new IllegalArgumentException("HARD_DROP must be the final action");
        }
        return plan;
    }
}
