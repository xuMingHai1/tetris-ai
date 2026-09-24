/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import xyz.xuminghai.tetris.core.TetrominoType;

import java.util.Objects;

/**
 * Benchmark-oriented BUILD_SHAPE safety guard based on the next piece's real action-native
 * continuation capacity.
 *
 * <p>The guard deliberately does not use aggregate-height, hole or bumpiness deltas. Previous
 * construction viability calibration showed that known clean HEART states can deviate strongly
 * from SURVIVAL top-1 on those metrics while still retaining substantial future play. Instead,
 * this guard asks whether the already-known preview piece still has reachable terminal outcomes
 * after a creative candidate.</p>
 *
 * <p>The profiles are calibration points, not production defaults. The SURVIVAL top-1 placement
 * is always a fallback even when its preview continuation count is zero.</p>
 */
public final class ConstructionSafetyGuard {

    private final Profile profile;

    public ConstructionSafetyGuard(Profile profile) {
        this.profile = Objects.requireNonNull(profile, "profile");
    }

    public Profile profile() {
        return profile;
    }

    public Decision begin(
            PlacementCandidate survivalBaseline,
            TetrominoType nextType) {
        Objects.requireNonNull(survivalBaseline, "survivalBaseline");
        Objects.requireNonNull(nextType, "nextType");
        int baselineNextReachable = nextReachableOutcomes(
                survivalBaseline.resultingBoard(),
                nextType);
        return new Decision(profile, nextType, baselineNextReachable);
    }

    private static int nextReachableOutcomes(
            boolean[][] board,
            TetrominoType nextType) {
        return ActionPlanCandidates.ranked(
                        BoardSimulator.snapshotForSpawnedPiece(board, nextType))
                .size();
    }

    /**
     * Relative preview-continuation retention used only for benchmark calibration.
     */
    public enum Profile {
        /**
         * A creative candidate must retain at least as many next-piece outcomes as SURVIVAL top-1.
         */
        PRESERVE_BASELINE("preserve-baseline", 1, 1, false),

        /**
         * A creative candidate must retain at least half of SURVIVAL top-1's next-piece outcomes.
         */
        RETAIN_HALF("retain-half", 1, 2, false),

        /**
         * A creative candidate only needs one real next-piece terminal outcome.
         */
        ANY_CONTINUATION("any-continuation", 0, 1, true);

        private final String configValue;
        private final int numerator;
        private final int denominator;
        private final boolean anyContinuation;

        Profile(
                String configValue,
                int numerator,
                int denominator,
                boolean anyContinuation) {
            this.configValue = configValue;
            this.numerator = numerator;
            this.denominator = denominator;
            this.anyContinuation = anyContinuation;
        }

        public String configValue() {
            return configValue;
        }

        boolean allows(
                int baselineNextReachable,
                int candidateNextReachable) {
            if (candidateNextReachable <= 0) {
                return false;
            }
            if (anyContinuation || baselineNextReachable <= 0) {
                return true;
            }
            return (long) candidateNextReachable * denominator
                    >= (long) baselineNextReachable * numerator;
        }
    }

    /**
     * Cached guard facts shared by all candidates for one decision.
     */
    public record Decision(
            Profile profile,
            TetrominoType nextType,
            int baselineNextReachableOutcomes) {

        public Decision {
            Objects.requireNonNull(profile, "profile");
            Objects.requireNonNull(nextType, "nextType");
            if (baselineNextReachableOutcomes < 0) {
                throw new IllegalArgumentException(
                        "baselineNextReachableOutcomes must not be negative");
            }
        }

        public Assessment assess(
                int survivalRank,
                PlacementCandidate candidate) {
            Objects.requireNonNull(candidate, "candidate");
            if (survivalRank <= 0) {
                throw new IllegalArgumentException("survivalRank must be positive");
            }

            if (survivalRank == 1) {
                return new Assessment(
                        true,
                        baselineNextReachableOutcomes,
                        baselineNextReachableOutcomes);
            }

            int candidateNextReachable = nextReachableOutcomes(
                    candidate.resultingBoard(),
                    nextType);
            return new Assessment(
                    profile.allows(
                            baselineNextReachableOutcomes,
                            candidateNextReachable),
                    baselineNextReachableOutcomes,
                    candidateNextReachable);
        }
    }

    /**
     * One candidate's next-piece continuation facts.
     */
    public record Assessment(
            boolean allowed,
            int baselineNextReachableOutcomes,
            int candidateNextReachableOutcomes) {

        public Assessment {
            if (baselineNextReachableOutcomes < 0
                    || candidateNextReachableOutcomes < 0) {
                throw new IllegalArgumentException(
                        "reachable outcome counts must not be negative");
            }
        }
    }
}
