/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import xyz.xuminghai.tetris.core.BoardPosition;
import xyz.xuminghai.tetris.core.BoardRules;

import java.util.ArrayList;
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

        List<PlannedCandidate> planned = new ArrayList<>();
        for (ActionStateSearch.ReachableLanding landing : ActionStateSearch.landings(snapshot)) {
            PlacementCandidate candidate = describe(snapshot, landing);
            if (candidate != null) {
                planned.add(new PlannedCandidate(landing.plan(), candidate));
            }
        }
        if (planned.isEmpty()) {
            return AiPlan.fromPlacement(AiMove.NONE);
        }

        List<PlacementCandidate> ranked =
                HeuristicTetrisAgent.rankCandidates(planned.stream().map(PlannedCandidate::candidate).toList());
        PlacementCandidate best = ranked.getFirst();
        return planned.stream()
                .filter(candidate -> candidate.candidate() == best)
                .map(PlannedCandidate::plan)
                .findFirst()
                .orElseThrow();
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

    private record PlannedCandidate(AiPlan plan, PlacementCandidate candidate) {
    }
}
