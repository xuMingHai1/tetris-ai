/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Opt-in BUILD_SHAPE planner that avoids a known, immediately unplayable preview.
 *
 * <p>Normal decisions come from production BUILD_SHAPE. Only when it chose SURVIVAL rank 1 and
 * that action leaves the known preview without a visible action-native landing does this planner
 * try the first SURVIVAL-ranked alternative that restores one. Every subsequent decision starts
 * with ordinary BUILD_SHAPE again. The desktop executor retains its existing snapshot and gravity
 * deadline checks. The observation capture is scoped to the calling thread because the desktop
 * executor may still have an older decision running when it submits a newer one.</p>
 */
public final class PreviewRescueActionPlanningAgent implements AiPlanningAgent {

    private final BuildShapeActionPlanningAgent production;
    private final Consumer<Observation> observer;
    private final ThreadLocal<BuildShapeDecisionObservation> decision = new ThreadLocal<>();

    public PreviewRescueActionPlanningAgent() {
        this(ignored -> {
        });
    }

    public PreviewRescueActionPlanningAgent(Consumer<Observation> observer) {
        this.observer = Objects.requireNonNull(observer, "observer");
        production = new BuildShapeActionPlanningAgent(ShapeTarget.HEART, this::record);
    }

    @Override
    public AiPlan plan(GameSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        decision.remove();
        AiPlan baseline;
        BuildShapeDecisionObservation selected;
        try {
            baseline = production.plan(snapshot);
            selected = decision.get();
        } finally {
            decision.remove();
        }
        if (selected == null) {
            throw new IllegalStateException("BUILD_SHAPE did not emit a decision");
        }
        if (selected.selectedRank() != 1 || snapshot.nextType().isEmpty()) {
            observer.accept(new Observation(false, false, false, 0, 0, 0L, 0L));
            return baseline;
        }

        PlacementCandidate placement = ActionPlanSimulator.requireTerminalPlacement(snapshot, baseline);
        long detectStart = System.nanoTime();
        boolean previewRecoverable = ActionPlanSimulator.hasReachableTerminalPlacement(
                BoardSimulator.snapshotForSpawnedPiece(
                        placement.resultingBoard(), snapshot.nextType().orElseThrow()));
        long detectionNanos = System.nanoTime() - detectStart;
        if (previewRecoverable) {
            observer.accept(new Observation(true, false, false, 0, 0, detectionNanos, 0L));
            return baseline;
        }

        long searchStart = System.nanoTime();
        ActionPlanCandidates.PreviewSearch search =
                ActionPlanCandidates.firstPreviewRecoverable(snapshot, 2);
        long searchNanos = System.nanoTime() - searchStart;
        if (search.totalCandidates() != selected.reachableCandidateCount()) {
            throw new IllegalStateException("SURVIVAL candidate count diverged");
        }
        AiPlan replacement = search.plan().orElse(null);
        observer.accept(new Observation(true, true, replacement != null,
                replacement == null ? 0 : search.candidatesProbed() + 1,
                search.candidatesProbed(), detectionNanos, searchNanos));
        return replacement == null ? baseline : replacement;
    }

    private void record(BuildShapeDecisionObservation observation) {
        decision.set(Objects.requireNonNull(observation, "observation"));
    }

    public record Observation(
            boolean rank1Evaluated, boolean previewUnrecoverable, boolean replaced,
            int replacementRank, int candidatesProbed, long detectionNanos, long searchNanos) {
        public Observation {
            if (replacementRank < 0 || candidatesProbed < 0
                    || detectionNanos < 0 || searchNanos < 0
                    || (replaced && (!previewUnrecoverable
                            || replacementRank < 2
                            || candidatesProbed != replacementRank - 1))
                    || (!previewUnrecoverable && (replaced || replacementRank != 0
                            || candidatesProbed != 0 || searchNanos != 0))
                    || (!rank1Evaluated && (previewUnrecoverable || detectionNanos != 0))) {
                throw new IllegalArgumentException("invalid preview rescue observation");
            }
        }
    }
}
