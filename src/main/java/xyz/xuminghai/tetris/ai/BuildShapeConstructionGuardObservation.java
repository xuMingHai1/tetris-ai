/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import java.util.Objects;

/**
 * Observational facts from the benchmark-only construction-guard BUILD_SHAPE experiment.
 */
public record BuildShapeConstructionGuardObservation(
        ShapeTarget target,
        ConstructionSafetyGuard.Profile profile,
        int reachableCandidateCount,
        int guardCheckedCandidates,
        int guardRejectedCandidates,
        boolean previewAvailable,
        int baselineNextReachableOutcomes,
        int selectedNextReachableOutcomes,
        int selectedRank,
        int baselineHeadroom,
        int selectedHeadroom,
        ShapeProgress baselineProgress,
        ShapeProgress selectedProgress) {

    public BuildShapeConstructionGuardObservation {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(baselineProgress, "baselineProgress");
        Objects.requireNonNull(selectedProgress, "selectedProgress");
        if (reachableCandidateCount <= 0
                || guardCheckedCandidates < 0
                || guardRejectedCandidates < 0
                || guardRejectedCandidates > guardCheckedCandidates
                || selectedRank <= 0
                || selectedRank > reachableCandidateCount
                || baselineHeadroom < 0
                || selectedHeadroom < 0) {
            throw new IllegalArgumentException(
                    "invalid construction guard decision counts");
        }
        if (previewAvailable) {
            if (baselineNextReachableOutcomes < 0 || selectedNextReachableOutcomes < 0) {
                throw new IllegalArgumentException(
                        "preview reachability must be available when preview is present");
            }
        }
        else if (baselineNextReachableOutcomes != -1
                || selectedNextReachableOutcomes != -1
                || selectedRank != 1
                || guardCheckedCandidates != 0
                || guardRejectedCandidates != 0) {
            throw new IllegalArgumentException(
                    "missing preview must fall back to SURVIVAL top-1 without guard evaluation");
        }
        if (baselineProgress.requiredCells() != selectedProgress.requiredCells()
                || baselineProgress.forbiddenCells() != selectedProgress.forbiddenCells()) {
            throw new IllegalArgumentException("shape progress must use the same target");
        }
    }

    public boolean objectiveApplied() {
        return selectedRank > 1;
    }

    public int netScoreDelta() {
        return selectedProgress.netScore() - baselineProgress.netScore();
    }

    public int visualErrorDelta() {
        return selectedProgress.visualErrorCells() - baselineProgress.visualErrorCells();
    }
}
