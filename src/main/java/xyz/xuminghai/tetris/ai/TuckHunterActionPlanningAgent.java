/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import xyz.xuminghai.tetris.core.TetrominoType;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Deterministic objective planner that tries to create a useful next-turn tuck opportunity without
 * leaving the current survival safety boundary.
 *
 * <p>The planner considers only the current heuristic top five action-native plans. For each one it
 * spawns the known preview piece against that resulting board, performs full action-native search,
 * and checks whether the preview piece would gain an action-only landing inside its own top five.
 * If no such setup exists, the planner returns the current survival top choice unchanged.</p>
 */
public final class TuckHunterActionPlanningAgent implements AiPlanningAgent {

    static final int MAX_CURRENT_CANDIDATES = 5;
    private static final Consumer<TuckHunterDecisionObservation> NOOP_OBSERVER = ignored -> {
    };

    private final Consumer<TuckHunterDecisionObservation> observer;

    public TuckHunterActionPlanningAgent() {
        this(NOOP_OBSERVER);
    }

    public TuckHunterActionPlanningAgent(Consumer<TuckHunterDecisionObservation> observer) {
        this.observer = Objects.requireNonNull(observer, "observer");
    }

    @Override
    public AiPlan plan(GameSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");

        List<ActionPlanCandidates.PlannedCandidate> ranked =
                ActionPlanCandidates.ranked(snapshot);
        if (ranked.isEmpty()) {
            return AiPlan.fromPlacement(AiMove.NONE);
        }

        List<ActionPlanCandidates.PlannedCandidate> shortlist =
                ranked.subList(0, Math.min(MAX_CURRENT_CANDIDATES, ranked.size()));
        if (snapshot.nextType().isEmpty()) {
            observeFallback(shortlist.size());
            return shortlist.getFirst().plan();
        }

        TetrominoType nextType = snapshot.nextType().orElseThrow();
        List<SetupCandidate> setups = new ArrayList<>(shortlist.size());
        for (int index = 0; index < shortlist.size(); index++) {
            ActionPlanCandidates.PlannedCandidate current = shortlist.get(index);
            setups.add(new SetupCandidate(
                    index + 1,
                    current,
                    FutureOpportunity.evaluate(current.placement(), nextType)));
        }

        SetupCandidate selected = choose(setups);
        FutureOpportunity opportunity = selected.opportunity();
        observer.accept(new TuckHunterDecisionObservation(
                shortlist.size(),
                selected.currentRank(),
                (int) setups.stream().filter(SetupCandidate::createsTopFiveOpportunity).count(),
                opportunity.actionOnlyCandidates(),
                opportunity.topFiveActionOnlyCandidates(),
                opportunity.bestActionOnlyRank()));
        return selected.candidate().plan();
    }

    private void observeFallback(int candidateCount) {
        observer.accept(new TuckHunterDecisionObservation(
                candidateCount,
                1,
                0,
                0,
                0,
                0));
    }

    private static SetupCandidate choose(List<SetupCandidate> setups) {
        return setups.stream()
                .filter(SetupCandidate::createsTopFiveOpportunity)
                .min((left, right) -> {
                    int rankComparison = Integer.compare(
                            left.opportunity().bestActionOnlyRank(),
                            right.opportunity().bestActionOnlyRank());
                    if (rankComparison != 0) {
                        return rankComparison;
                    }

                    int topFiveComparison = Integer.compare(
                            right.opportunity().topFiveActionOnlyCandidates(),
                            left.opportunity().topFiveActionOnlyCandidates());
                    if (topFiveComparison != 0) {
                        return topFiveComparison;
                    }

                    int countComparison = Integer.compare(
                            right.opportunity().actionOnlyCandidates(),
                            left.opportunity().actionOnlyCandidates());
                    if (countComparison != 0) {
                        return countComparison;
                    }

                    return Integer.compare(left.currentRank(), right.currentRank());
                })
                .orElseGet(() -> setups.getFirst());
    }

    private record SetupCandidate(
            int currentRank,
            ActionPlanCandidates.PlannedCandidate candidate,
            FutureOpportunity opportunity) {

        boolean createsTopFiveOpportunity() {
            return opportunity.topFiveActionOnlyCandidates() > 0;
        }
    }

    private record FutureOpportunity(
            int actionOnlyCandidates,
            int bestActionOnlyRank,
            int topFiveActionOnlyCandidates) {

        static FutureOpportunity evaluate(
                PlacementCandidate currentCandidate,
                TetrominoType nextType) {
            GameSnapshot previewSnapshot = BoardSimulator.snapshotForSpawnedPiece(
                    currentCandidate.resultingBoard(),
                    nextType);
            List<ActionPlanCandidates.PlannedCandidate> ranked =
                    ActionPlanCandidates.ranked(previewSnapshot);
            if (ranked.isEmpty()) {
                return new FutureOpportunity(0, 0, 0);
            }

            ActionPlanProvenance.Classification provenance =
                    ActionPlanProvenance.classify(previewSnapshot, ranked);
            int bestRank = 0;
            int topFiveCount = 0;
            for (int index = 0; index < ranked.size(); index++) {
                if (!provenance.isActionOnly(ranked.get(index))) {
                    continue;
                }
                int rank = index + 1;
                if (bestRank == 0) {
                    bestRank = rank;
                }
                if (rank <= MAX_CURRENT_CANDIDATES) {
                    topFiveCount++;
                }
            }
            return new FutureOpportunity(
                    provenance.actionOnlyCandidateCount(),
                    bestRank,
                    topFiveCount);
        }
    }
}
