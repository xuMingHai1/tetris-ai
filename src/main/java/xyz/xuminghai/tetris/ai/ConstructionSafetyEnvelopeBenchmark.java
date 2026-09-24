/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import xyz.xuminghai.tetris.core.TetrominoType;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Measures the absolute board-health envelope traversed by a known clean construction witness.
 *
 * <p>This benchmark deliberately does not decide whether a metric value is safe. It records the
 * states that a production-rules witness actually traversed before clean completion, separating
 * absolute viability facts from the current runtime policy's relative comparison with SURVIVAL
 * top-1.</p>
 *
 * <p>Every step is replayed through {@link ActionPlanCandidates}. Hole coordinates come from the
 * same {@link BoardOccupancyMetrics} scan that supplies production {@link PlacementCandidate}
 * metrics, and are classified through the target semantics used by
 * {@link ShapeWitnessConstraintAudit}. The known next witness piece is also spawned from each
 * intermediate board so the diagnostic can report whether the successful path still had multiple
 * action-native continuations rather than merely surviving the current lock.</p>
 */
public final class ConstructionSafetyEnvelopeBenchmark {

    private ConstructionSafetyEnvelopeBenchmark() {
    }

    public static Result analyze(
            ShapeTarget target,
            List<ShapeConstructionFeasibilityBenchmark.WitnessStep> witness,
            int rows,
            int cols) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(witness, "witness");
        if (witness.isEmpty()) {
            throw new IllegalArgumentException("witness must not be empty");
        }
        if (witness.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("witness must not contain null");
        }
        if (rows <= 0 || cols <= 0) {
            throw new IllegalArgumentException("rows and cols must be greater than 0");
        }

        boolean[][] board = new boolean[rows][cols];
        List<Step> steps = new ArrayList<>(witness.size());

        for (int index = 0; index < witness.size(); index++) {
            ShapeConstructionFeasibilityBenchmark.WitnessStep witnessStep = witness.get(index);
            GameSnapshot snapshot =
                    BoardSimulator.snapshotForSpawnedPiece(board, witnessStep.pieceType());
            List<ActionPlanCandidates.PlannedCandidate> ranked =
                    ActionPlanCandidates.ranked(snapshot);
            if (ranked.isEmpty()) {
                throw new IllegalStateException(
                        "witness step has no reachable action-native candidates: " + (index + 1));
            }

            CandidateMatch witnessMatch = findCandidate(ranked, witnessStep.plan());
            PlacementCandidate placement = witnessMatch.candidate().placement();
            boolean[][] resultingBoard = placement.resultingBoard();
            BoardOccupancyMetrics.Analysis metrics =
                    BoardOccupancyMetrics.analyzeWithHoleCells(resultingBoard);
            ShapeWitnessConstraintAudit.HoleBreakdown holes =
                    ShapeWitnessConstraintAudit.classifyHoles(target, resultingBoard);
            if (metrics.holes() != placement.holes()
                    || holes.totalHoles() != placement.holes()) {
                throw new IllegalStateException(
                        "construction envelope hole facts disagree with PlacementCandidate");
            }

            Optional<TetrominoType> nextPieceType =
                    index + 1 < witness.size()
                            ? Optional.of(witness.get(index + 1).pieceType())
                            : Optional.empty();
            int nextReachableOutcomes = nextPieceType
                    .map(type -> ActionPlanCandidates.ranked(
                                    BoardSimulator.snapshotForSpawnedPiece(resultingBoard, type))
                            .size())
                    .orElse(-1);

            ShapeProgress progress = target.progress(resultingBoard);
            steps.add(new Step(
                    index + 1,
                    witnessStep.pieceType(),
                    witnessStep.plan().actions().size(),
                    witnessMatch.rank(),
                    ranked.size(),
                    nextPieceType,
                    nextReachableOutcomes,
                    placement.clearedLines(),
                    metrics.headroom(),
                    metrics.maxColumnHeight(),
                    placement.aggregateHeight(),
                    placement.bumpiness(),
                    holes,
                    progress));

            board = resultingBoard;
        }

