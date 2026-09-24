/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import xyz.xuminghai.tetris.core.TetrominoType;

import java.util.List;
import java.util.Objects;

/**
 * Diagnostic probe for construction-state recovery robustness.
 *
 * <p>The probe first applies the known preview piece using production SURVIVAL rank 1. From that
 * recovered board it then evaluates every tetromino type as the following unknown piece, again
 * using production action-native reachability. This separates immediate preview branching from
 * the stronger question of whether one survival recovery leaves the board broadly playable across
 * the complete tetromino alphabet.</p>
 *
 * <p>No field in this class is a runtime threshold. The probe exists to compare known clean
 * construction states with states observed shortly before benchmark game-over.</p>
 */
public final class RecoveryRobustnessBenchmark {

    private RecoveryRobustnessBenchmark() {
    }

    /**
     * Replays one already-known action plan from the production spawned-piece boundary and probes
     * the resulting board without exposing package-local board-construction helpers to the
     * benchmark application package.
     */
    public static ReplayedPlacement replayAndProbe(
            boolean[][] board,
            TetrominoType currentType,
            AiPlan plan,
            TetrominoType previewType) {
        Objects.requireNonNull(board, "board");
        Objects.requireNonNull(currentType, "currentType");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(previewType, "previewType");

        GameSnapshot snapshot =
                BoardSimulator.snapshotForSpawnedPiece(board, currentType);
        PlacementCandidate placement =
                ActionPlanSimulator.requireTerminalPlacement(snapshot, plan);
        return new ReplayedPlacement(
                placement,
                probe(placement.resultingBoard(), previewType));
    }

    public static Probe probe(
            boolean[][] board,
            TetrominoType previewType) {
        Objects.requireNonNull(board, "board");
        Objects.requireNonNull(previewType, "previewType");

        List<ActionPlanCandidates.PlannedCandidate> previewCandidates =
                ActionPlanCandidates.ranked(
                        BoardSimulator.snapshotForSpawnedPiece(board, previewType));
        if (previewCandidates.isEmpty()) {
            return Probe.unrecoverable(previewType);
        }

        PlacementCandidate recovery = previewCandidates.getFirst().placement();
        boolean[][] recoveredBoard = recovery.resultingBoard();
        BoardOccupancyMetrics.Analysis recoveryMetrics =
                BoardOccupancyMetrics.analyze(recoveredBoard);

        int unplayableTypes = 0;
        int minReachable = Integer.MAX_VALUE;
        int maxReachable = 0;
        long reachableSum = 0L;
        int minPostUnknownHeadroom = Integer.MAX_VALUE;
        int maxPostUnknownHoles = 0;
        int maxPostUnknownAggregateHeight = 0;

        TetrominoType[] unknownTypes = TetrominoType.values();
        for (TetrominoType unknownType : unknownTypes) {
            List<ActionPlanCandidates.PlannedCandidate> candidates =
                    ActionPlanCandidates.ranked(
                            BoardSimulator.snapshotForSpawnedPiece(
                                    recoveredBoard,
                                    unknownType));
            int reachable = candidates.size();
            reachableSum += reachable;
            minReachable = Math.min(minReachable, reachable);
            maxReachable = Math.max(maxReachable, reachable);

            if (candidates.isEmpty()) {
                unplayableTypes++;
                continue;
            }

            PlacementCandidate bestRecovery = candidates.getFirst().placement();
            BoardOccupancyMetrics.Analysis postUnknown =
                    BoardOccupancyMetrics.analyze(bestRecovery.resultingBoard());
            minPostUnknownHeadroom = Math.min(
                    minPostUnknownHeadroom,
                    postUnknown.headroom());
            maxPostUnknownHoles = Math.max(
                    maxPostUnknownHoles,
                    bestRecovery.holes());
            maxPostUnknownAggregateHeight = Math.max(
                    maxPostUnknownAggregateHeight,
                    bestRecovery.aggregateHeight());
        }

        return new Probe(
                previewType,
                previewCandidates.size(),
                true,
                recoveryMetrics.headroom(),
                recovery.aggregateHeight(),
                recovery.holes(),
                recovery.bumpiness(),
                unknownTypes.length,
                unplayableTypes,
                minReachable,
                (double) reachableSum / unknownTypes.length,
                maxReachable,
                minPostUnknownHeadroom == Integer.MAX_VALUE
                        ? -1
                        : minPostUnknownHeadroom,
                unplayableTypes == unknownTypes.length ? -1 : maxPostUnknownHoles,
                unplayableTypes == unknownTypes.length
                        ? -1
                        : maxPostUnknownAggregateHeight);
    }

