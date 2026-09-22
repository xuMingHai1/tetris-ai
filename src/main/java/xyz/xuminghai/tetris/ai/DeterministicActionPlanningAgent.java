/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import java.util.List;
import java.util.Objects;

/**
 * Deterministic action-native baseline.
 *
 * <p>The planner explores every state reachable by legal primitive controls, then ranks the
 * resulting landing boards with the same survival heuristic used by the placement baseline. This
 * isolates the value of richer movement paths from any remote-model behavior.</p>
 */
public final class DeterministicActionPlanningAgent implements AiPlanningAgent {

    @Override
    public AiPlan plan(GameSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");

        List<ActionPlanCandidates.PlannedCandidate> ranked = ActionPlanCandidates.ranked(snapshot);
        return ranked.isEmpty() ? AiPlan.fromPlacement(AiMove.NONE) : ranked.getFirst().plan();
    }
}
