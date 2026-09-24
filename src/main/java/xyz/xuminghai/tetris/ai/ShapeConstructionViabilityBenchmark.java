/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import xyz.xuminghai.tetris.core.TetrominoType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Compares known clean-construction states with the SURVIVAL top-1 counterfactual under the same
 * deterministic future piece sequence.
 *
 * <p>The benchmark produces two continuation signals. A greedy rollout follows the production
 * SURVIVAL heuristic top-1 until it cannot place the next piece or reaches the configured horizon.
 * A bounded existential search keeps unique production resulting boards and retains the
 * survival-ranked best beam after each future piece. Reaching the requested search horizon is
 * constructive evidence that at least one continuation exists. Frontier exhaustion is an exact
 * failure only when no earlier depth was pruned; exhaustion after pruning is deliberately reported
 * as inconclusive.</p>
 *
 * <p>This class is diagnostic only. Observed horizons and frontier sizes are evidence, not runtime
 * safety thresholds.</p>
 */
public final class ShapeConstructionViabilityBenchmark {

    private ShapeConstructionViabilityBenchmark() {
    }

    public static Result calibrate(
            ShapeTarget target,
            List<ShapeConstructionFeasibilityBenchmark.WitnessStep> witness,
            List<TetrominoType> fullPieceSequence,
            int rows,
            int cols,
            int searchDepth,
            int beamWidth,
            int greedyDepth) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(witness, "witness");
        Objects.requireNonNull(fullPieceSequence, "fullPieceSequence");
        if (witness.isEmpty()) {
            throw new IllegalArgumentException("witness must not be empty");
        }
        if (witness.stream().anyMatch(Objects::isNull)
                || fullPieceSequence.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("piece sequences must not contain null");
        }
        if (fullPieceSequence.size() < witness.size()) {
            throw new IllegalArgumentException(
                    "fullPieceSequence must contain the complete witness prefix");
        }
        if (rows <= 0 || cols <= 0 || searchDepth <= 0 || beamWidth <= 0 || greedyDepth <= 0) {
            throw new IllegalArgumentException(
                    "board dimensions and viability limits must be greater than 0");
        }

        boolean[][] witnessBoard = new boolean[rows][cols];
        List<Comparison> comparisons = new ArrayList<>(witness.size());

        for (int index = 0; index < witness.size(); index++) {
            ShapeConstructionFeasibilityBenchmark.WitnessStep witnessStep = witness.get(index);
            TetrominoType expectedPiece = fullPieceSequence.get(index);
            if (expectedPiece != witnessStep.pieceType()) {
                throw new IllegalArgumentException(
                        "fullPieceSequence does not match witness at step " + (index + 1));
            }

            GameSnapshot snapshot =
                    BoardSimulator.snapshotForSpawnedPiece(witnessBoard, witnessStep.pieceType());
            List<ActionPlanCandidates.PlannedCandidate> ranked =
                    ActionPlanCandidates.ranked(snapshot);
            if (ranked.isEmpty()) {
                throw new IllegalStateException(
                        "witness step has no reachable action-native candidates: " + (index + 1));
            }

            CandidateMatch witnessMatch = findCandidate(ranked, witnessStep.plan());
            PlacementCandidate witnessPlacement = witnessMatch.candidate().placement();
            PlacementCandidate survivalPlacement = ranked.getFirst().placement();
            List<TetrominoType> futurePieces =
                    fullPieceSequence.subList(index + 1, fullPieceSequence.size());

            Sample witnessSample = sample(
                    target,
                    Source.WITNESS,
                    index + 1,
                    witnessStep.pieceType(),
                    witnessMatch.rank(),
                    witnessPlacement,
                    futurePieces,
                    searchDepth,
                    beamWidth,
                    greedyDepth);
            Sample survivalSample = sample(
                    target,
                    Source.SURVIVAL_BASELINE,
                    index + 1,
                    witnessStep.pieceType(),
                    1,
                    survivalPlacement,
                    futurePieces,
                    searchDepth,
                    beamWidth,
                    greedyDepth);

            comparisons.add(new Comparison(
                    index + 1,
                    witnessStep.pieceType(),
                    witnessSample,
                    survivalSample));
            witnessBoard = witnessPlacement.resultingBoard();
        }

        if (!target.progress(witnessBoard).cleanCompletion()) {
            throw new IllegalArgumentException(
                    "construction viability calibration requires a clean-completion witness");
        }

