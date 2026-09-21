/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Ordered controls that an AI wants the live game to execute for the current piece.
 *
 * <p>The current placement agents still decide an {@link AiMove}. {@link #fromPlacement(AiMove)}
 * is the compatibility boundary that expands that compact placement into explicit controls. Future
 * agents can target this action vocabulary without moving game rules into the agent layer.</p>
 */
public record AiPlan(List<AiAction> actions) {

    public AiPlan {
        Objects.requireNonNull(actions, "actions");
        actions = List.copyOf(actions);
        if (actions.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("actions must not contain null");
        }
    }

    /**
     * Expands the existing rotate-then-shift placement contract and terminates it with hard drop.
     */
    public static AiPlan fromPlacement(AiMove move) {
        Objects.requireNonNull(move, "move");

        List<AiAction> actions = new ArrayList<>();
        for (int rotation = 0; rotation < move.clockwiseRotations(); rotation++) {
            actions.add(AiAction.ROTATE_CLOCKWISE);
        }

        AiAction horizontal = move.horizontalShift() < 0 ? AiAction.LEFT : AiAction.RIGHT;
        for (int step = 0; step < Math.abs(move.horizontalShift()); step++) {
            actions.add(horizontal);
        }

        actions.add(AiAction.HARD_DROP);
        return new AiPlan(actions);
    }
}