        ShapeProgress finalProgress = target.progress(board);
        if (!finalProgress.cleanCompletion()) {
            throw new IllegalArgumentException(
                    "construction safety envelope requires a clean-completion witness");
        }
        return summarize(List.copyOf(steps), finalProgress);
    }

    private static CandidateMatch findCandidate(
            List<ActionPlanCandidates.PlannedCandidate> ranked,
            AiPlan plan) {
        for (int index = 0; index < ranked.size(); index++) {
            ActionPlanCandidates.PlannedCandidate candidate = ranked.get(index);
            if (candidate.plan().equals(plan)) {
                return new CandidateMatch(index + 1, candidate);
            }
        }
        throw new IllegalArgumentException(
                "witness plan is not reachable from the supplied snapshot: " + plan);
    }

    private static Result summarize(List<Step> steps, ShapeProgress finalProgress) {
        int minHeadroom = steps.stream().mapToInt(Step::headroom).min().orElseThrow();
        int maxColumnHeight =
                steps.stream().mapToInt(Step::maxColumnHeight).max().orElseThrow();
        int maxAggregateHeight =
                steps.stream().mapToInt(Step::aggregateHeight).max().orElseThrow();
        int maxRawHoles = steps.stream()
                .mapToInt(step -> step.holes().totalHoles())
                .max()
                .orElseThrow();
        int maxNonForbiddenHoles = steps.stream()
                .mapToInt(step -> step.holes().nonForbiddenHoles())
                .max()
                .orElseThrow();
        int maxRequiredHoles = steps.stream()
                .mapToInt(step -> step.holes().requiredHoles())
                .max()
                .orElseThrow();
        int maxForbiddenHoles = steps.stream()
                .mapToInt(step -> step.holes().forbiddenHoles())
                .max()
                .orElseThrow();
        int maxSupportHoles = steps.stream()
                .mapToInt(step -> step.holes().supportAllowedHoles())
                .max()
                .orElseThrow();
        int maxOutsideHoles = steps.stream()
                .mapToInt(step -> step.holes().outsideTargetHoles())
                .max()
                .orElseThrow();
        int maxBumpiness = steps.stream().mapToInt(Step::bumpiness).max().orElseThrow();
        int minReachableOutcomes =
                steps.stream().mapToInt(Step::reachableOutcomes).min().orElseThrow();
        int minNextReachableOutcomes = steps.stream()
                .mapToInt(Step::nextReachableOutcomes)
                .filter(value -> value >= 0)
                .min()
                .orElse(-1);
        double averageReachableOutcomes =
                steps.stream().mapToInt(Step::reachableOutcomes).average().orElse(0.0);
        int maxSurvivalRank =
                steps.stream().mapToInt(Step::survivalRank).max().orElseThrow();
        double averageSurvivalRank =
                steps.stream().mapToInt(Step::survivalRank).average().orElse(0.0);

        return new Result(
                steps,
                finalProgress,
                minHeadroom,
                maxColumnHeight,
                maxAggregateHeight,
                maxRawHoles,
                maxNonForbiddenHoles,
                maxRequiredHoles,
                maxForbiddenHoles,
                maxSupportHoles,
                maxOutsideHoles,
                maxBumpiness,
                minReachableOutcomes,
                minNextReachableOutcomes,
                averageReachableOutcomes,
                maxSurvivalRank,
                averageSurvivalRank);
    }

    /**
     * Absolute facts for one successful construction step.
     */
    public record Step(
            int step,
            TetrominoType pieceType,
            int actionCount,
            int survivalRank,
            int reachableOutcomes,
            Optional<TetrominoType> nextPieceType,
            int nextReachableOutcomes,
            int clearedLines,
            int headroom,
            int maxColumnHeight,
            int aggregateHeight,
            int bumpiness,
            ShapeWitnessConstraintAudit.HoleBreakdown holes,
            ShapeProgress progress) {

        public Step {
            if (step <= 0
                    || actionCount <= 0
                    || survivalRank <= 0
                    || reachableOutcomes <= 0
                    || survivalRank > reachableOutcomes
                    || nextReachableOutcomes < -1
                    || clearedLines < 0
                    || headroom < 0
                    || maxColumnHeight < 0
                    || aggregateHeight < 0
                    || bumpiness < 0) {
                throw new IllegalArgumentException("invalid construction safety envelope step");
            }
            Objects.requireNonNull(pieceType, "pieceType");
            nextPieceType = Objects.requireNonNull(nextPieceType, "nextPieceType");
            Objects.requireNonNull(holes, "holes");
            Objects.requireNonNull(progress, "progress");
            if (nextPieceType.isEmpty() != (nextReachableOutcomes == -1)) {
                throw new IllegalArgumentException(
                        "nextReachableOutcomes must be -1 exactly when there is no next witness piece");
            }
        }
    }

    /**
     * Observed extrema across one known clean witness.
     *
     * <p>These extrema describe evidence, not recommended runtime thresholds.</p>
     */
    public record Result(
            List<Step> steps,
            ShapeProgress finalProgress,
            int minHeadroom,
            int maxColumnHeight,
            int maxAggregateHeight,
            int maxRawHoles,
            int maxNonForbiddenHoles,
            int maxRequiredHoles,
            int maxForbiddenHoles,
            int maxSupportHoles,
            int maxOutsideHoles,
            int maxBumpiness,
            int minReachableOutcomes,
            int minNextReachableOutcomes,
            double averageReachableOutcomes,
            int maxSurvivalRank,
            double averageSurvivalRank) {

        public Result {
            steps = List.copyOf(Objects.requireNonNull(steps, "steps"));
            Objects.requireNonNull(finalProgress, "finalProgress");
            if (steps.isEmpty()
                    || minHeadroom < 0
                    || maxColumnHeight < 0
                    || maxAggregateHeight < 0
                    || maxRawHoles < 0
                    || maxNonForbiddenHoles < 0
                    || maxRequiredHoles < 0
                    || maxForbiddenHoles < 0
                    || maxSupportHoles < 0
                    || maxOutsideHoles < 0
                    || maxBumpiness < 0
                    || minReachableOutcomes <= 0
                    || minNextReachableOutcomes < -1
                    || averageReachableOutcomes <= 0
                    || maxSurvivalRank <= 0
                    || averageSurvivalRank <= 0) {
                throw new IllegalArgumentException("invalid construction safety envelope result");
            }
            if (!finalProgress.cleanCompletion()) {
                throw new IllegalArgumentException(
                        "construction safety envelope must end at clean completion");
            }
        }
    }

    private record CandidateMatch(
            int rank,
            ActionPlanCandidates.PlannedCandidate candidate) {
    }
}
