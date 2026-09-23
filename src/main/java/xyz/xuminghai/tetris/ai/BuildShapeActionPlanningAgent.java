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
 * Occupancy-only creative planner that incrementally moves the resulting board toward a
 * Tetris-aware target silhouette while remaining inside the state-aware objective safety budget.
 *
 * <p>The runtime planner keeps the SURVIVAL heuristic top-1 as the risk baseline and uses the
 * heuristic top five as a survival-quality prior for creative planning. Every candidate in that
 * shortlist must still pass {@link ObjectiveSafetyBudget} relative to the same SURVIVAL top-1 board
 * before it may compete on shape progress. Required visual cells are rewarded, forbidden visual
 * cells are penalized and support cells are neutral. DANGER disables creative deviation entirely
 * and returns SURVIVAL top-1.</p>
 *
 * <p>The optional preview-lookahead mode is benchmark-only. It keeps the same current top-five
 * envelope and safety boundary, then evaluates the known preview piece with another top-five,
 * independently risk-controlled search. This allows an otherwise-neutral current move to act as a
 * setup for better next-turn shape progress without widening either turn's survival-quality
 * prior.</p>
 */
public final class BuildShapeActionPlanningAgent implements AiPlanningAgent {

    static final int MAX_CURRENT_CANDIDATES = 5;
    private static final Consumer<BuildShapeDecisionObservation> NOOP_OBSERVER = ignored -> {
    };

    private final ShapeTarget target;
    private final ObjectiveRiskController riskController;
    private final Consumer<BuildShapeDecisionObservation> observer;
    private final boolean previewLookahead;

    public BuildShapeActionPlanningAgent() {
        this(ShapeTarget.HEART, ObjectiveRiskController.adaptive(), NOOP_OBSERVER, false);
    }

    public BuildShapeActionPlanningAgent(ShapeTarget target) {
        this(target, ObjectiveRiskController.adaptive(), NOOP_OBSERVER, false);
    }

    public BuildShapeActionPlanningAgent(
            ShapeTarget target,
            Consumer<BuildShapeDecisionObservation> observer) {
        this(target, ObjectiveRiskController.adaptive(), observer, false);
    }

    /**
     * Creates the benchmark-only one-piece preview experiment.
     *
     * <p>This does not change the runtime BUILD_SHAPE factory path. The experiment exists so the
     * preview hypothesis can be measured against the current greedy planner before adoption.</p>
     */
    public static BuildShapeActionPlanningAgent previewLookahead(
            ShapeTarget target,
            Consumer<BuildShapeDecisionObservation> observer) {
        return new BuildShapeActionPlanningAgent(
                target,
                ObjectiveRiskController.adaptive(),
                observer,
                true);
    }

    BuildShapeActionPlanningAgent(
            ShapeTarget target,
            ObjectiveRiskController riskController,
            Consumer<BuildShapeDecisionObservation> observer) {
        this(target, riskController, observer, false);
    }

    private BuildShapeActionPlanningAgent(
            ShapeTarget target,
            ObjectiveRiskController riskController,
            Consumer<BuildShapeDecisionObservation> observer,
            boolean previewLookahead) {
        this.target = Objects.requireNonNull(target, "target");
        this.riskController = Objects.requireNonNull(riskController, "riskController");
        this.observer = Objects.requireNonNull(observer, "observer");
        this.previewLookahead = previewLookahead;
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
        ObjectiveRiskController.Decision riskDecision =
                riskController.decide(survivalBaseline);
        ObjectiveSafetyBudget safetyBudget = riskDecision.profile().budget();
        ShapeProgress baselineProgress =
                target.progress(survivalBaseline.resultingBoard());

        List<ObjectiveCandidate> evaluated =
                assess(shortlist, survivalBaseline, safetyBudget);
        int safetyEligibleCandidates =
                (int) evaluated.stream().filter(ObjectiveCandidate::safetyEligible).count();
        boolean creativeSuppressed =
                riskDecision.level() == ObjectiveRiskController.RiskLevel.DANGER;

        ObjectiveCandidate selected = evaluated.getFirst();
        if (!creativeSuppressed) {
            selected = chooseImmediate(evaluated);
            if (previewLookahead && snapshot.nextType().isPresent()) {
                selected = choosePreview(
                        evaluated,
                        snapshot.nextType().orElseThrow(),
                        selected);
            }
        }

        observer.accept(new BuildShapeDecisionObservation(
                target,
                ranked.size(),
                shortlist.size(),
                safetyEligibleCandidates,
                riskDecision.level(),
                riskDecision.profile(),
                riskDecision.headroom(),
                riskDecision.holes(),
                creativeSuppressed,
                selected.currentRank(),
                baselineProgress,
                selected.progress(),
                selected.safety().clearedLinesDelta(),
                selected.safety().aggregateHeightDelta(),
                selected.safety().holesDelta(),
                selected.safety().bumpinessDelta()));
        return selected.candidate().plan();
    }

