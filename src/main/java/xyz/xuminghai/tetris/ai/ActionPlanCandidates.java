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
import java.util.Optional;

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

    /** Finds the first SURVIVAL-ranked alternative that keeps the known preview playable. */
    static PreviewSearch firstPreviewRecoverable(GameSnapshot snapshot, int firstRankInclusive) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (firstRankInclusive <= 0) {
            throw new IllegalArgumentException("firstRankInclusive must be positive");
        }
        if (snapshot.nextType().isEmpty()) {
            throw new IllegalArgumentException("preview search requires a known preview piece");
        }

        List<PlannedCandidate> candidates = ranked(snapshot);
        if (firstRankInclusive > candidates.size()) {
            return new PreviewSearch(candidates.size(), 0, Optional.empty());
        }
        var previewType = snapshot.nextType().orElseThrow();
        for (int index = firstRankInclusive - 1; index < candidates.size(); index++) {
            PlannedCandidate candidate = candidates.get(index);
            if (ActionPlanSimulator.hasReachableTerminalPlacement(
                    BoardSimulator.snapshotForSpawnedPiece(
                            candidate.placement().resultingBoard(), previewType))) {
                return new PreviewSearch(candidates.size(), index - firstRankInclusive + 2,
                        Optional.of(candidate.plan()));
            }
        }
        return new PreviewSearch(candidates.size(),
                candidates.size() - firstRankInclusive + 1, Optional.empty());
    }

    record PreviewSearch(int totalCandidates, int candidatesProbed, Optional<AiPlan> plan) {
        PreviewSearch {
            Objects.requireNonNull(plan, "plan");
            if (totalCandidates < 0 || candidatesProbed < 0
                    || candidatesProbed > totalCandidates
                    || (plan.isPresent() && candidatesProbed == 0)) {
                throw new IllegalArgumentException("invalid preview candidate search counts");
            }
        }
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
