/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import java.util.Objects;

/**
 * Observational facts from one deterministic tuck-hunter decision.
 *
 * <p>The selected rank is one-based inside the current top-five survival shortlist. Risk level and
 * active profile are derived from the SURVIVAL top-1 resulting board before objective candidates are
 * assessed. Future action-only facts describe the known preview piece after the selected current
 * placement.</p>
 */
public record TuckHunterDecisionObservation(
        int candidateCount,
        int safetyEligibleCandidates,
        ObjectiveRiskController.RiskLevel riskLevel,
        ObjectiveRiskProfile riskProfile,
        int baselineHeadroom,
        int baselineHoles,
        int selectedRank,
        boolean selectedCurrentActionOnly,
        int setupCandidates,
        int selectedFutureActionOnlyCandidates,
        int selectedFutureTopFiveActionOnlyCandidates,
        int selectedBestFutureActionOnlyRank,
        int selectedClearedLinesDelta,
        int selectedAggregateHeightDelta,
        int selectedHolesDelta,
        int selectedBumpinessDelta) {

    public TuckHunterDecisionObservation {
        Objects.requireNonNull(riskLevel, "riskLevel");
        Objects.requireNonNull(riskProfile, "riskProfile");
        if (candidateCount <= 0
                || candidateCount > TuckHunterActionPlanningAgent.MAX_CURRENT_CANDIDATES) {
            throw new IllegalArgumentException(
                    "candidateCount must be within the tuck-hunter safety shortlist");
        }
        if (safetyEligibleCandidates <= 0 || safetyEligibleCandidates > candidateCount) {
            throw new IllegalArgumentException(
                    "safetyEligibleCandidates must be within candidateCount");
        }
        if (baselineHeadroom < 0 || baselineHoles < 0) {
            throw new IllegalArgumentException(
                    "baseline risk metrics must not be negative");
        }
        if (selectedRank <= 0 || selectedRank > candidateCount) {
            throw new IllegalArgumentException("selectedRank must be within the current shortlist");
        }
        if (setupCandidates < 0 || setupCandidates > safetyEligibleCandidates) {
            throw new IllegalArgumentException(
                    "setupCandidates must be within the safety-eligible candidate count");
        }
        if (selectedFutureActionOnlyCandidates < 0) {
            throw new IllegalArgumentException(
                    "selectedFutureActionOnlyCandidates must not be negative");
        }
        if (selectedFutureTopFiveActionOnlyCandidates < 0
                || selectedFutureTopFiveActionOnlyCandidates > selectedFutureActionOnlyCandidates) {
            throw new IllegalArgumentException(
                    "selectedFutureTopFiveActionOnlyCandidates must fit within future action-only candidates");
        }
        if (selectedBestFutureActionOnlyRank < 0) {
            throw new IllegalArgumentException("selectedBestFutureActionOnlyRank must not be negative");
        }
        if ((selectedFutureActionOnlyCandidates == 0) != (selectedBestFutureActionOnlyRank == 0)) {
            throw new IllegalArgumentException(
                    "selectedBestFutureActionOnlyRank must be zero exactly when no future action-only candidate exists");
        }
        if (selectedCurrentActionOnly
                && (setupCandidates != 0
                        || selectedFutureActionOnlyCandidates != 0
                        || selectedFutureTopFiveActionOnlyCandidates != 0
                        || selectedBestFutureActionOnlyRank != 0)) {
            throw new IllegalArgumentException(
                    "executing a current action-only plan cannot also report future setup facts");
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

    public boolean createsTopFiveOpportunity() {
        return selectedFutureTopFiveActionOnlyCandidates > 0;
    }
}
