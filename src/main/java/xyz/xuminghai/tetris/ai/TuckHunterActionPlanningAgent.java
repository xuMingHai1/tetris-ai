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
 * Deterministic objective planner that tries to create a useful next-turn tuck opportunity while
 * staying inside an explicit risk budget relative to the current survival top choice.
 *
 * <p>The planner starts from the existing heuristic top five action-native plans, then applies
 * {@link ObjectiveSafetyBudget}. A current action-only landing may execute only when it is
 * safety-eligible. Future setup evaluation is performed only for safety-eligible current choices,
 * and the preview opportunity itself must also contain a safety-eligible action-only landing inside
 * the preview piece's top five. If no objective opportunity survives these guards, the planner
 * returns the current survival top choice unchanged.</p>
 */
public final class TuckHunterActionPlanningAgent implements AiPlanningAgent {

    static final int MAX_CURRENT_CANDIDATES = 5;
    private static final Consumer<TuckHunterDecisionObservation> NOOP_OBSERVER = ignored -> {
    };

    private final ObjectiveSafetyBudget safetyBudget;
    private final Consumer<TuckHunterDecisionObservation> observer;

    public TuckHunterActionPlanningAgent() {
        this(ObjectiveSafetyBudget.conservative(), NOOP_OBSERVER);
    }

    public TuckHunterActionPlanningAgent(Consumer<TuckHunterDecisionObservation> observer) {
        this(ObjectiveSafetyBudget.conservative(), observer);
    }

    TuckHunterActionPlanningAgent(
            ObjectiveSafetyBudget safetyBudget,
            Consumer<TuckHunterDecisionObservation> observer) {
        this.safetyBudget = Objects.requireNonNull(safetyBudget, "safetyBudget");
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
        PlacementCandidate survivalBaseline = shortlist.getFirst().placement();
        List<ObjectiveCandidate> evaluated =
                assess(shortlist, survivalBaseline, safetyBudget);
        int safetyEligibleCandidates =
                (int) evaluated.stream().filter(ObjectiveCandidate::safetyEligible).count();

        List<ActionPlanCandidates.PlannedCandidate> safetyEligiblePlans = evaluated.stream()
                .filter(ObjectiveCandidate::safetyEligible)
                .map(ObjectiveCandidate::candidate)
                .toList();
        ActionPlanProvenance.Classification currentProvenance =
                ActionPlanProvenance.classify(snapshot, safetyEligiblePlans);

        for (ObjectiveCandidate candidate : evaluated) {
            if (candidate.safetyEligible()
                    && currentProvenance.isActionOnly(candidate.candidate())) {
                observe(
                        shortlist.size(),
                        safetyEligibleCandidates,
                        candidate.currentRank(),
                        true,
                        0,
                        FutureOpportunity.NONE,
                        candidate.safety());
                return candidate.candidate().plan();
            }
        }

        if (snapshot.nextType().isEmpty()) {
            ObjectiveCandidate fallback = evaluated.getFirst();
            observe(
                    shortlist.size(),
                    safetyEligibleCandidates,
                    1,
                    false,
                    0,
                    FutureOpportunity.NONE,
                    fallback.safety());
            return fallback.candidate().plan();
        }

        TetrominoType nextType = snapshot.nextType().orElseThrow();
        List<SetupCandidate> setups = evaluated.stream()
                .filter(ObjectiveCandidate::safetyEligible)
                .map(candidate -> new SetupCandidate(
                        candidate,
                        FutureOpportunity.evaluate(
                                candidate.candidate().placement(),
                                nextType,
                                safetyBudget)))
                .toList();

        SetupCandidate selected = choose(setups);
        int setupCandidates =
                (int) setups.stream().filter(SetupCandidate::createsTopFiveOpportunity).count();
        observe(
                shortlist.size(),
                safetyEligibleCandidates,
                selected.current().currentRank(),
                false,
                setupCandidates,
                selected.opportunity(),
                selected.current().safety());
        return selected.current().candidate().plan();
    }

