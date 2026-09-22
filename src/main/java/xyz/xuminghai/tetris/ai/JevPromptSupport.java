/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import xyz.xuminghai.tetris.core.TetrominoType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Shared structured prompt facts for Jev-backed placement and action-native planners.
 */
final class JevPromptSupport {

    private JevPromptSupport() {
    }

    static Map<String, Object> state(GameSnapshot snapshot) {
        Map<String, Object> metricSemantics = new LinkedHashMap<>();
        metricSemantics.put("cleared_lines", "higher is beneficial when board safety is preserved");
        metricSemantics.put("aggregate_height", "lower is safer; high values approach top-out");
        metricSemantics.put("holes", "lower is strongly preferred; buried empty cells create long-term risk");
        metricSemantics.put("bumpiness", "lower is generally better, but less important than holes and height");

        Map<String, Object> state = new LinkedHashMap<>();
        state.put("game", "Tetris");
        state.put("current_piece", snapshot.currentType().name());
        snapshot.nextType().ifPresent(type -> state.put("next_piece", type.name()));
        state.put("board_encoding", "# = occupied, . = empty");
        state.put("current_board", boardRows(snapshot.occupied()));
        state.put("rows", snapshot.rows());
        state.put("cols", snapshot.cols());
        state.put("metric_semantics", metricSemantics);
        return state;
    }

    static Map<String, Object> placementCandidate(
            PlacementCandidate candidate,
            Optional<TetrominoType> nextType) {
        Map<String, Object> move = new LinkedHashMap<>();
        move.put("clockwise_rotations", candidate.move().clockwiseRotations());
        move.put("horizontal_shift", candidate.move().horizontalShift());

        Map<String, Object> description = commonCandidate(candidate, nextType);
        description.put("move", move);
        return description;
    }

    static Map<String, Object> actionCandidate(
            AiPlan plan,
            PlacementCandidate candidate,
            Optional<TetrominoType> nextType) {
        Map<String, Object> description = commonCandidate(candidate, nextType);
        description.put("actions", plan.actions().stream().map(Enum::name).toList());
        description.put("action_count", plan.actions().size());
        return description;
    }

    static Map<String, Object> metrics(PlacementCandidate candidate) {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("cleared_lines", candidate.clearedLines());
        metrics.put("aggregate_height", candidate.aggregateHeight());
        metrics.put("holes", candidate.holes());
        metrics.put("bumpiness", candidate.bumpiness());
        return metrics;
    }

    private static Map<String, Object> commonCandidate(
            PlacementCandidate candidate,
            Optional<TetrominoType> nextType) {
        Map<String, Object> description = new LinkedHashMap<>();
        description.put("metrics", metrics(candidate));
        description.put("resulting_board", boardRows(candidate.resultingBoard()));
        nextType.ifPresent(type -> description.put(
                "next_piece_outlook",
                nextPieceOutlook(candidate, type)));
        return description;
    }

    private static Map<String, Object> nextPieceOutlook(
            PlacementCandidate candidate,
            TetrominoType nextType) {
        NextPieceOutlook evaluated = NextPieceOutlook.evaluate(candidate, nextType);

        Map<String, Object> outlook = new LinkedHashMap<>();
        outlook.put("piece", evaluated.piece().name());
        outlook.put("legal_placements", evaluated.legalPlacements());
        outlook.put("can_place", evaluated.canPlace());
        evaluated.bestLocalResponse().ifPresent(
                bestResponse -> outlook.put("best_local_response_metrics", metrics(bestResponse)));
        return outlook;
    }

    private static List<String> boardRows(boolean[][] board) {
        List<String> rows = new ArrayList<>(board.length);
        for (boolean[] row : board) {
            StringBuilder encoded = new StringBuilder(row.length);
            for (boolean occupied : row) {
                encoded.append(occupied ? '#' : '.');
            }
            rows.add(encoded.toString());
        }
        return List.copyOf(rows);
    }
}
