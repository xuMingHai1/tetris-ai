/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import xyz.xuminghai.tetris.core.BoardPosition;
import xyz.xuminghai.tetris.core.BoardRules;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Builds action-native landing candidates and ranks them with the existing survival heuristic.
 *
 * <p>The action path remains paired with the exact {@link PlacementCandidate} used for scoring so
 * model-backed and local planners can share one deterministic reachability/safety boundary.</p>
 */
final class ActionPlanCandidates {

    private ActionPlanCandidates() {
    }

    static List<PlannedCandidate> ranked(GameSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");

        List<PlannedCandidate> planned = new ArrayList<>();
        for (ActionStateSearch.ReachableLanding landing : ActionStateSearch.landings(snapshot)) {
            PlacementCandidate candidate = describe(snapshot, landing);
            if (candidate != null) {
                planned.add(new PlannedCandidate(landing.plan(), candidate));
            }
        }
        if (planned.isEmpty()) {
            return List.of();
        }

        Map<PlacementCandidate, PlannedCandidate> byCandidate = new IdentityHashMap<>();
        for (PlannedCandidate candidate : planned) {
            byCandidate.put(candidate.placement(), candidate);
        }

        return HeuristicTetrisAgent.rankCandidates(
                        planned.stream().map(PlannedCandidate::placement).toList())
                .stream()
                .map(byCandidate::get)
                .toList();
    }

    private static PlacementCandidate describe(
            GameSnapshot snapshot, ActionStateSearch.ReachableLanding landing) {
        boolean[][] board = snapshot.occupied();
        if (landing.cells().stream().anyMatch(cell -> cell.row() < 0)) {
            return null;
        }
        for (BoardPosition cell : landing.cells()) {
            board[cell.row()][cell.col()] = true;
        }

        int clearedLines = BoardRules.clearFullRows(board);
        return BoardSimulator.describeForPlan(board, clearedLines);
    }

    record PlannedCandidate(AiPlan plan, PlacementCandidate placement) {
        PlannedCandidate {
            Objects.requireNonNull(plan, "plan");
            Objects.requireNonNull(placement, "placement");
        }
    }
}
