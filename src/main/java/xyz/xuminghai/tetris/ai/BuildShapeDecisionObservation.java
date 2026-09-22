/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import java.util.Objects;

/**
 * Observational facts from one BUILD_SHAPE decision.
 */
public record BuildShapeDecisionObservation(
        ShapeTarget target,
        int candidateCount,
        int safetyEligibleCandidates,
        ObjectiveRiskController.RiskLevel riskLevel,
        ObjectiveRiskProfile riskProfile,
        int baselineHeadroom,
        int baselineHoles,
        int selectedRank,
        ShapeProgress baselineProgress,
        ShapeProgress selectedProgress,
        int selectedClearedLinesDelta,
        int selectedAggregateHeightDelta,
        int selectedHolesDelta,
        int selectedBumpinessDelta) {

    public BuildShapeDecisionObservation {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(riskLevel, "riskLevel");
        Objects.requireNonNull(riskProfile, "riskProfile");
        Objects.requireNonNull(baselineProgress, "baselineProgress");
        Objects.requireNonNull(selectedProgress, "selectedProgress");
        if (candidateCount <= 0
                || candidateCount > BuildShapeActionPlanningAgent.MAX_CURRENT_CANDIDATES) {
            throw new IllegalArgumentException(
                    "candidateCount must be within the BUILD_SHAPE search envelope");
        }
        if (safetyEligibleCandidates <= 0 || safetyEligibleCandidates > candidateCount) {
            throw new IllegalArgumentException(
                    "safetyEligibleCandidates must be within candidateCount");
        }
        if (baselineHeadroom < 0 || baselineHoles < 0) {
            throw new IllegalArgumentException("baseline risk metrics must not be negative");
        }
        if (selectedRank <= 0 || selectedRank > candidateCount) {
            throw new IllegalArgumentException("selectedRank must be within candidateCount");
        }
        if (baselineProgress.targetCells() != selectedProgress.targetCells()) {
            throw new IllegalArgumentException("shape progress must use the same target");
        }
        if (selectedRank == 1
                && (selectedClearedLinesDelta != 0
                        || selectedAggregateHeightDelta != 0
                        || selectedHolesDelta != 0
                        || selectedBumpinessDelta != 0)) {
            throw new IllegalArgumentException(
                    "survival top choice must have zero safety deltas");
        }
    }

    public int safetyRejectedCandidates() {
        return candidateCount - safetyEligibleCandidates;
    }

    public boolean objectiveApplied() {
        return selectedRank > 1;
    }

    public int netScoreDelta() {
        return selectedProgress.netScore() - baselineProgress.netScore();
    }

    public int matchedCellsDelta() {
        return selectedProgress.matchedCells() - baselineProgress.matchedCells();
    }

    public int intrusionCellsDelta() {
        return selectedProgress.intrusionCells() - baselineProgress.intrusionCells();
    }
}
