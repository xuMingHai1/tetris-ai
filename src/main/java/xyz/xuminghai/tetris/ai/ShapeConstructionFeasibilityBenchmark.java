/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import xyz.xuminghai.tetris.core.TetrominoType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Bounded benchmark-only search for evidence that a creative target can be constructed through
 * the real action-native Tetris rules.
 *
 * <p>The search consumes a fixed piece sequence, expands every action-native reachable landing
 * from {@link ActionStateSearch} through {@link ActionPlanCandidates}, and advances only from the
 * production post-lock/post-row-clear {@link PlacementCandidate#resultingBoard()} facts. A found
 * clean completion is therefore constructive evidence: the returned witness is a sequence of legal
 * {@link AiPlan} values for that exact piece sequence. Failure to find a completion is deliberately
 * not treated as an impossibility proof because the frontier is bounded by a beam width.</p>
 *
 * <p>The beam is ranked for target construction rather than runtime survival. This class is an
 * offline diagnostic and must not be used as the desktop BUILD_SHAPE strategy without separate
 * benchmark evidence and safety review.</p>
 */
public final class ShapeConstructionFeasibilityBenchmark {

    private static final Comparator<SearchState> CONSTRUCTION_ORDER =
            Comparator.comparingInt((SearchState state) -> state.progress().visualErrorCells())
                    .thenComparingInt(state -> state.progress().forbiddenOccupiedCells())
                    .thenComparing(
                            Comparator.comparingInt(
                                            (SearchState state) ->
                                                    state.progress().matchedRequiredCells())
                                    .reversed())
                    .thenComparingInt(SearchState::holes)
                    .thenComparingInt(SearchState::aggregateHeight)
                    .thenComparingInt(SearchState::bumpiness);

    private ShapeConstructionFeasibilityBenchmark() {
    }

    /**
     * Searches one deterministic piece sequence on a board of the supplied dimensions.
     *
     * @param target creative target evaluated against production resulting boards
     * @param pieceSequence exact upcoming tetromino sequence
     * @param rows board row count
     * @param cols board column count
     * @param beamWidth maximum unique board states retained after each placed piece
     */
    public static Result search(
            ShapeTarget target,
            List<TetrominoType> pieceSequence,
            int rows,
            int cols,
            int beamWidth) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(pieceSequence, "pieceSequence");
        if (pieceSequence.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("pieceSequence must not contain null");
        }
        if (rows <= 0 || cols <= 0) {
            throw new IllegalArgumentException("rows and cols must be greater than 0");
        }
        if (beamWidth <= 0) {
            throw new IllegalArgumentException("beamWidth must be greater than 0");
        }

        boolean[][] emptyBoard = new boolean[rows][cols];
        ShapeProgress initialProgress = target.progress(emptyBoard);
        SearchState initial = new SearchState(
                emptyBoard,
                initialProgress,
                0,
                0,
                0,
                null,
                null,
                null);

        SearchStats stats = new SearchStats(initial);
        List<SearchState> frontier = List.of(initial);

        for (int depth = 0; depth < pieceSequence.size() && !frontier.isEmpty(); depth++) {
            stats.searchedPieces = depth + 1;
            TetrominoType pieceType = pieceSequence.get(depth);
            Map<String, SearchState> unique = new HashMap<>();

            for (SearchState state : frontier) {
                stats.expandedStates++;
                GameSnapshot snapshot =
                        BoardSimulator.snapshotForSpawnedPiece(state.board(), pieceType);
                List<ActionPlanCandidates.PlannedCandidate> candidates =
                        ActionPlanCandidates.ranked(snapshot);

                for (ActionPlanCandidates.PlannedCandidate candidate : candidates) {
                    stats.generatedPlacements++;
                    PlacementCandidate placement = candidate.placement();
                    boolean[][] resultingBoard = placement.resultingBoard();
                    ShapeProgress progress = target.progress(resultingBoard);
                    SearchState child = new SearchState(
                            resultingBoard,
                            progress,
                            placement.holes(),
                            placement.aggregateHeight(),
                            placement.bumpiness(),
                            state,
                            pieceType,
                            candidate.plan());
                    stats.observe(child);

                    if (progress.cleanCompletion()) {
                        return stats.result(
                                true,
                                depth + 1,
                                beamWidth,
                                child);
                    }

                    String fingerprint = fingerprint(resultingBoard);
                    SearchState incumbent = unique.get(fingerprint);
                    if (incumbent == null
                            || CONSTRUCTION_ORDER.compare(child, incumbent) < 0) {
                        unique.put(fingerprint, child);
                    }
                }
            }

            stats.uniqueStates += unique.size();
            List<SearchState> next = new ArrayList<>(unique.values());
            next.sort(CONSTRUCTION_ORDER);
            if (next.size() > beamWidth) {
                next = new ArrayList<>(next.subList(0, beamWidth));
            }
            stats.maxFrontierSize = Math.max(stats.maxFrontierSize, next.size());
            frontier = List.copyOf(next);
        }

        return stats.result(
                false,
                stats.searchedPieces,
                beamWidth,
                stats.bestState);
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

    private static List<WitnessStep> witness(SearchState state) {
        List<WitnessStep> reversed = new ArrayList<>();
        for (SearchState current = state;
                current != null && current.plan() != null;
                current = current.parent()) {
            reversed.add(new WitnessStep(current.pieceType(), current.plan()));
        }

        List<WitnessStep> ordered = new ArrayList<>(reversed.size());
        for (int index = reversed.size() - 1; index >= 0; index--) {
            ordered.add(reversed.get(index));
        }
        return List.copyOf(ordered);
    }

    /**
     * One legal step in a constructive witness.
     */
    public record WitnessStep(TetrominoType pieceType, AiPlan plan) {

        public WitnessStep {
            Objects.requireNonNull(pieceType, "pieceType");
            Objects.requireNonNull(plan, "plan");
        }
    }

    /**
     * Search evidence for one fixed piece sequence.
     *
     * <p>{@code minForbiddenAtFullRequired == -1} means the bounded search never observed all
     * required cells occupied at once.</p>
     */
    public record Result(
            boolean cleanCompletionFound,
            int searchedPieces,
            int piecesToCompletion,
            int beamWidth,
            long expandedStates,
            long generatedPlacements,
            long uniqueStates,
            int maxFrontierSize,
            int bestVisualErrorCells,
            int maxRequiredWithZeroForbidden,
            int minForbiddenAtFullRequired,
            ShapeProgress bestProgress,
            List<WitnessStep> witness) {

        public Result {
            if (searchedPieces < 0
                    || piecesToCompletion < -1
                    || beamWidth <= 0
                    || expandedStates < 0
                    || generatedPlacements < 0
                    || uniqueStates < 0
                    || maxFrontierSize < 0
                    || bestVisualErrorCells < 0
                    || maxRequiredWithZeroForbidden < 0
                    || minForbiddenAtFullRequired < -1) {
                throw new IllegalArgumentException("invalid shape feasibility result");
            }
            Objects.requireNonNull(bestProgress, "bestProgress");
            witness = List.copyOf(Objects.requireNonNull(witness, "witness"));
            if (cleanCompletionFound && piecesToCompletion <= 0) {
                throw new IllegalArgumentException(
                        "clean completion requires a positive piecesToCompletion");
            }
            if (!cleanCompletionFound && piecesToCompletion != -1) {
                throw new IllegalArgumentException(
                        "incomplete search must use piecesToCompletion=-1");
            }
            if (cleanCompletionFound && witness.size() != piecesToCompletion) {
                throw new IllegalArgumentException(
                        "clean completion witness must cover every placed piece");
            }
        }
    }

    private record SearchState(
            boolean[][] board,
            ShapeProgress progress,
            int holes,
            int aggregateHeight,
            int bumpiness,
            SearchState parent,
            TetrominoType pieceType,
            AiPlan plan) {

        SearchState {
            Objects.requireNonNull(board, "board");
            Objects.requireNonNull(progress, "progress");
        }
    }

    private static final class SearchStats {

        private long expandedStates;
        private long generatedPlacements;
        private long uniqueStates;
        private int maxFrontierSize = 1;
        private int searchedPieces;
        private int bestVisualErrorCells;
        private int maxRequiredWithZeroForbidden;
        private int minForbiddenAtFullRequired = Integer.MAX_VALUE;
        private SearchState bestState;

        private SearchStats(SearchState initialState) {
            bestVisualErrorCells = initialState.progress().visualErrorCells();
            maxRequiredWithZeroForbidden =
                    initialState.progress().forbiddenOccupiedCells() == 0
                            ? initialState.progress().matchedRequiredCells()
                            : 0;
            bestState = initialState;
        }

        private void observe(SearchState state) {
            if (CONSTRUCTION_ORDER.compare(state, bestState) < 0) {
                bestState = state;
            }
            bestVisualErrorCells = Math.min(
                    bestVisualErrorCells,
                    state.progress().visualErrorCells());
            if (state.progress().forbiddenOccupiedCells() == 0) {
                maxRequiredWithZeroForbidden = Math.max(
                        maxRequiredWithZeroForbidden,
                        state.progress().matchedRequiredCells());
            }
            if (state.progress().matchedRequiredCells() == state.progress().requiredCells()) {
                minForbiddenAtFullRequired = Math.min(
                        minForbiddenAtFullRequired,
                        state.progress().forbiddenOccupiedCells());
            }
        }

        private Result result(
                boolean cleanCompletionFound,
                int searchedPieces,
                int beamWidth,
                SearchState resultState) {
            int minForbidden =
                    minForbiddenAtFullRequired == Integer.MAX_VALUE
                            ? -1
                            : minForbiddenAtFullRequired;
            return new Result(
                    cleanCompletionFound,
                    searchedPieces,
                    cleanCompletionFound ? searchedPieces : -1,
                    beamWidth,
                    expandedStates,
                    generatedPlacements,
                    uniqueStates,
                    maxFrontierSize,
                    bestVisualErrorCells,
                    maxRequiredWithZeroForbidden,
                    minForbidden,
                    resultState.progress(),
                    cleanCompletionFound ? witness(resultState) : List.of());
        }

    }
}
