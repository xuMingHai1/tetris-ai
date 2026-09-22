/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

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
                    + "stack height. Do not sacrifice survival for a smoother surface. When a deterministic "
                    + "next_piece_outlook is provided, use it to avoid current placements that leave the known "
                    + "preview piece with dangerous or impossible follow-up placements.";

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
        List<PlacementCandidate> candidates =
                HeuristicTetrisAgent.shortlist(snapshot, MAX_REMOTE_CANDIDATES);
        if (candidates.isEmpty()) {
            return AiMove.NONE;
        }

        Map<String, PlacementCandidate> candidatesById = new LinkedHashMap<>();
        Map<String, Object> criteria = new LinkedHashMap<>();
        for (int index = 0; index < candidates.size(); index++) {
            String id = "c" + index;
            PlacementCandidate candidate = candidates.get(index);
            candidatesById.put(id, candidate);
            criteria.put(id, JevPromptSupport.placementCandidate(candidate, snapshot.nextType()));
        }

        TypeSafeSystemOneClient.ChoiceResult result =
                client.choose(QUESTION_ID, JevPromptSupport.state(snapshot), INSTRUCTIONS, criteria);
        PlacementCandidate selected = candidatesById.get(result.choice());
        if (selected == null) {
            throw new IllegalStateException("TypeSafe selected an unknown placement candidate: " + result.choice());
        }

        PlacementCandidate heuristicTopCandidate = candidates.getFirst();
        int selectedRank = candidates.indexOf(selected) + 1;
        observer.accept(new JevDecisionObservation(
                result.confidence(),
                result.inputTokens(),
                result.outputTokens(),
                candidates.size(),
                selectedRank,
                selected.clearedLines() - heuristicTopCandidate.clearedLines(),
                selected.aggregateHeight() - heuristicTopCandidate.aggregateHeight(),
                selected.holes() - heuristicTopCandidate.holes(),
                selected.bumpiness() - heuristicTopCandidate.bumpiness(),
                0,
                false));
        return selected.move();
    }
}
