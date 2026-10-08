/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import xyz.xuminghai.tetris.core.BoardPosition;
import xyz.xuminghai.tetris.core.BoardRules;
import xyz.xuminghai.tetris.core.Cell;
import xyz.xuminghai.tetris.core.Tetris;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Deterministically explores piece states reachable through the action-native control vocabulary.
 *
 * <p>The search reuses the core tetromino transformations and {@link BoardRules} collision checks.
 * Each candidate path is replayed from the captured live snapshot so stateful rotation
 * implementations keep the same orientation sequence as the live piece.</p>
 */
final class ActionStateSearch {

    private static final List<AiAction> SEARCH_ACTIONS = List.of(
            AiAction.LEFT,
            AiAction.RIGHT,
            AiAction.ROTATE_CLOCKWISE,
            AiAction.ROTATE_COUNTER_CLOCKWISE,
            AiAction.SOFT_DROP);

    private ActionStateSearch() {
    }

    static SearchResult search(GameSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        boolean[][] occupied = snapshot.occupied();
        State initial = new State(List.copyOf(snapshot.currentCells()), List.of());
        if (!canPlace(snapshot, occupied, initial.cells())) {
            return new SearchResult(List.of(), 0);
        }

        ArrayDeque<State> queue = new ArrayDeque<>();
        Set<List<BoardPosition>> visited = new HashSet<>();
        List<ReachableLanding> landings = new ArrayList<>();
        queue.add(initial);
        visited.add(canonical(initial.cells()));

        while (!queue.isEmpty()) {
            State state = queue.removeFirst();
            if (!canPlace(snapshot, occupied, down(state.cells()))) {
                List<AiAction> actions = append(state.actions(), AiAction.HARD_DROP);
                landings.add(new ReachableLanding(new AiPlan(actions), state.cells()));
            }

            for (AiAction action : SEARCH_ACTIONS) {
                List<AiAction> actions = append(state.actions(), action);
                List<BoardPosition> next = replay(snapshot, occupied, actions);
                if (next == null) {
                    continue;
                }
                List<BoardPosition> key = canonical(next);
                if (!visited.add(key)) {
                    continue;
                }
                queue.addLast(new State(next, actions));
            }
        }
        return new SearchResult(List.copyOf(landings), visited.size());
    }

    static List<ReachableLanding> landings(GameSnapshot snapshot) {
        return search(snapshot).landings();
    }

    /**
     * A straight hard drop is a reachable landing. Check it before enumerating all action paths;
     * a hidden landing still needs the full search because movement may escape the blocked column.
     */
    static boolean hasVisibleLanding(GameSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        boolean[][] occupied = snapshot.occupied();
        List<BoardPosition> cells = snapshot.currentCells();
        if (!canPlace(snapshot, occupied, cells)) {
            return false;
        }
        List<BoardPosition> next = down(cells);
        while (canPlace(snapshot, occupied, next)) {
            cells = next;
            next = down(cells);
        }
        if (cells.stream().noneMatch(cell -> cell.row() < 0)) {
            return true;
        }
        return landings(snapshot).stream()
                .anyMatch(landing -> landing.cells().stream()
                        .noneMatch(cell -> cell.row() < 0));
    }

    /**
     * Replays one non-terminal action path using the same stateful tetromino implementation as the
     * live game. Restoring the captured orientation also supports replanning a partially played
     * piece, which need not be in its factory orientation.
     *
     * @return resulting cells, or {@code null} when any intermediate action collides
     */
    static List<BoardPosition> replay(GameSnapshot snapshot, List<AiAction> actions) {
        Objects.requireNonNull(snapshot, "snapshot");
        return replay(snapshot, snapshot.occupied(), actions);
    }

    private static List<BoardPosition> replay(
            GameSnapshot snapshot, boolean[][] occupied, List<AiAction> actions) {
        Tetris tetris = snapshot.restoreCurrentPiece();

        List<BoardPosition> positions = positions(tetris);
        for (AiAction action : actions) {
            switch (action) {
                case LEFT -> tetris.leftMove();
                case RIGHT -> tetris.rightMove();
                case ROTATE_CLOCKWISE -> tetris.rotateClockwise();
                case ROTATE_COUNTER_CLOCKWISE -> tetris.rotateCounterClockwise();
                case SOFT_DROP -> tetris.downMove();
                case HARD_DROP -> throw new IllegalArgumentException(
                        "HARD_DROP is terminal and is not a search transition");
            }

            positions = positions(tetris);
            if (!canPlace(snapshot, occupied, positions)) {
                return null;
            }
        }
        return positions;
    }

    private static List<AiAction> append(List<AiAction> actions, AiAction action) {
        List<AiAction> copy = new ArrayList<>(actions.size() + 1);
        copy.addAll(actions);
        copy.add(action);
        return List.copyOf(copy);
    }

    private static List<BoardPosition> positions(Tetris tetris) {
        List<BoardPosition> positions = new ArrayList<>(4);
        for (Cell cell : tetris.getCells()) {
            positions.add(new BoardPosition(cell.getRow(), cell.getCol()));
        }
        return List.copyOf(positions);
    }

    private static boolean canPlace(
            GameSnapshot snapshot, boolean[][] occupied, List<BoardPosition> cells) {
        return BoardRules.canPlace(occupied, snapshot.rows(), snapshot.cols(), cells);
    }

    private static List<BoardPosition> down(List<BoardPosition> cells) {
        return cells.stream().map(BoardPosition::down).toList();
    }

    private static List<BoardPosition> canonical(List<BoardPosition> cells) {
        return cells.stream()
                .sorted((left, right) -> {
                    int row = Integer.compare(left.row(), right.row());
                    return row != 0 ? row : Integer.compare(left.col(), right.col());
                })
                .toList();
    }

    record SearchResult(List<ReachableLanding> landings, int visitedStates) {
        SearchResult {
            landings = List.copyOf(landings);
            if (visitedStates < 0) {
                throw new IllegalArgumentException("visitedStates must not be negative");
            }
        }
    }

    record ReachableLanding(AiPlan plan, List<BoardPosition> cells) {
        ReachableLanding {
            Objects.requireNonNull(plan, "plan");
            cells = List.copyOf(cells);
        }
    }

    private record State(List<BoardPosition> cells, List<AiAction> actions) {
    }
}
