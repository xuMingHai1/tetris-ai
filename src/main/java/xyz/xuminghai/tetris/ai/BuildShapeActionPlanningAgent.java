/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Occupancy-only creative planner that incrementally moves the resulting board toward a
 * Tetris-aware target silhouette while remaining inside the state-aware objective safety budget.
 *
 * <p>V3 keeps the SURVIVAL heuristic top-1 as the risk baseline but evaluates the full
 * action-native reachable ranking as the creative search envelope. Every non-baseline candidate
 * must still pass {@link ObjectiveSafetyBudget} relative to that same SURVIVAL top-1 board before
 * it may compete on shape progress. Required visual cells are rewarded, forbidden visual cells are
 * penalized and support cells are neutral. DANGER disables creative deviation entirely and returns
 * SURVIVAL top-1. If no eligible candidate improves visual progress, SURVIVAL is returned
 * unchanged.</p>
 */
public final class BuildShapeActionPlanningAgent implements AiPlanningAgent {

    private static final Consumer<BuildShapeDecisionObservation> NOOP_OBSERVER = ignored -> {
    };

    private final ShapeTarget target;
    private final ObjectiveRiskController riskController;
    private final Consumer<BuildShapeDecisionObservation> observer;

    public BuildShapeActionPlanningAgent() {
        this(ShapeTarget.HEART, ObjectiveRiskController.adaptive(), NOOP_OBSERVER);
    }

    public BuildShapeActionPlanningAgent(ShapeTarget target) {
        this(target, ObjectiveRiskController.adaptive(), NOOP_OBSERVER);
    }

    public BuildShapeActionPlanningAgent(
            ShapeTarget target,
            Consumer<BuildShapeDecisionObservation> observer) {
        this(target, ObjectiveRiskController.adaptive(), observer);
    }

    BuildShapeActionPlanningAgent(
            ShapeTarget target,
            ObjectiveRiskController riskController,
            Consumer<BuildShapeDecisionObservation> observer) {
        this.target = Objects.requireNonNull(target, "target");
        this.riskController = Objects.requireNonNull(riskController, "riskController");
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

        PlacementCandidate survivalBaseline = ranked.getFirst().placement();
        ObjectiveRiskController.Decision riskDecision =
                riskController.decide(survivalBaseline);
        ObjectiveSafetyBudget safetyBudget = riskDecision.profile().budget();
        ShapeProgress baselineProgress =
                target.progress(survivalBaseline.resultingBoard());

        List<ObjectiveCandidate> evaluated = new ArrayList<>(ranked.size());
        for (int index = 0; index < ranked.size(); index++) {
            ActionPlanCandidates.PlannedCandidate candidate = ranked.get(index);
            evaluated.add(new ObjectiveCandidate(
                    index + 1,
                    candidate,
                    safetyBudget.assess(survivalBaseline, candidate.placement()),
                    target.progress(candidate.placement().resultingBoard())));
        }

        int safetyEligibleCandidates =
                (int) evaluated.stream().filter(ObjectiveCandidate::safetyEligible).count();
        ObjectiveCandidate selected = evaluated.getFirst();
        boolean creativeSuppressed =
                riskDecision.level() == ObjectiveRiskController.RiskLevel.DANGER;

        if (!creativeSuppressed) {
            for (ObjectiveCandidate candidate : evaluated) {
                if (candidate.safetyEligible() && betterShape(candidate, selected)) {
                    selected = candidate;
                }
            }
        }

        observer.accept(new BuildShapeDecisionObservation(
                target,
                ranked.size(),
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

    private static boolean betterShape(
            ObjectiveCandidate candidate,
            ObjectiveCandidate incumbent) {
        int comparison = Integer.compare(
                candidate.progress().netScore(),
                incumbent.progress().netScore());
        if (comparison != 0) {
            return comparison > 0;
        }

        comparison = Integer.compare(
                incumbent.progress().forbiddenOccupiedCells(),
                candidate.progress().forbiddenOccupiedCells());
        if (comparison != 0) {
            return comparison > 0;
        }

        return candidate.currentRank() < incumbent.currentRank();
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
}
