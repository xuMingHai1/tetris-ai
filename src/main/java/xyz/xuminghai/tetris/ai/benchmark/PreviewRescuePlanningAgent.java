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
import xyz.xuminghai.tetris.ai.ShapeTarget;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Benchmark-only rescue when production rank 1 makes the already-known preview unplayable.
 *
 * <p>The action is replaced only by the first SURVIVAL-ranked alternative that restores preview
 * playability; the strict arm also clears the existing frozen warning. Every next decision starts from production
 * BUILD_SHAPE again. The preview eligibility check first tries a straight hard drop and searches
 * all action paths only when that landing is hidden. This planner is never installed in the
 * desktop runtime.</p>
 */
final class PreviewRescuePlanningAgent implements AiPlanningAgent {

    private final BuildShapeActionPlanningAgent production;
    private final Consumer<Observation> observer;
    private final Mode mode;
    private final DecisionCapture decisionCapture = new DecisionCapture();

    PreviewRescuePlanningAgent(Consumer<Observation> observer) {
        this(observer, Mode.STRICT);
    }

    PreviewRescuePlanningAgent(Consumer<Observation> observer, Mode mode) {
        this.observer = Objects.requireNonNull(observer, "observer");
        this.mode = Objects.requireNonNull(mode, "mode");
        production = new BuildShapeActionPlanningAgent(
                ShapeTarget.HEART, decisionCapture::record);
    }

    @Override
    public AiPlan plan(GameSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        decisionCapture.reset();
        AiPlan baseline = production.plan(snapshot);
        BuildShapeDecisionObservation decision = decisionCapture.current();
        if (decision == null) {
            throw new IllegalStateException("BUILD_SHAPE did not emit a decision");
        }
        if (decision.selectedRank() != 1 || snapshot.nextType().isEmpty()) {
            observer.accept(new Observation(false, false, false, 0, 0, 0L, 0L));
            return baseline;
        }

        PlacementCandidate placement =
                ActionPlanSimulator.requireTerminalPlacement(snapshot, baseline);
        long detectStart = System.nanoTime();
        boolean previewRecoverable = RecoveryRobustnessBenchmark.previewRecoverable(
                placement.resultingBoard(), snapshot.nextType().orElseThrow());
        long detectionNanos = System.nanoTime() - detectStart;
        if (previewRecoverable) {
            observer.accept(new Observation(true, false, false, 0, 0, detectionNanos, 0L));
            return baseline;
        }

        long searchStart = System.nanoTime();
        AiPlan replacement;
        int totalCandidates;
        int candidatesProbed;
        int replacementRank;
        if (mode == Mode.STRICT) {
            RecoveryCandidateScanBenchmark.MatchSearch search =
                    RecoveryCandidateScanBenchmark.findFirstMatching(
                            snapshot, 2, PreviewRescuePlanningAgent::clearsWarning);
            totalCandidates = search.totalCandidates();
            candidatesProbed = search.candidatesProbed();
            RecoveryCandidateScanBenchmark.CandidateAnalysis match =
                    search.match().orElse(null);
            replacement = match == null ? null : match.plan();
            replacementRank = match == null ? 0 : match.survivalRank();
        } else {
            RecoveryCandidateScanBenchmark.PreviewSearch search =
                    RecoveryCandidateScanBenchmark.findFirstPreviewRecoverable(snapshot, 2);
            totalCandidates = search.totalCandidates();
            candidatesProbed = search.candidatesProbed();
            replacement = search.plan().orElse(null);
            replacementRank = replacement == null ? 0 : candidatesProbed + 1;
        }
        long searchNanos = System.nanoTime() - searchStart;
        if (totalCandidates != decision.reachableCandidateCount()) {
            throw new IllegalStateException("SURVIVAL candidate count diverged");
        }
        observer.accept(new Observation(true, true, replacement != null,
                replacementRank, candidatesProbed,
                detectionNanos, searchNanos));
        return replacement == null ? baseline : replacement;
    }

    static boolean clearsWarning(RecoveryRobustnessBenchmark.Probe probe) {
        return probe.previewRecoverable()
                && !RecoveryReserveValidationApplication.warning(probe);
    }

    enum Mode {
        STRICT,
        PREVIEW_ONLY
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
            boolean rank1Evaluated, boolean previewUnrecoverable, boolean replaced,
            int replacementRank, int candidatesProbed, long detectionNanos, long searchNanos) {
        Observation {
            if (replacementRank < 0 || candidatesProbed < 0
                    || detectionNanos < 0 || searchNanos < 0
                    || (replaced && (!previewUnrecoverable
                            || replacementRank < 2
                            || candidatesProbed != replacementRank - 1))
                    || (!previewUnrecoverable && (replaced
                            || replacementRank != 0 || candidatesProbed != 0
                            || searchNanos != 0))
                    || (!rank1Evaluated && (previewUnrecoverable || detectionNanos != 0))) {
                throw new IllegalArgumentException("invalid preview rescue observation");
            }
        }

        long overheadNanos() {
            return detectionNanos + searchNanos;
        }
    }
}
