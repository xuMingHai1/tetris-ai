/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import xyz.xuminghai.tetris.core.BoardPosition;
import xyz.xuminghai.tetris.core.BoardRules;

import java.util.List;
import java.util.Objects;

/**
 * Resolves one action-native plan to its deterministic post-lock board outcome.
 *
 * <p>The simulator reuses {@link ActionStateSearch} for every non-terminal action and
 * {@link BoardRules} for hard-drop collision and row clearing. It exists so headless evaluation
 * can advance action-native strategies without introducing benchmark-only Tetris rules.</p>
 */
public final class ActionPlanSimulator {

    private ActionPlanSimulator() {
    }

    /**
     * Returns whether the snapshot has at least one reachable visible landing.
     *
     * <p>Headless evaluation uses this as the action-native equivalent of an empty
     * {@link BoardSimulator#candidates(GameSnapshot)} result when deciding that a game has ended.</p>
     */
    public static boolean hasReachableTerminalPlacement(GameSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        return ActionStateSearch.landings(snapshot).stream()
                .anyMatch(landing -> landing.cells().stream().noneMatch(cell -> cell.row() < 0));
    }

    /**
     * Resolves a complete turn plan.
     *
     * @param snapshot immutable decision state
     * @param plan plan whose final action must be {@link AiAction#HARD_DROP}
     * @return placement facts for the post-lock/post-row-clear board
     * @throws IllegalArgumentException when the plan is structurally invalid or not terminal
     * @throws IllegalStateException when an action is not legal for the supplied snapshot
     */
    public static PlacementCandidate requireTerminalPlacement(GameSnapshot snapshot, AiPlan plan) {
        Objects.requireNonNull(snapshot, "snapshot");
        AiPlan validated = AiPlanValidator.requireValid(plan);
        List<AiAction> actions = validated.actions();
        if (actions.getLast() != AiAction.HARD_DROP) {
            throw new IllegalArgumentException("Headless turn plans must end with HARD_DROP");
        }

        List<BoardPosition> positions =
                ActionStateSearch.replay(snapshot, actions.subList(0, actions.size() - 1));
        if (positions == null) {
            throw new IllegalStateException("AI plan contains an action that is illegal for the current snapshot");
        }

        boolean[][] board = snapshot.occupied();
        List<BoardPosition> next = down(positions);
        while (BoardRules.canPlace(board, snapshot.rows(), snapshot.cols(), next)) {
            positions = next;
            next = down(positions);
        }

        if (positions.stream().anyMatch(cell -> cell.row() < 0)) {
            throw new IllegalStateException("AI plan hard-drops to a top-out position");
        }

        for (BoardPosition cell : positions) {
            board[cell.row()][cell.col()] = true;
        }
        int clearedLines = BoardRules.clearFullRows(board);
        return BoardSimulator.describeForPlan(board, clearedLines);
    }

    private static List<BoardPosition> down(List<BoardPosition> cells) {
        return cells.stream().map(BoardPosition::down).toList();
    }
}
