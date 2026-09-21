/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import java.util.Objects;

/**
 * Plans ordered controls for the current piece from an immutable game snapshot.
 *
 * <p>This is the action-native decision contract. Placement-oriented agents remain supported
 * through {@link #fromPlacementAgent(TetrisAgent)} so strategy migration can happen independently
 * from the live execution boundary.</p>
 */
@FunctionalInterface
public interface AiPlanningAgent {

    AiPlan plan(GameSnapshot snapshot);

    static AiPlanningAgent fromPlacementAgent(TetrisAgent agent) {
        Objects.requireNonNull(agent, "agent");
        return snapshot -> AiPlan.fromPlacement(agent.decide(snapshot));
    }
}
