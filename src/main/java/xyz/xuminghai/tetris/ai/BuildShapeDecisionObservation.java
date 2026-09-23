/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import java.util.Objects;

/**
 * Observational facts from one BUILD_SHAPE decision.
 *
 * <p>{@code reachableCandidateCount} reports the full action-native search result, while
 * {@code candidateCount} reports the survival-quality shortlist that BUILD_SHAPE actually
 * evaluates. Keeping both makes the search envelope observable without conflating reachability
 * with creative eligibility.</p>
 */
public record BuildShapeDecisionObservation(
        ShapeTarget target,
        int reachableCandidateCount,
        int candidateCount,
        int safetyEligibleCandidates,
        ObjectiveRiskController.RiskLevel riskLevel,
        ObjectiveRiskProfile riskProfile,
        int baselineHeadroom,
        int baselineHoles,
        boolean creativeSuppressedByDanger,
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
        if (reachableCandidateCount <= 0) {
            throw new IllegalArgumentException(
                    "reachableCandidateCount must be positive");
        }
        if (candidateCount <= 0
                || candidateCount > BuildShapeActionPlanningAgent.MAX_CURRENT_CANDIDATES
                || candidateCount > reachableCandidateCount) {
            throw new IllegalArgumentException(
                    "candidateCount must be within the BUILD_SHAPE quality shortlist");
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
        if (baselineProgress.requiredCells() != selectedProgress.requiredCells()
                || baselineProgress.forbiddenCells() != selectedProgress.forbiddenCells()) {
            throw new IllegalArgumentException("shape progress must use the same target");
        }
        if (creativeSuppressedByDanger
                && (riskLevel != ObjectiveRiskController.RiskLevel.DANGER
                        || selectedRank != 1
                        || !baselineProgress.equals(selectedProgress))) {
            throw new IllegalArgumentException(
                    "danger suppression must return the exact SURVIVAL shape progress");
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

    public int visualErrorDelta() {
        return selectedProgress.visualErrorCells() - baselineProgress.visualErrorCells();
    }

    public int netScoreDelta() {
        return selectedProgress.netScore() - baselineProgress.netScore();
    }

    public int matchedRequiredDelta() {
        return selectedProgress.matchedRequiredCells() - baselineProgress.matchedRequiredCells();
    }

    public int forbiddenOccupiedDelta() {
        return selectedProgress.forbiddenOccupiedCells()
                - baselineProgress.forbiddenOccupiedCells();
    }
}
