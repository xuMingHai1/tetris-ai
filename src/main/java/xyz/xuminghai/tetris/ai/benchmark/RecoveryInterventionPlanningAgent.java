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
import xyz.xuminghai.tetris.ai.DeterministicActionPlanningAgent;
import xyz.xuminghai.tetris.ai.GameSnapshot;
import xyz.xuminghai.tetris.ai.PlacementCandidate;
import xyz.xuminghai.tetris.ai.RecoveryRobustnessBenchmark;
import xyz.xuminghai.tetris.ai.ShapeProgress;
import xyz.xuminghai.tetris.ai.ShapeTarget;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Benchmark-only reversible intervention around the production BUILD_SHAPE planner.
 *
 * <p>The underlying BUILD_SHAPE decision is always evaluated first. Its selected resulting board is
 * tested with the frozen recovery-reserve warning contract. A warning affects only the current
 * decision: the returned plan becomes the existing deterministic SURVIVAL top-1 plan. The next
 * decision starts from normal BUILD_SHAPE again, so this class does not implement sticky mode,
 * persistence thresholds or a new runtime policy.</p>
 */
final class RecoveryInterventionPlanningAgent implements AiPlanningAgent {

    private static final Consumer<Observation> NOOP_OBSERVER = ignored -> {
    };

    private final ShapeTarget target;
    private final BuildShapeActionPlanningAgent buildShape;
    private final DeterministicActionPlanningAgent survival;
    private final Consumer<Observation> observer;
    private final DecisionCapture decisionCapture = new DecisionCapture();

    RecoveryInterventionPlanningAgent() {
        this(ShapeTarget.HEART, NOOP_OBSERVER);
    }

    RecoveryInterventionPlanningAgent(
            ShapeTarget target,
            Consumer<Observation> observer) {
        this.target = Objects.requireNonNull(target, "target");
        this.observer = Objects.requireNonNull(observer, "observer");
        this.buildShape = new BuildShapeActionPlanningAgent(
                target,
                decisionCapture::record);
        this.survival = new DeterministicActionPlanningAgent();
    }

    @Override
    public AiPlan plan(GameSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        decisionCapture.reset();

        AiPlan buildShapePlan = buildShape.plan(snapshot);
        BuildShapeDecisionObservation buildDecision = decisionCapture.current();
        if (buildDecision == null || snapshot.nextType().isEmpty()) {
            return buildShapePlan;
        }

        PlacementCandidate buildPlacement =
                ActionPlanSimulator.requireTerminalPlacement(snapshot, buildShapePlan);

        long probeStarted = System.nanoTime();
        RecoveryRobustnessBenchmark.Probe buildProbe =
                RecoveryRobustnessBenchmark.probe(
                        buildPlacement.resultingBoard(),
                        snapshot.nextType().orElseThrow());
        long probeNanos = System.nanoTime() - probeStarted;

        boolean warning = RecoveryReserveValidationApplication.warning(buildProbe);
        if (!warning) {
            observer.accept(new Observation(
                    false,
                    "none",
                    buildDecision.selectedRank(),
                    buildDecision.objectiveApplied(),
                    false,
                    false,
                    buildDecision.selectedProgress(),
                    buildDecision.selectedProgress(),
                    probeNanos,
                    0L));
            return buildShapePlan;
        }

        AiPlan survivalPlan = survival.plan(snapshot);
        PlacementCandidate survivalPlacement =
                ActionPlanSimulator.requireTerminalPlacement(snapshot, survivalPlan);
        ShapeProgress appliedProgress = target.progress(survivalPlacement.resultingBoard());

        long verificationStarted = System.nanoTime();
        RecoveryRobustnessBenchmark.Probe survivalProbe =
                RecoveryRobustnessBenchmark.probe(
                        survivalPlacement.resultingBoard(),
                        snapshot.nextType().orElseThrow());
        long verificationProbeNanos = System.nanoTime() - verificationStarted;

        boolean warningAfterSurvival =
                RecoveryReserveValidationApplication.warning(survivalProbe);
        boolean planReplaced = !survivalPlan.equals(buildShapePlan);

        observer.accept(new Observation(
                true,
                RecoveryReserveValidationApplication.warningReason(buildProbe),
                buildDecision.selectedRank(),
                buildDecision.objectiveApplied(),
                planReplaced,
                warningAfterSurvival,
                buildDecision.selectedProgress(),
                appliedProgress,
                probeNanos,
                verificationProbeNanos));
        return survivalPlan;
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
            boolean warning,
            String warningReason,
            int buildShapeSelectedRank,
            boolean buildShapeObjectiveApplied,
            boolean planReplaced,
            boolean warningAfterSurvival,
            ShapeProgress buildShapeProgress,
            ShapeProgress appliedProgress,
            long probeNanos,
            long verificationProbeNanos) {

        Observation {
            Objects.requireNonNull(warningReason, "warningReason");
            Objects.requireNonNull(buildShapeProgress, "buildShapeProgress");
            Objects.requireNonNull(appliedProgress, "appliedProgress");
            if (buildShapeSelectedRank <= 0) {
                throw new IllegalArgumentException(
                        "buildShapeSelectedRank must be positive");
            }
            if (probeNanos < 0 || verificationProbeNanos < 0) {
                throw new IllegalArgumentException(
                        "probe timings must not be negative");
            }
            if (!warning) {
                if (!"none".equals(warningReason)
                        || planReplaced
                        || warningAfterSurvival
                        || verificationProbeNanos != 0L
                        || !buildShapeProgress.equals(appliedProgress)) {
                    throw new IllegalArgumentException(
                            "non-warning observation must preserve BUILD_SHAPE selection");
                }
            }
        }

        boolean warningClearedBySurvival() {
            return warning && !warningAfterSurvival;
        }

        int appliedVisualErrorDelta() {
            return appliedProgress.visualErrorCells()
                    - buildShapeProgress.visualErrorCells();
        }
    }
}
