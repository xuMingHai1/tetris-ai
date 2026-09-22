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
 * Jev-backed action-native planner constrained to deterministic reachable plans.
 *
 * <p>The local search remains the source of truth for movement legality. Jev receives only a small
 * heuristic safety shortlist of already-reachable plans and chooses one by id, so remote reasoning
 * can exploit richer movement without inventing Tetris physics.</p>
 */
public final class JevActionPlanningAgent implements AiPlanningAgent {

    static final int MAX_REMOTE_CANDIDATES = 5;

    private static final String QUESTION_ID = "plan";
    private static final String INSTRUCTIONS =
            "Choose the safest reachable Tetris action plan for long-term survival from the supplied "
                    + "legal options. Every option is already validated by the deterministic local rules "
                    + "engine, so do not reason about whether an action sequence is physically possible. "
                    + "Prefer fewer holes and lower aggregate height; avoid top-out above all. Cleared "
                    + "lines are beneficial when board safety is preserved. Use next_piece_outlook when "
                    + "available. Action count is secondary to board safety, but avoid needless extra "
                    + "movement when outcomes are otherwise comparable.";

    private static final Consumer<JevDecisionObservation> NOOP_OBSERVER = _ -> {
    };

    private final TypeSafeSystemOneClient client;
    private final Consumer<JevDecisionObservation> observer;

    public JevActionPlanningAgent(String apiKey) {
        this(new TypeSafeSystemOneClient(apiKey), NOOP_OBSERVER);
    }

    public JevActionPlanningAgent(String apiKey, Consumer<JevDecisionObservation> observer) {
        this(new TypeSafeSystemOneClient(apiKey), observer);
    }

    JevActionPlanningAgent(TypeSafeSystemOneClient client) {
        this(client, NOOP_OBSERVER);
    }

    JevActionPlanningAgent(
            TypeSafeSystemOneClient client,
            Consumer<JevDecisionObservation> observer) {
        this.client = Objects.requireNonNull(client, "client");
        this.observer = Objects.requireNonNull(observer, "observer");
    }

    @Override
    public AiPlan plan(GameSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");

        List<ActionPlanCandidates.PlannedCandidate> ranked = ActionPlanCandidates.ranked(snapshot);
        if (ranked.isEmpty()) {
            return AiPlan.fromPlacement(AiMove.NONE);
        }
        List<ActionPlanCandidates.PlannedCandidate> candidates =
                ranked.subList(0, Math.min(MAX_REMOTE_CANDIDATES, ranked.size()));

        Map<String, ActionPlanCandidates.PlannedCandidate> candidatesById = new LinkedHashMap<>();
        Map<String, Object> criteria = new LinkedHashMap<>();
        for (int index = 0; index < candidates.size(); index++) {
            String id = "c" + index;
            ActionPlanCandidates.PlannedCandidate candidate = candidates.get(index);
            candidatesById.put(id, candidate);
            criteria.put(
                    id,
                    JevPromptSupport.actionCandidate(
                            candidate.plan(),
                            candidate.placement(),
                            snapshot.nextType()));
        }

        TypeSafeSystemOneClient.ChoiceResult result =
                client.choose(QUESTION_ID, JevPromptSupport.state(snapshot), INSTRUCTIONS, criteria);
        ActionPlanCandidates.PlannedCandidate selected = candidatesById.get(result.choice());
        if (selected == null) {
            throw new IllegalStateException(
                    "TypeSafe selected an unknown action-plan candidate: " + result.choice());
        }

        ActionPlanCandidates.PlannedCandidate heuristicTop = candidates.getFirst();
        int selectedRank = candidates.indexOf(selected) + 1;
        PlacementCandidate selectedPlacement = selected.placement();
        PlacementCandidate topPlacement = heuristicTop.placement();
        observer.accept(new JevDecisionObservation(
                result.confidence(),
                result.inputTokens(),
                result.outputTokens(),
                candidates.size(),
                selectedRank,
                selectedPlacement.clearedLines() - topPlacement.clearedLines(),
                selectedPlacement.aggregateHeight() - topPlacement.aggregateHeight(),
                selectedPlacement.holes() - topPlacement.holes(),
                selectedPlacement.bumpiness() - topPlacement.bumpiness()));

        return selected.plan();
    }
}
