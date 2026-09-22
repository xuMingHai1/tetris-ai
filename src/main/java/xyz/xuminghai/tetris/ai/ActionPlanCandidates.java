/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import xyz.xuminghai.tetris.core.BoardPosition;
import xyz.xuminghai.tetris.core.BoardRules;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Builds action-native landing candidates and ranks them with the existing survival heuristic.
 *
 * <p>The action path remains paired with the exact {@link PlacementCandidate} used for scoring so
 * model-backed and local planners can share one deterministic reachability/safety boundary. Each
 * candidate also records whether an equivalent post-lock/post-row-clear board is reachable through
 * the legacy rotate-then-shift {@link BoardSimulator} path. That provenance is observational only
 * and must not affect ranking or provider prompts.</p>
 */
final class ActionPlanCandidates {

    private ActionPlanCandidates() {
    }

    static List<PlannedCandidate> ranked(GameSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");

        Set<List<BoardPosition>> placementOutcomes = new HashSet<>();
        for (PlacementCandidate candidate : BoardSimulator.candidates(snapshot)) {
            placementOutcomes.add(occupiedCells(candidate.resultingBoard()));
        }

        List<PlannedCandidate> planned = new ArrayList<>();
        for (ActionStateSearch.ReachableLanding landing : ActionStateSearch.landings(snapshot)) {
            PlacementCandidate candidate = describe(snapshot, landing);
            if (candidate != null) {
                boolean legacyPlacementReachable =
                        placementOutcomes.contains(occupiedCells(candidate.resultingBoard()));
                planned.add(new PlannedCandidate(
                        landing.plan(),
                        candidate,
                        legacyPlacementReachable));
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

    private static List<BoardPosition> occupiedCells(boolean[][] board) {
        List<BoardPosition> cells = new ArrayList<>();
        for (int row = 0; row < board.length; row++) {
            for (int col = 0; col < board[row].length; col++) {
                if (board[row][col]) {
                    cells.add(new BoardPosition(row, col));
                }
            }
        }
        return List.copyOf(cells);
    }

    record PlannedCandidate(
            AiPlan plan,
            PlacementCandidate placement,
            boolean legacyPlacementReachable) {

        PlannedCandidate {
            Objects.requireNonNull(plan, "plan");
            Objects.requireNonNull(placement, "placement");
        }

        boolean actionOnly() {
            return !legacyPlacementReachable;
        }
    }
}
