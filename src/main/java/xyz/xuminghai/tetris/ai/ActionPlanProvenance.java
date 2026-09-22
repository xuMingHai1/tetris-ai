/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import xyz.xuminghai.tetris.core.BoardPosition;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Classifies action-native outcomes against the legacy placement reachability boundary.
 *
 * <p>Reachability is compared by equivalent post-lock/post-row-clear board outcome, matching the
 * existing {@link ActionReachabilityBenchmark} semantics. This helper is observational only and is
 * intentionally called after action candidates are ranked so provenance cannot influence search or
 * heuristic ordering.</p>
 */
final class ActionPlanProvenance {

    private ActionPlanProvenance() {
    }

    static Classification classify(
            GameSnapshot snapshot,
            List<ActionPlanCandidates.PlannedCandidate> candidates) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(candidates, "candidates");

        Set<List<BoardPosition>> placementOutcomes = new HashSet<>();
        for (PlacementCandidate candidate : BoardSimulator.candidates(snapshot)) {
            placementOutcomes.add(occupiedCells(candidate.resultingBoard()));
        }

        Set<ActionPlanCandidates.PlannedCandidate> actionOnly =
                Collections.newSetFromMap(new IdentityHashMap<>());
        for (ActionPlanCandidates.PlannedCandidate candidate : candidates) {
            if (!placementOutcomes.contains(occupiedCells(candidate.placement().resultingBoard()))) {
                actionOnly.add(candidate);
            }
        }
        return new Classification(actionOnly);
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

    static final class Classification {

        private final Set<ActionPlanCandidates.PlannedCandidate> actionOnly;

        private Classification(Set<ActionPlanCandidates.PlannedCandidate> actionOnly) {
            Set<ActionPlanCandidates.PlannedCandidate> copy =
                    Collections.newSetFromMap(new IdentityHashMap<>());
            copy.addAll(actionOnly);
            this.actionOnly = copy;
        }

        int actionOnlyCandidateCount() {
            return actionOnly.size();
        }

        boolean isActionOnly(ActionPlanCandidates.PlannedCandidate candidate) {
            return actionOnly.contains(candidate);
        }
    }
}