    public record ReplayedPlacement(
            PlacementCandidate placement,
            Probe probe) {

        public ReplayedPlacement {
            Objects.requireNonNull(placement, "placement");
            Objects.requireNonNull(probe, "probe");
        }
    }

    /**
     * Recovery facts for one board after the current creative placement.
     *
     * <p>Post-unknown board metrics are {@code -1} only when no unknown tetromino type is playable.
     * Reachable-outcome aggregates always include zero-outcome types.</p>
     */
    public record Probe(
            TetrominoType previewType,
            int previewReachableOutcomes,
            boolean previewRecoverable,
            int recoveryHeadroom,
            int recoveryAggregateHeight,
            int recoveryHoles,
            int recoveryBumpiness,
            int unknownPieceTypes,
            int unplayableUnknownTypes,
            int minUnknownReachableOutcomes,
            double averageUnknownReachableOutcomes,
            int maxUnknownReachableOutcomes,
            int minPostUnknownHeadroom,
            int maxPostUnknownHoles,
            int maxPostUnknownAggregateHeight) {

        public Probe {
            Objects.requireNonNull(previewType, "previewType");
            if (previewReachableOutcomes < 0
                    || recoveryHeadroom < -1
                    || recoveryAggregateHeight < -1
                    || recoveryHoles < -1
                    || recoveryBumpiness < -1
                    || unknownPieceTypes <= 0
                    || unplayableUnknownTypes < 0
                    || unplayableUnknownTypes > unknownPieceTypes
                    || minUnknownReachableOutcomes < 0
                    || averageUnknownReachableOutcomes < 0.0
                    || maxUnknownReachableOutcomes < 0
                    || minPostUnknownHeadroom < -1
                    || maxPostUnknownHoles < -1
                    || maxPostUnknownAggregateHeight < -1) {
                throw new IllegalArgumentException("invalid recovery robustness probe");
            }
            if (!previewRecoverable
                    && (previewReachableOutcomes != 0
                            || recoveryHeadroom != -1
                            || recoveryAggregateHeight != -1
                            || recoveryHoles != -1
                            || recoveryBumpiness != -1
                            || unplayableUnknownTypes != unknownPieceTypes
                            || minUnknownReachableOutcomes != 0
                            || averageUnknownReachableOutcomes != 0.0
                            || maxUnknownReachableOutcomes != 0
                            || minPostUnknownHeadroom != -1
                            || maxPostUnknownHoles != -1
                            || maxPostUnknownAggregateHeight != -1)) {
                throw new IllegalArgumentException(
                        "unrecoverable preview must use unavailable recovery facts");
            }
        }

        public boolean fullyRecoverableAcrossTetrominoes() {
            return previewRecoverable && unplayableUnknownTypes == 0;
        }

        private static Probe unrecoverable(TetrominoType previewType) {
            int types = TetrominoType.values().length;
            return new Probe(
                    previewType,
                    0,
                    false,
                    -1,
                    -1,
                    -1,
                    -1,
                    types,
                    types,
                    0,
                    0.0,
                    0,
                    -1,
                    -1,
                    -1);
        }
    }
}
