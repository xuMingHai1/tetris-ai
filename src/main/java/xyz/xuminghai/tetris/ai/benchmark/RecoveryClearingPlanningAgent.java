/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai.benchmark;

import xyz.xuminghai.tetris.ai.ActionPlanSimulator;
import xyz.xuminghai.tetris.ai.AiPlan;
import xyz.xuminghai.tetris.ai.AiPlanningAgent;
import xyz.xuminghai.tetris.ai.BuildShapeActionPlanningAgent;
import xyz.xuminghai.tetris.ai.BuildShapeDecisionObservation;
import xyz.xuminghai.tetris.ai.GameSnapshot;
import xyz.xuminghai.tetris.ai.PlacementCandidate;
import xyz.xuminghai.tetris.ai.RecoveryCandidateScanBenchmark;
import xyz.xuminghai.tetris.ai.RecoveryRobustnessBenchmark;
import xyz.xuminghai.tetris.ai.ShapeProgress;
import xyz.xuminghai.tetris.ai.ShapeTarget;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Benchmark-only recovery planner that preserves the existing SURVIVAL ranking.
 *
 * <p>Production BUILD_SHAPE always decides first. Only when BUILD_SHAPE has already selected
 * SURVIVAL rank 1 and that resulting board triggers the frozen recovery warning does this planner
 * inspect lower-ranked reachable actions. Starting at rank 2, candidates are probed in the
 * existing SURVIVAL order and the first candidate that clears the frozen warning is used for this
 * decision. If no candidate clears it, rank 1 remains unchanged.</p>
 *
 * <p>The next decision starts from normal BUILD_SHAPE again. This class adds no recovery score,
 * warning threshold, persistence rule or movement semantics.</p>
 */
final class RecoveryClearingPlanningAgent implements AiPlanningAgent {

    private static final Consumer<Observation> NOOP_OBSERVER = ignored -> {
    };

    private final ShapeTarget target;
    private final BuildShapeActionPlanningAgent buildShape;
    private final Consumer<Observation> observer;
    private final DecisionCapture decisionCapture = new DecisionCapture();

    RecoveryClearingPlanningAgent() {
        this(ShapeTarget.HEART, NOOP_OBSERVER);
    }

    RecoveryClearingPlanningAgent(
            ShapeTarget target,
            Consumer<Observation> observer) {
        this.target = Objects.requireNonNull(target, "target");
        this.observer = Objects.requireNonNull(observer, "observer");
        this.buildShape = new BuildShapeActionPlanningAgent(
                target,
                decisionCapture::record);
    }

    @Override
    public AiPlan plan(GameSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        decisionCapture.reset();

        AiPlan buildShapePlan = buildShape.plan(snapshot);
        BuildShapeDecisionObservation buildDecision = decisionCapture.current();
        if (buildDecision == null) {
            return buildShapePlan;
        }

        if (buildDecision.selectedRank() != 1 || snapshot.nextType().isEmpty()) {
            observer.accept(Observation.notEvaluated(buildDecision));
            return buildShapePlan;
        }

        PlacementCandidate buildPlacement =
                ActionPlanSimulator.requireTerminalPlacement(snapshot, buildShapePlan);

        long detectionStarted = System.nanoTime();
        RecoveryRobustnessBenchmark.Probe selectedProbe =
                RecoveryRobustnessBenchmark.probe(
                        buildPlacement.resultingBoard(),
                        snapshot.nextType().orElseThrow());
        long detectionProbeNanos = System.nanoTime() - detectionStarted;

        if (!RecoveryReserveValidationApplication.warning(selectedProbe)) {
            observer.accept(Observation.safeBaseline(
                    buildDecision,
                    detectionProbeNanos));
            return buildShapePlan;
        }

        long searchStarted = System.nanoTime();
        RecoveryCandidateScanBenchmark.MatchSearch search =
                RecoveryCandidateScanBenchmark.findFirstMatching(
                        snapshot,
                        2,
                        probe -> !RecoveryReserveValidationApplication.warning(probe));
        long searchNanos = System.nanoTime() - searchStarted;

        if (search.totalCandidates() != buildDecision.reachableCandidateCount()) {
            throw new IllegalStateException(
                    "recovery search candidate count diverged from BUILD_SHAPE reachability");
        }

        RecoveryCandidateScanBenchmark.CandidateAnalysis clearing =
                search.match().orElse(null);
        AiPlan appliedPlan = clearing == null
                ? buildShapePlan
                : clearing.plan();
        ShapeProgress appliedProgress = clearing == null
                ? buildDecision.selectedProgress()
                : target.progress(clearing.placement().resultingBoard());
        int appliedRank = clearing == null
                ? 1
                : clearing.survivalRank();

        observer.accept(Observation.warned(
                buildDecision,
                RecoveryReserveValidationApplication.warningReason(selectedProbe),
                clearing != null,
                appliedRank,
                search.candidatesProbed(),
                appliedProgress,
                detectionProbeNanos,
                searchNanos));
        return appliedPlan;
    }

    private static final class DecisionCapture {
        private BuildShapeDecisionObservation current;