        return summarize(List.copyOf(comparisons));
    }

    private static Sample sample(
            ShapeTarget target,
            Source source,
            int step,
            TetrominoType pieceType,
            int survivalRank,
            PlacementCandidate placement,
            List<TetrominoType> futurePieces,
            int searchDepth,
            int beamWidth,
            int greedyDepth) {
        boolean[][] board = placement.resultingBoard();
        BoardOccupancyMetrics.Analysis metrics =
                BoardOccupancyMetrics.analyzeWithHoleCells(board);
        ShapeWitnessConstraintAudit.HoleBreakdown holes =
                ShapeWitnessConstraintAudit.classifyHoles(target, board);
        if (metrics.holes() != placement.holes()
                || holes.totalHoles() != placement.holes()) {
            throw new IllegalStateException(
                    "viability calibration hole facts disagree with PlacementCandidate");
        }

        Probe probe = probe(board, futurePieces, searchDepth, beamWidth, greedyDepth);
        return new Sample(
                source,
                step,
                pieceType,
                survivalRank,
                metrics.headroom(),
                metrics.maxColumnHeight(),
                placement.aggregateHeight(),
                placement.bumpiness(),
                holes,
                target.progress(board),
                probe);
    }

    static Probe probe(
            boolean[][] initialBoard,
            List<TetrominoType> futurePieces,
            int searchDepth,
            int beamWidth,
            int greedyDepth) {
        Objects.requireNonNull(initialBoard, "initialBoard");
        Objects.requireNonNull(futurePieces, "futurePieces");
        if (futurePieces.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("futurePieces must not contain null");
        }
        if (searchDepth <= 0 || beamWidth <= 0 || greedyDepth <= 0) {
            throw new IllegalArgumentException(
                    "viability limits must be greater than 0");
        }

        int requestedSearchDepth = Math.min(searchDepth, futurePieces.size());
        int requestedGreedyDepth = Math.min(greedyDepth, futurePieces.size());
        GreedyResult greedy = greedyRollout(initialBoard, futurePieces, requestedGreedyDepth);
        SearchResult search = existentialSearch(
                initialBoard,
                futurePieces,
                requestedSearchDepth,
                beamWidth);

        return new Probe(
                futurePieces.size(),
                requestedGreedyDepth,
                greedy.survivedDepth(),
                greedy.exhausted(),
                requestedSearchDepth,
                search.survivedDepth(),
                search.status(),
                search.pruned(),
                search.firstReachableOutcomes(),
                search.minRetainedFrontier(),
                search.finalRetainedFrontier(),
                search.maxUniqueStates(),
                search.expandedStates(),
                search.generatedPlacements());
    }

    private static GreedyResult greedyRollout(
            boolean[][] initialBoard,
            List<TetrominoType> futurePieces,
            int depth) {
        boolean[][] board = copyBoard(initialBoard);
        int survived = 0;
        for (int index = 0; index < depth; index++) {
            List<ActionPlanCandidates.PlannedCandidate> ranked =
                    ActionPlanCandidates.ranked(
                            BoardSimulator.snapshotForSpawnedPiece(
                                    board,
                                    futurePieces.get(index)));
            if (ranked.isEmpty()) {
                return new GreedyResult(survived, true);
            }
            board = ranked.getFirst().placement().resultingBoard();
            survived++;
        }
        return new GreedyResult(survived, false);
    }

    private static SearchResult existentialSearch(
            boolean[][] initialBoard,
            List<TetrominoType> futurePieces,
            int depth,
            int beamWidth) {
        if (depth == 0) {
            return new SearchResult(
                    0,
                    SearchStatus.SURVIVED_HORIZON,
                    false,
                    -1,
                    1,
                    1,
                    1,
                    0,
                    0);
        }

        List<boolean[][]> frontier = List.<boolean[][]>of(copyBoard(initialBoard));
        boolean pruned = false;
        int survived = 0;
        int firstReachableOutcomes = -1;
        int minRetainedFrontier = Integer.MAX_VALUE;
        int maxUniqueStates = 1;
        long expandedStates = 0;
        long generatedPlacements = 0;

        for (int pieceIndex = 0; pieceIndex < depth; pieceIndex++) {
            TetrominoType pieceType = futurePieces.get(pieceIndex);
            Map<String, PlacementCandidate> unique = new HashMap<>();
            int reachableFromInitial = 0;

            for (boolean[][] board : frontier) {
                expandedStates++;
                List<ActionPlanCandidates.PlannedCandidate> ranked =
                        ActionPlanCandidates.ranked(
                                BoardSimulator.snapshotForSpawnedPiece(board, pieceType));
                if (pieceIndex == 0) {
                    reachableFromInitial += ranked.size();
                }
                for (ActionPlanCandidates.PlannedCandidate candidate : ranked) {
                    generatedPlacements++;
                    PlacementCandidate placement = candidate.placement();
                    unique.putIfAbsent(
                            fingerprint(placement.resultingBoard()),
                            placement);
                }
            }

            if (pieceIndex == 0) {
                firstReachableOutcomes = reachableFromInitial;
            }
            if (unique.isEmpty()) {
                SearchStatus status = pruned
                        ? SearchStatus.EXHAUSTED_AFTER_PRUNING
                        : SearchStatus.EXHAUSTED_EXACT;
                return new SearchResult(
                        survived,
                        status,
                        pruned,
                        firstReachableOutcomes,
                        minRetainedFrontier == Integer.MAX_VALUE ? 0 : minRetainedFrontier,
                        0,
                        maxUniqueStates,
                        expandedStates,
                        generatedPlacements);
            }

            maxUniqueStates = Math.max(maxUniqueStates, unique.size());
            List<PlacementCandidate> rankedUnique =
                    HeuristicTetrisAgent.rankCandidates(new ArrayList<>(unique.values()));
            if (rankedUnique.size() > beamWidth) {
                rankedUnique = rankedUnique.subList(0, beamWidth);
                pruned = true;
            }
            frontier = rankedUnique.stream()
                    .map(PlacementCandidate::resultingBoard)
                    .toList();
            survived++;
            minRetainedFrontier = Math.min(minRetainedFrontier, frontier.size());
        }

        return new SearchResult(
                survived,
                SearchStatus.SURVIVED_HORIZON,
                pruned,
                firstReachableOutcomes,
                minRetainedFrontier == Integer.MAX_VALUE ? 0 : minRetainedFrontier,
                frontier.size(),
                maxUniqueStates,
                expandedStates,
                generatedPlacements);
    }

    private static Result summarize(List<Comparison> comparisons) {
        long witnessGreedyAtLeastBaseline = comparisons.stream()
                .filter(comparison ->
                        comparison.witness().probe().greedySurvivedDepth()
                                >= comparison.survivalBaseline().probe().greedySurvivedDepth())
                .count();
        long witnessSearchAtLeastBaseline = comparisons.stream()
                .filter(comparison ->
                        comparison.witness().probe().searchSurvivedDepth()
                                >= comparison.survivalBaseline().probe().searchSurvivedDepth())
                .count();
        long witnessFullGreedyHorizon = comparisons.stream()
                .filter(comparison ->
                        comparison.witness().probe().greedySurvivedDepth()
                                == comparison.witness().probe().requestedGreedyDepth())
                .count();
        long witnessFullSearchHorizon = comparisons.stream()
                .filter(comparison ->
                        comparison.witness().probe().searchSurvivedDepth()
                                == comparison.witness().probe().requestedSearchDepth())
                .count();
        long witnessExactExhaustions = comparisons.stream()
                .filter(comparison ->
                        comparison.witness().probe().searchStatus()
                                == SearchStatus.EXHAUSTED_EXACT)
                .count();
        long baselineExactExhaustions = comparisons.stream()
                .filter(comparison ->
                        comparison.survivalBaseline().probe().searchStatus()
                                == SearchStatus.EXHAUSTED_EXACT)
                .count();
        int minWitnessGreedyHorizon = comparisons.stream()
                .mapToInt(comparison -> comparison.witness().probe().greedySurvivedDepth())
                .min()
                .orElseThrow();
        int minWitnessSearchHorizon = comparisons.stream()
                .mapToInt(comparison -> comparison.witness().probe().searchSurvivedDepth())
                .min()
                .orElseThrow();
        int minWitnessInitialReachable = comparisons.stream()
                .mapToInt(comparison -> comparison.witness().probe().firstReachableOutcomes())
                .filter(value -> value >= 0)
                .min()
                .orElse(-1);

        return new Result(
                comparisons,
                witnessGreedyAtLeastBaseline,
                witnessSearchAtLeastBaseline,
                witnessFullGreedyHorizon,
                witnessFullSearchHorizon,
                witnessExactExhaustions,
                baselineExactExhaustions,
                minWitnessGreedyHorizon,
                minWitnessSearchHorizon,
                minWitnessInitialReachable);
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

    private static String fingerprint(boolean[][] board) {
        StringBuilder value = new StringBuilder(board.length * board[0].length);
        for (boolean[] row : board) {
            for (boolean occupied : row) {
                value.append(occupied ? '1' : '0');
            }
        }
        return value.toString();
    }

    private static boolean[][] copyBoard(boolean[][] source) {
        Objects.requireNonNull(source, "source");
        if (source.length == 0 || source[0].length == 0) {
            throw new IllegalArgumentException("board dimensions must be positive");
        }
        int cols = source[0].length;
        boolean[][] copy = new boolean[source.length][cols];
        for (int row = 0; row < source.length; row++) {
            if (source[row].length != cols) {
                throw new IllegalArgumentException(
                        "board rows must have a consistent column count");
            }
            System.arraycopy(source[row], 0, copy[row], 0, cols);
        }
        return copy;
    }

    public enum Source {
        WITNESS,
        SURVIVAL_BASELINE
    }

    public enum SearchStatus {
        SURVIVED_HORIZON,
        EXHAUSTED_EXACT,
        EXHAUSTED_AFTER_PRUNING
    }

    public record Probe(
            int availableFuturePieces,
            int requestedGreedyDepth,
            int greedySurvivedDepth,
            boolean greedyExhausted,
            int requestedSearchDepth,
            int searchSurvivedDepth,
            SearchStatus searchStatus,
            boolean searchPruned,
            int firstReachableOutcomes,
            int minRetainedFrontier,
            int finalRetainedFrontier,
            int maxUniqueStates,
            long expandedStates,
            long generatedPlacements) {

        public Probe {
            Objects.requireNonNull(searchStatus, "searchStatus");
            if (availableFuturePieces < 0
                    || requestedGreedyDepth < 0
                    || greedySurvivedDepth < 0
                    || greedySurvivedDepth > requestedGreedyDepth
                    || requestedSearchDepth < 0
                    || searchSurvivedDepth < 0
                    || searchSurvivedDepth > requestedSearchDepth
                    || firstReachableOutcomes < -1
                    || minRetainedFrontier < 0
                    || finalRetainedFrontier < 0
                    || maxUniqueStates < 0
                    || expandedStates < 0
                    || generatedPlacements < 0) {
                throw new IllegalArgumentException("invalid viability probe");
            }
        }
    }

    public record Sample(
            Source source,
            int step,
            TetrominoType pieceType,
            int survivalRank,
            int headroom,
            int maxColumnHeight,
            int aggregateHeight,
            int bumpiness,
            ShapeWitnessConstraintAudit.HoleBreakdown holes,
            ShapeProgress progress,
            Probe probe) {

        public Sample {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(pieceType, "pieceType");
            Objects.requireNonNull(holes, "holes");
            Objects.requireNonNull(progress, "progress");
            Objects.requireNonNull(probe, "probe");
            if (step <= 0
                    || survivalRank <= 0
                    || headroom < 0
                    || maxColumnHeight < 0
                    || aggregateHeight < 0
                    || bumpiness < 0) {
                throw new IllegalArgumentException("invalid viability calibration sample");
            }
        }
    }

    public record Comparison(
            int step,
            TetrominoType pieceType,
            Sample witness,
            Sample survivalBaseline) {

        public Comparison {
            Objects.requireNonNull(pieceType, "pieceType");
            Objects.requireNonNull(witness, "witness");
            Objects.requireNonNull(survivalBaseline, "survivalBaseline");
            if (step <= 0
                    || witness.source() != Source.WITNESS
                    || survivalBaseline.source() != Source.SURVIVAL_BASELINE
                    || witness.step() != step
                    || survivalBaseline.step() != step) {
                throw new IllegalArgumentException("invalid viability comparison");
            }
        }
    }

    public record Result(
            List<Comparison> comparisons,
            long witnessGreedyAtLeastBaselineSteps,
            long witnessSearchAtLeastBaselineSteps,
            long witnessFullGreedyHorizonSteps,
            long witnessFullSearchHorizonSteps,
            long witnessExactExhaustions,
            long baselineExactExhaustions,
            int minWitnessGreedyHorizon,
            int minWitnessSearchHorizon,
            int minWitnessInitialReachableOutcomes) {

        public Result {
            comparisons = List.copyOf(Objects.requireNonNull(comparisons, "comparisons"));
            if (comparisons.isEmpty()
                    || witnessGreedyAtLeastBaselineSteps < 0
                    || witnessSearchAtLeastBaselineSteps < 0
                    || witnessFullGreedyHorizonSteps < 0
                    || witnessFullSearchHorizonSteps < 0
                    || witnessExactExhaustions < 0
                    || baselineExactExhaustions < 0
                    || minWitnessGreedyHorizon < 0
                    || minWitnessSearchHorizon < 0
                    || minWitnessInitialReachableOutcomes < -1) {
                throw new IllegalArgumentException("invalid viability calibration result");
            }
        }
    }

    private record CandidateMatch(
            int rank,
            ActionPlanCandidates.PlannedCandidate candidate) {
    }

    private record GreedyResult(int survivedDepth, boolean exhausted) {
    }

    private record SearchResult(
            int survivedDepth,
            SearchStatus status,
            boolean pruned,
            int firstReachableOutcomes,
            int minRetainedFrontier,
            int finalRetainedFrontier,
            int maxUniqueStates,
            long expandedStates,
            long generatedPlacements) {
    }
}
