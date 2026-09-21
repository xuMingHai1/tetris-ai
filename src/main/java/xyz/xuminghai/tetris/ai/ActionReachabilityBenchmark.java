/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import xyz.xuminghai.tetris.core.BoardPosition;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Compares legacy placement reachability with the action-native search on the same snapshot.
 */
public final class ActionReachabilityBenchmark {

    private ActionReachabilityBenchmark() {
    }

    public static Result measure(GameSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");

        long placementStarted = System.nanoTime();
        List<PlacementCandidate> placementCandidates = BoardSimulator.candidates(snapshot);
        long placementNanos = System.nanoTime() - placementStarted;

        long actionStarted = System.nanoTime();
        ActionStateSearch.SearchResult search = ActionStateSearch.search(snapshot);
        long actionNanos = System.nanoTime() - actionStarted;

        Set<List<BoardPosition>> placementLandings = new HashSet<>();
        for (PlacementCandidate candidate : placementCandidates) {
            placementLandings.add(occupiedCells(candidate.resultingBoard()));
        }

        Set<List<BoardPosition>> actionLandings = new HashSet<>();
        for (ActionStateSearch.ReachableLanding landing : search.landings()) {
            boolean[][] board = snapshot.occupied();
            if (landing.cells().stream().anyMatch(cell -> cell.row() < 0)) {
                continue;
            }
            for (BoardPosition cell : landing.cells()) {
                board[cell.row()][cell.col()] = true;
            }
            actionLandings.add(occupiedCells(board));
        }

        Set<List<BoardPosition>> actionOnly = new HashSet<>(actionLandings);
        actionOnly.removeAll(placementLandings);
        return new Result(
                placementLandings.size(),
                actionLandings.size(),
                actionOnly.size(),
                search.visitedStates(),
                placementNanos,
                actionNanos);
    }

    private static List<BoardPosition> occupiedCells(boolean[][] board) {
        java.util.ArrayList<BoardPosition> cells = new java.util.ArrayList<>();
        for (int row = 0; row < board.length; row++) {
            for (int col = 0; col < board[row].length; col++) {
                if (board[row][col]) {
                    cells.add(new BoardPosition(row, col));
                }
            }
        }
        return List.copyOf(cells);
    }

    public record Result(
            int placementLandings,
            int actionLandings,
            int actionOnlyLandings,
            int visitedActionStates,
            long placementNanos,
            long actionNanos) {

        public double placementMillis() {
            return placementNanos / 1_000_000.0;
        }

        public double actionMillis() {
            return actionNanos / 1_000_000.0;
        }
    }
}