    private void observe(
            int candidateCount,
            int safetyEligibleCandidates,
            int selectedRank,
            boolean selectedCurrentActionOnly,
            int setupCandidates,
            FutureOpportunity opportunity,
            ObjectiveSafetyBudget.Assessment safety) {
        observer.accept(new TuckHunterDecisionObservation(
                candidateCount,
                safetyEligibleCandidates,
                selectedRank,
                selectedCurrentActionOnly,
                setupCandidates,
                opportunity.actionOnlyCandidates(),
                opportunity.topFiveActionOnlyCandidates(),
                opportunity.bestActionOnlyRank(),
                safety.clearedLinesDelta(),
                safety.aggregateHeightDelta(),
                safety.holesDelta(),
                safety.bumpinessDelta()));
    }

    private static List<ObjectiveCandidate> assess(
            List<ActionPlanCandidates.PlannedCandidate> shortlist,
            PlacementCandidate survivalBaseline,
            ObjectiveSafetyBudget safetyBudget) {
        List<ObjectiveCandidate> evaluated = new ArrayList<>(shortlist.size());
        for (int index = 0; index < shortlist.size(); index++) {
            ActionPlanCandidates.PlannedCandidate candidate = shortlist.get(index);
            evaluated.add(new ObjectiveCandidate(
                    index + 1,
                    candidate,
                    safetyBudget.assess(survivalBaseline, candidate.placement())));
        }
        return List.copyOf(evaluated);
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

                    return Integer.compare(
                            left.current().currentRank(),
                            right.current().currentRank());
                })
                .orElseGet(() -> setups.getFirst());
    }

    private record ObjectiveCandidate(
            int currentRank,
            ActionPlanCandidates.PlannedCandidate candidate,
            ObjectiveSafetyBudget.Assessment safety) {

        boolean safetyEligible() {
            return safety.allowed();
        }
    }

    private record SetupCandidate(
            ObjectiveCandidate current,
            FutureOpportunity opportunity) {

        boolean createsTopFiveOpportunity() {
            return opportunity.topFiveActionOnlyCandidates() > 0;
        }
    }

    private record FutureOpportunity(
            int actionOnlyCandidates,
            int bestActionOnlyRank,
            int topFiveActionOnlyCandidates) {

        private static final FutureOpportunity NONE =
                new FutureOpportunity(0, 0, 0);

        static FutureOpportunity evaluate(
                PlacementCandidate currentCandidate,
                TetrominoType nextType,
                ObjectiveSafetyBudget safetyBudget) {
            GameSnapshot previewSnapshot = BoardSimulator.snapshotForSpawnedPiece(
                    currentCandidate.resultingBoard(),
                    nextType);
            List<ActionPlanCandidates.PlannedCandidate> ranked =
                    ActionPlanCandidates.ranked(previewSnapshot);
            if (ranked.isEmpty()) {
                return NONE;
            }

            PlacementCandidate futureSurvivalBaseline =
                    ranked.getFirst().placement();
            ActionPlanProvenance.Classification provenance =
                    ActionPlanProvenance.classify(previewSnapshot, ranked);
            int actionOnlyCount = 0;
            int bestRank = 0;
            int topFiveCount = 0;

            for (int index = 0; index < ranked.size(); index++) {
                ActionPlanCandidates.PlannedCandidate candidate = ranked.get(index);
                if (!provenance.isActionOnly(candidate)
                        || !safetyBudget
                                .assess(futureSurvivalBaseline, candidate.placement())
                                .allowed()) {
                    continue;
                }

                int rank = index + 1;
                actionOnlyCount++;
                if (bestRank == 0) {
                    bestRank = rank;
                }
                if (rank <= MAX_CURRENT_CANDIDATES) {
                    topFiveCount++;
                }
            }

            return new FutureOpportunity(
                    actionOnlyCount,
                    bestRank,
                    topFiveCount);
        }
    }
}
