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
import xyz.xuminghai.tetris.core.TetrisFactory;

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
 * It therefore discovers interleaved paths such as move-down-rotate-move without introducing a
 * second implementation of Tetris geometry.</p>
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
                List<AiAction> actions = new ArrayList<>(state.actions());
                actions.add(AiAction.HARD_DROP);
                landings.add(new ReachableLanding(new AiPlan(actions), state.cells()));
            }

            for (AiAction action : SEARCH_ACTIONS) {
                List<BoardPosition> next = apply(snapshot, state.cells(), action);
                if (next == null || !canPlace(snapshot, occupied, next)) {
                    continue;
                }
                List<BoardPosition> key = canonical(next);
                if (!visited.add(key)) {
                    continue;
                }
                List<AiAction> actions = new ArrayList<>(state.actions());
                actions.add(action);
                queue.addLast(new State(next, List.copyOf(actions)));
            }
        }
        return new SearchResult(List.copyOf(landings), visited.size());
    }

    static List<ReachableLanding> landings(GameSnapshot snapshot) {
        return search(snapshot).landings();
    }

    private static List<BoardPosition> apply(
            GameSnapshot snapshot, List<BoardPosition> cells, AiAction action) {
        return switch (action) {
            case LEFT -> horizontal(cells, -1);
            case RIGHT -> horizontal(cells, 1);
            case SOFT_DROP -> down(cells);
            case ROTATE_CLOCKWISE -> rotate(snapshot, cells, true);
            case ROTATE_COUNTER_CLOCKWISE -> rotate(snapshot, cells, false);
            case HARD_DROP -> throw new IllegalArgumentException("HARD_DROP is terminal and is not a search transition");
        };
    }

    private static List<BoardPosition> rotate(
            GameSnapshot snapshot, List<BoardPosition> positions, boolean clockwise) {
        Tetris tetris = TetrisFactory.create(snapshot.currentType());
        Cell[] template = tetris.getCells();
        Cell[] cells = new Cell[template.length];
        for (int i = 0; i < cells.length; i++) {
            BoardPosition position = positions.get(i);
            cells[i] = new Cell(position.row(), position.col(), template[i].getColor());
        }
        tetris.setCells(cells);
        if (clockwise) {
            tetris.rotateClockwise();
        }
        else {
            tetris.rotateCounterClockwise();
        }
        return positions(tetris);
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

    private static List<BoardPosition> horizontal(List<BoardPosition> cells, int delta) {
        return cells.stream().map(cell -> cell.horizontal(delta)).toList();
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
