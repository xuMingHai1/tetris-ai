/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Jev-backed hybrid agent that asks TypeSafe to choose among a deterministic safety shortlist.
 *
 * <p>Jev never generates a Tetris action directly. Board simulation remains the source of truth,
 * while the local heuristic removes clearly weaker placements before any remote call. Jev then
 * chooses only among those already-legal shortlisted candidates and the returned id is mapped back
 * to the original {@link AiMove}.</p>
 */
public final class JevTetrisAgent implements TetrisAgent {

    static final int MAX_REMOTE_CANDIDATES = 5;

    private static final String QUESTION_ID = "move";
    private static final String INSTRUCTIONS =
            "Choose the safest Tetris placement for long-term survival from the supplied legal options. "
                    + "Avoid top-out above all. Holes are severe long-term risk; prefer fewer holes and "
                    + "lower aggregate height. Cleared lines are beneficial when they do not create a much "
                    + "riskier board. Lower bumpiness is useful but must not outweigh holes or dangerous "
                    + "stack height. Do not sacrifice survival for a smoother surface.";

    private static final Consumer<JevDecisionObservation> NOOP_OBSERVER = _ -> {
    };

    private final TypeSafeSystemOneClient client;
    private final Consumer<JevDecisionObservation> observer;

    public JevTetrisAgent(String apiKey) {
        this(new TypeSafeSystemOneClient(apiKey), NOOP_OBSERVER);
    }

    /**
     * Creates a Jev agent with an observer for evaluation telemetry.
     *
     * <p>The observer is notified only after a successful TypeSafe Choice response and never
     * participates in move selection.</p>
     */
    public JevTetrisAgent(String apiKey, Consumer<JevDecisionObservation> observer) {
        this(new TypeSafeSystemOneClient(apiKey), observer);
    }

    JevTetrisAgent(TypeSafeSystemOneClient client) {
        this(client, NOOP_OBSERVER);
    }

    JevTetrisAgent(TypeSafeSystemOneClient client, Consumer<JevDecisionObservation> observer) {
        this.client = Objects.requireNonNull(client, "client");
        this.observer = Objects.requireNonNull(observer, "observer");
    }

    @Override
    public AiMove decide(GameSnapshot snapshot) {
        List<PlacementCandidate> rankedCandidates = HeuristicTetrisAgent.rankCandidates(snapshot);
        if (rankedCandidates.isEmpty()) {
            return AiMove.NONE;
        }
        List<PlacementCandidate> candidates =
                rankedCandidates.subList(0, Math.min(MAX_REMOTE_CANDIDATES, rankedCandidates.size()));

        Map<String, PlacementCandidate> candidatesById = new LinkedHashMap<>();
        Map<String, Object> criteria = new LinkedHashMap<>();
        for (int index = 0; index < candidates.size(); index++) {
            String id = "c" + index;
            PlacementCandidate candidate = candidates.get(index);
            candidatesById.put(id, candidate);
            criteria.put(id, candidateDescription(candidate));
        }

        TypeSafeSystemOneClient.ChoiceResult result =
                client.choose(QUESTION_ID, state(snapshot), INSTRUCTIONS, criteria);
        observer.accept(new JevDecisionObservation(
                result.confidence(),
                result.inputTokens(),
                result.outputTokens(),
                candidates.size()));
        PlacementCandidate selected = candidatesById.get(result.choice());
        if (selected == null) {
            throw new IllegalStateException("TypeSafe selected an unknown placement candidate: " + result.choice());
        }
        return selected.move();
    }

    private static Map<String, Object> state(GameSnapshot snapshot) {
        Map<String, Object> metricSemantics = new LinkedHashMap<>();
        metricSemantics.put("cleared_lines", "higher is beneficial when board safety is preserved");
        metricSemantics.put("aggregate_height", "lower is safer; high values approach top-out");
        metricSemantics.put("holes", "lower is strongly preferred; buried empty cells create long-term risk");
        metricSemantics.put("bumpiness", "lower is generally better, but less important than holes and height");

        Map<String, Object> state = new LinkedHashMap<>();
        state.put("game", "Tetris");
        state.put("current_piece", snapshot.currentType().name());
        state.put("board_encoding", "# = occupied, . = empty");
        state.put("current_board", boardRows(snapshot.occupied()));
        state.put("rows", snapshot.rows());
        state.put("cols", snapshot.cols());
        state.put("metric_semantics", metricSemantics);
        return state;
    }

    private static Map<String, Object> candidateDescription(PlacementCandidate candidate) {
        Map<String, Object> move = new LinkedHashMap<>();
        move.put("clockwise_rotations", candidate.move().clockwiseRotations());
        move.put("horizontal_shift", candidate.move().horizontalShift());

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("cleared_lines", candidate.clearedLines());
        metrics.put("aggregate_height", candidate.aggregateHeight());
        metrics.put("holes", candidate.holes());
        metrics.put("bumpiness", candidate.bumpiness());

        Map<String, Object> description = new LinkedHashMap<>();
        description.put("move", move);
        description.put("metrics", metrics);
        description.put("resulting_board", boardRows(candidate.resultingBoard()));
        return description;
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