        void reset() {
            current = null;
        }

        void record(BuildShapeDecisionObservation decision) {
            current = Objects.requireNonNull(decision, "decision");
        }

        BuildShapeDecisionObservation current() {
            return current;
        }
    }

    record Observation(
            boolean recoveryEvaluated,
            boolean warning,
            String warningReason,
            int buildShapeSelectedRank,
            boolean buildShapeObjectiveApplied,
            boolean planReplaced,
            int appliedSurvivalRank,
            int candidatesProbed,
            int reachableCandidateCount,
            ShapeProgress buildShapeProgress,
            ShapeProgress appliedProgress,
            long detectionProbeNanos,
            long searchNanos) {

        Observation {
            Objects.requireNonNull(warningReason, "warningReason");
            Objects.requireNonNull(buildShapeProgress, "buildShapeProgress");
            Objects.requireNonNull(appliedProgress, "appliedProgress");
            if (buildShapeSelectedRank <= 0
                    || appliedSurvivalRank <= 0
                    || reachableCandidateCount <= 0
                    || buildShapeSelectedRank > reachableCandidateCount
                    || appliedSurvivalRank > reachableCandidateCount
                    || candidatesProbed < 0
                    || candidatesProbed > reachableCandidateCount
                    || detectionProbeNanos < 0
                    || searchNanos < 0) {
                throw new IllegalArgumentException(
                        "invalid recovery clearing observation");
            }
            if (buildShapeObjectiveApplied != (buildShapeSelectedRank > 1)) {
                throw new IllegalArgumentException(
                        "objective flag must match BUILD_SHAPE selected rank");
            }

            if (!recoveryEvaluated) {
                if (warning
                        || !"none".equals(warningReason)
                        || planReplaced
                        || appliedSurvivalRank != buildShapeSelectedRank
                        || candidatesProbed != 0
                        || detectionProbeNanos != 0L
                        || searchNanos != 0L
                        || !buildShapeProgress.equals(appliedProgress)) {
                    throw new IllegalArgumentException(
                            "non-evaluated decision must preserve BUILD_SHAPE");
                }
            }
            else if (buildShapeSelectedRank != 1) {
                throw new IllegalArgumentException(
                        "recovery evaluation is only valid for SURVIVAL rank 1");
            }
            else if (!warning) {
                if (!"none".equals(warningReason)
                        || planReplaced
                        || appliedSurvivalRank != 1
                        || candidatesProbed != 0
                        || searchNanos != 0L
                        || !buildShapeProgress.equals(appliedProgress)) {
                    throw new IllegalArgumentException(
                            "safe evaluated decision must preserve SURVIVAL rank 1");
                }
            }
            else {
                if ("none".equals(warningReason)) {
                    throw new IllegalArgumentException(
                            "warned decision requires a warning reason");
                }
                if (planReplaced) {
                    if (appliedSurvivalRank <= 1
                            || candidatesProbed != appliedSurvivalRank - 1) {
                        throw new IllegalArgumentException(
                                "replacement must be the first probed clearing rank");
                    }
                }
                else if (appliedSurvivalRank != 1
                        || candidatesProbed != reachableCandidateCount - 1
                        || !buildShapeProgress.equals(appliedProgress)) {
                    throw new IllegalArgumentException(
                            "unresolved warning must exhaust alternatives and preserve rank 1");
                }
            }
        }

        static Observation notEvaluated(
                BuildShapeDecisionObservation decision) {
            return new Observation(
                    false,
                    false,
                    "none",
                    decision.selectedRank(),
                    decision.objectiveApplied(),
                    false,
                    decision.selectedRank(),
                    0,
                    decision.reachableCandidateCount(),
                    decision.selectedProgress(),
                    decision.selectedProgress(),
                    0L,
                    0L);
        }

        static Observation safeBaseline(
                BuildShapeDecisionObservation decision,
                long detectionProbeNanos) {
            return new Observation(
                    true,
                    false,
                    "none",
                    decision.selectedRank(),
                    decision.objectiveApplied(),
                    false,
                    1,
                    0,
                    decision.reachableCandidateCount(),
                    decision.selectedProgress(),
                    decision.selectedProgress(),
                    detectionProbeNanos,
                    0L);
        }

        static Observation warned(
                BuildShapeDecisionObservation decision,
                String warningReason,
                boolean planReplaced,
                int appliedSurvivalRank,
                int candidatesProbed,
                ShapeProgress appliedProgress,
                long detectionProbeNanos,
                long searchNanos) {
            return new Observation(
                    true,
                    true,
                    warningReason,
                    decision.selectedRank(),
                    decision.objectiveApplied(),
                    planReplaced,
                    appliedSurvivalRank,
                    candidatesProbed,
                    decision.reachableCandidateCount(),
                    decision.selectedProgress(),
                    appliedProgress,
                    detectionProbeNanos,
                    searchNanos);
        }

        int appliedVisualErrorDelta() {
            return appliedProgress.visualErrorCells()
                    - buildShapeProgress.visualErrorCells();
        }

        long recoveryOverheadNanos() {
            return detectionProbeNanos + searchNanos;
        }
    }
}