    private List<ObjectiveCandidate> assess(
            List<ActionPlanCandidates.PlannedCandidate> shortlist,
            PlacementCandidate survivalBaseline,
            ObjectiveSafetyBudget safetyBudget) {
        List<ObjectiveCandidate> evaluated = new ArrayList<>(shortlist.size());
        for (int index = 0; index < shortlist.size(); index++) {
            ActionPlanCandidates.PlannedCandidate candidate = shortlist.get(index);
            evaluated.add(new ObjectiveCandidate(
                    index + 1,
                    candidate,
                    safetyBudget.assess(survivalBaseline, candidate.placement()),
                    target.progress(candidate.placement().resultingBoard())));
        }
        return List.copyOf(evaluated);
    }

    private static ObjectiveCandidate chooseImmediate(List<ObjectiveCandidate> evaluated) {
        ObjectiveCandidate selected = evaluated.getFirst();
        for (ObjectiveCandidate candidate : evaluated) {
            if (candidate.safetyEligible() && betterShape(candidate, selected)) {
                selected = candidate;
            }
        }
        return selected;
    }

    private ObjectiveCandidate choosePreview(
            List<ObjectiveCandidate> evaluated,
            TetrominoType nextType,
            ObjectiveCandidate immediateSelection) {
        SetupCandidate selected = null;
        for (ObjectiveCandidate candidate : evaluated) {
            if (!candidate.safetyEligible()) {
                continue;
            }
            FutureShapeOpportunity opportunity = FutureShapeOpportunity.evaluate(
                    candidate,
                    nextType,
                    target,
                    riskController);
            if (!opportunity.available()) {
                continue;
            }
            SetupCandidate setup = new SetupCandidate(candidate, opportunity);
            if (selected == null || betterSetup(setup, selected)) {
                selected = setup;
            }
        }
        return selected == null ? immediateSelection : selected.current();
    }

    private static boolean betterSetup(
            SetupCandidate candidate,
            SetupCandidate incumbent) {
        int comparison = compareProgress(
                candidate.opportunity().progress(),
                incumbent.opportunity().progress());
        if (comparison != 0) {
            return comparison > 0;
        }

        comparison = compareProgress(
                candidate.current().progress(),
                incumbent.current().progress());
        if (comparison != 0) {
            return comparison > 0;
        }

        return candidate.current().currentRank() < incumbent.current().currentRank();
    }

    private static boolean betterShape(
            ObjectiveCandidate candidate,
            ObjectiveCandidate incumbent) {
        int comparison = compareProgress(candidate.progress(), incumbent.progress());
        if (comparison != 0) {
            return comparison > 0;
        }

        return candidate.currentRank() < incumbent.currentRank();
    }

    private static int compareProgress(ShapeProgress left, ShapeProgress right) {
        int comparison = Integer.compare(left.netScore(), right.netScore());
        if (comparison != 0) {
            return comparison;
        }

        return Integer.compare(
                right.forbiddenOccupiedCells(),
                left.forbiddenOccupiedCells());
    }

    private record ObjectiveCandidate(
            int currentRank,
            ActionPlanCandidates.PlannedCandidate candidate,
            ObjectiveSafetyBudget.Assessment safety,
            ShapeProgress progress) {

        ObjectiveCandidate {
            Objects.requireNonNull(candidate, "candidate");
            Objects.requireNonNull(safety, "safety");
            Objects.requireNonNull(progress, "progress");
        }

        boolean safetyEligible() {
            return safety.allowed();
        }
    }

    private record SetupCandidate(
            ObjectiveCandidate current,
            FutureShapeOpportunity opportunity) {
    }

    private record FutureShapeOpportunity(
            boolean available,
            ShapeProgress progress) {

        FutureShapeOpportunity {
            Objects.requireNonNull(progress, "progress");
        }

        static FutureShapeOpportunity evaluate(
                ObjectiveCandidate current,
                TetrominoType nextType,
                ShapeTarget target,
                ObjectiveRiskController riskController) {
            GameSnapshot previewSnapshot = BoardSimulator.snapshotForSpawnedPiece(
                    current.candidate().placement().resultingBoard(),
                    nextType);
            List<ActionPlanCandidates.PlannedCandidate> ranked =
                    ActionPlanCandidates.ranked(previewSnapshot);
            if (ranked.isEmpty()) {
                return new FutureShapeOpportunity(false, current.progress());
            }

            List<ActionPlanCandidates.PlannedCandidate> shortlist =
                    ranked.subList(0, Math.min(MAX_CURRENT_CANDIDATES, ranked.size()));
            PlacementCandidate survivalBaseline = shortlist.getFirst().placement();
            ObjectiveRiskController.Decision riskDecision =
                    riskController.decide(survivalBaseline);
            ObjectiveSafetyBudget safetyBudget = riskDecision.profile().budget();

            List<ObjectiveCandidate> evaluated = new ArrayList<>(shortlist.size());
            for (int index = 0; index < shortlist.size(); index++) {
                ActionPlanCandidates.PlannedCandidate candidate = shortlist.get(index);
                evaluated.add(new ObjectiveCandidate(
                        index + 1,
                        candidate,
                        safetyBudget.assess(survivalBaseline, candidate.placement()),
                        target.progress(candidate.placement().resultingBoard())));
            }

            ObjectiveCandidate selected = evaluated.getFirst();
            if (riskDecision.level() != ObjectiveRiskController.RiskLevel.DANGER) {
                selected = chooseImmediate(evaluated);
            }
            return new FutureShapeOpportunity(true, selected.progress());
        }
    }
}
