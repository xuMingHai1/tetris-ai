/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Benchmark-only BUILD_SHAPE experiment that replaces the current top-five + relative safety
 * envelope with a full-reachable candidate set guarded by next-piece continuation capacity.
 *
 * <p>This class is intentionally not wired through {@link AiPlanningAgentFactory}. Its purpose is
 * to test whether an absolute action-native viability signal can safely recover construction
 * choices that the current SURVIVAL-relative policy blocks. Runtime adoption requires separate
 * gameplay evidence and review.</p>
 */
public final class BuildShapeConstructionGuardPlanningAgent implements AiPlanningAgent {

    private static final Consumer<BuildShapeConstructionGuardObservation> NOOP_OBSERVER =
            ignored -> {
            };

    private static final Comparator<ObjectiveCandidate> OBJECTIVE_ORDER =
            (left, right) -> {
                int comparison = Integer.compare(
                        right.progress().netScore(),
                        left.progress().netScore());
                if (comparison != 0) {
                    return comparison;
                }
                comparison = Integer.compare(
                        left.progress().forbiddenOccupiedCells(),
                        right.progress().forbiddenOccupiedCells());
                if (comparison != 0) {
                    return comparison;
                }
                return Integer.compare(left.survivalRank(), right.survivalRank());
            };

    private final ShapeTarget target;
    private final ConstructionSafetyGuard guard;
    private final Consumer<BuildShapeConstructionGuardObservation> observer;

    public BuildShapeConstructionGuardPlanningAgent(
            ShapeTarget target,
            ConstructionSafetyGuard.Profile profile,
            Consumer<BuildShapeConstructionGuardObservation> observer) {
        this.target = Objects.requireNonNull(target, "target");
        this.guard = new ConstructionSafetyGuard(
                Objects.requireNonNull(profile, "profile"));
        this.observer = Objects.requireNonNull(observer, "observer");
    }

    public BuildShapeConstructionGuardPlanningAgent(
            ConstructionSafetyGuard.Profile profile) {
        this(ShapeTarget.HEART, profile, NOOP_OBSERVER);
    }

    @Override
    public AiPlan plan(GameSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");

        List<ActionPlanCandidates.PlannedCandidate> ranked =
                ActionPlanCandidates.ranked(snapshot);
        if (ranked.isEmpty()) {
            return AiPlan.fromPlacement(AiMove.NONE);
        }

        ObjectiveCandidate baseline = objectiveCandidate(1, ranked.getFirst());
        BoardOccupancyMetrics.Analysis baselineMetrics =
                BoardOccupancyMetrics.analyze(baseline.candidate().placement().resultingBoard());

        if (snapshot.nextType().isEmpty()) {
            observer.accept(new BuildShapeConstructionGuardObservation(
                    target,
                    profile(),
                    ranked.size(),
                    0,
                    0,
                    false,
                    -1,
                    -1,
                    1,
                    baselineMetrics.headroom(),
                    baselineMetrics.headroom(),
                    baseline.progress(),
                    baseline.progress()));
            return baseline.candidate().plan();
        }

        ConstructionSafetyGuard.Decision guardDecision =
                guard.begin(
                        baseline.candidate().placement(),
                        snapshot.nextType().orElseThrow());

        List<ObjectiveCandidate> objectiveCandidates =
                new ArrayList<>(ranked.size());
        for (int index = 0; index < ranked.size(); index++) {
            objectiveCandidates.add(objectiveCandidate(index + 1, ranked.get(index)));
        }
        objectiveCandidates.sort(OBJECTIVE_ORDER);

        ObjectiveCandidate selected = baseline;
        ConstructionSafetyGuard.Assessment selectedAssessment =
                guardDecision.assess(1, baseline.candidate().placement());
        int checked = 0;
        int rejected = 0;

        for (ObjectiveCandidate candidate : objectiveCandidates) {
            if (!betterShape(candidate, baseline)) {
                break;
            }
            checked++;
            ConstructionSafetyGuard.Assessment assessment =
                    guardDecision.assess(
                            candidate.survivalRank(),
                            candidate.candidate().placement());
            if (assessment.allowed()) {
                selected = candidate;
                selectedAssessment = assessment;
                break;
            }
            rejected++;
        }

        BoardOccupancyMetrics.Analysis selectedMetrics =
                BoardOccupancyMetrics.analyze(selected.candidate().placement().resultingBoard());
        observer.accept(new BuildShapeConstructionGuardObservation(
                target,
                profile(),
                ranked.size(),
                checked,
                rejected,
                true,
                guardDecision.baselineNextReachableOutcomes(),
                selectedAssessment.candidateNextReachableOutcomes(),
                selected.survivalRank(),
                baselineMetrics.headroom(),
                selectedMetrics.headroom(),
                baseline.progress(),
                selected.progress()));
        return selected.candidate().plan();
    }

    private ConstructionSafetyGuard.Profile profile() {
        return guardProfile(guard);
    }

    private static ConstructionSafetyGuard.Profile guardProfile(
            ConstructionSafetyGuard guard) {
        try {
            var field = ConstructionSafetyGuard.class.getDeclaredField("profile");
            field.setAccessible(true);
            return (ConstructionSafetyGuard.Profile) field.get(guard);
        }
        catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unable to read construction guard profile", exception);
        }
    }

    private ObjectiveCandidate objectiveCandidate(
            int survivalRank,
            ActionPlanCandidates.PlannedCandidate candidate) {
        return new ObjectiveCandidate(
                survivalRank,
                candidate,
                target.progress(candidate.placement().resultingBoard()));
    }

    private static boolean betterShape(
            ObjectiveCandidate candidate,
            ObjectiveCandidate baseline) {
        int comparison = Integer.compare(
                candidate.progress().netScore(),
                baseline.progress().netScore());
        if (comparison != 0) {
            return comparison > 0;
        }
        return candidate.progress().forbiddenOccupiedCells()
                < baseline.progress().forbiddenOccupiedCells();
    }

    private record ObjectiveCandidate(
            int survivalRank,
            ActionPlanCandidates.PlannedCandidate candidate,
            ShapeProgress progress) {

        ObjectiveCandidate {
            if (survivalRank <= 0) {
                throw new IllegalArgumentException("survivalRank must be positive");
            }
            Objects.requireNonNull(candidate, "candidate");
            Objects.requireNonNull(progress, "progress");
        }
    }
}
