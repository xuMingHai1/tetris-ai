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
 * Jev-backed agent that asks TypeSafe to choose among deterministic legal placements.
 *
 * <p>Jev never generates a Tetris action directly. Board simulation remains the source of truth:
 * this agent sends only candidates already validated by {@link BoardSimulator} and maps the
 * returned Choice id back to the original {@link AiMove}.</p>
 */
public final class JevTetrisAgent implements TetrisAgent {

    private static final String QUESTION_ID = "move";
    private static final String INSTRUCTIONS =
            "Choose the strongest legal Tetris placement for long-term survival and line-clearing potential. "
                    + "Every option is already reachable and legal. Prefer boards with fewer holes and lower "
                    + "dangerous stacking while considering cleared lines and surface stability.";

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
        List<PlacementCandidate> candidates = BoardSimulator.candidates(snapshot);
        if (candidates.isEmpty()) {
            return AiMove.NONE;
        }

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
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("game", "Tetris");
        state.put("current_piece", snapshot.currentType().name());
        state.put("board_encoding", "# = occupied, . = empty");
        state.put("current_board", boardRows(snapshot.occupied()));
        state.put("rows", snapshot.rows());
        state.put("cols", snapshot.cols());
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
