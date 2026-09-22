/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import java.util.List;
import java.util.Objects;

/**
 * Measures how often deterministic action search discovers outcomes that the legacy placement
 * search cannot produce, while returning the same best plan used by the local action baseline.
 *
 * <p>This benchmark facade evaluates the ranked action candidates once. Provenance is classified
 * only after heuristic ranking, so observation cannot influence the selected plan.</p>
 */
public final class ActionProvenanceBenchmark {

    private static final int SAFETY_SHORTLIST_SIZE = 5;

    private ActionProvenanceBenchmark() {
    }

    /**
     * Evaluates one real decision snapshot.
     *
     * <p>{@code bestActionOnlyRank} is one-based in the full heuristic ranking and is {@code 0}
     * when no action-only outcome exists.</p>
     */
    public static Observation evaluate(GameSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");

        List<ActionPlanCandidates.PlannedCandidate> ranked = ActionPlanCandidates.ranked(snapshot);
        if (ranked.isEmpty()) {
            return new Observation(
                    AiPlan.fromPlacement(AiMove.NONE),
                    0,
                    0,
                    0,
                    0);
        }

        ActionPlanProvenance.Classification provenance =
                ActionPlanProvenance.classify(snapshot, ranked);

        int bestActionOnlyRank = 0;
        int topFiveActionOnlyCandidates = 0;
        for (int index = 0; index < ranked.size(); index++) {
            if (!provenance.isActionOnly(ranked.get(index))) {
                continue;
            }
            int rank = index + 1;
            if (bestActionOnlyRank == 0) {
                bestActionOnlyRank = rank;
            }
            if (rank <= SAFETY_SHORTLIST_SIZE) {
                topFiveActionOnlyCandidates++;
            }
        }

        return new Observation(
                ranked.getFirst().plan(),
                ranked.size(),
                provenance.actionOnlyCandidateCount(),
                bestActionOnlyRank,
                topFiveActionOnlyCandidates);
    }

    public record Observation(
            AiPlan selectedPlan,
            int totalActionCandidates,
            int totalActionOnlyCandidates,
            int bestActionOnlyRank,
            int topFiveActionOnlyCandidates) {

        public Observation {
            Objects.requireNonNull(selectedPlan, "selectedPlan");
            if (totalActionCandidates < 0) {
                throw new IllegalArgumentException("totalActionCandidates must not be negative");
            }
            if (totalActionOnlyCandidates < 0
                    || totalActionOnlyCandidates > totalActionCandidates) {
                throw new IllegalArgumentException(
                        "totalActionOnlyCandidates must be within totalActionCandidates");
            }
            if (bestActionOnlyRank < 0 || bestActionOnlyRank > totalActionCandidates) {
                throw new IllegalArgumentException(
                        "bestActionOnlyRank must be zero or within the action candidate ranking");
            }
            if ((totalActionOnlyCandidates == 0) != (bestActionOnlyRank == 0)) {
                throw new IllegalArgumentException(
                        "bestActionOnlyRank must be zero exactly when no action-only candidate exists");
            }
            if (topFiveActionOnlyCandidates < 0
                    || topFiveActionOnlyCandidates > Math.min(SAFETY_SHORTLIST_SIZE, totalActionOnlyCandidates)) {
                throw new IllegalArgumentException(
                        "topFiveActionOnlyCandidates must fit within the action-only candidate count");
            }
        }

        public boolean hasActionOnlyCandidate() {
            return totalActionOnlyCandidates > 0;
        }

        public boolean hasTopFiveActionOnlyCandidate() {
            return topFiveActionOnlyCandidates > 0;
        }
    }
}
