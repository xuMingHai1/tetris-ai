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

/**
 * Benchmark-only continuation runner for production BUILD_SHAPE from an arbitrary resulting board.
 *
 * <p>The supplied future sequence starts with the next piece to be played. Each continuation step
 * receives its real one-piece preview from the following element in that same deterministic suffix.
 * Movement, reachability, hard-drop and row-clear behavior continue to come from the production
 * action-native simulation boundary; this helper only orchestrates repeated production decisions.</p>
 */
public final class BuildShapeContinuationBenchmark {

    private BuildShapeContinuationBenchmark() {
    }

    /**
     * Continues ordinary production BUILD_SHAPE for at most {@code horizon} future placements.
     *
     * @param target creative target used by the production BUILD_SHAPE planner
     * @param initialBoard board after the counterfactual first action has locked and rows cleared
     * @param futurePieces deterministic suffix beginning with the next current piece; one extra
     *                     piece is required to provide preview for the final requested step
     * @param horizon maximum number of future placements to attempt
     */
    public static Result rollout(
            ShapeTarget target,
            boolean[][] initialBoard,
            List<TetrominoType> futurePieces,
            int horizon) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(initialBoard, "initialBoard");
        Objects.requireNonNull(futurePieces, "futurePieces");
        if (futurePieces.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(
                    "futurePieces must not contain null");
        }
        if (horizon <= 0) {
            throw new IllegalArgumentException(
                    "horizon must be greater than 0");
        }
        if (futurePieces.size() < 2) {
            throw new IllegalArgumentException(
                    "futurePieces must contain a current piece and its preview");
        }

        int requestedDepth = Math.min(
                horizon,
                futurePieces.size() - 1);
        boolean[][] board = copyBoard(initialBoard);
        DecisionCapture capture = new DecisionCapture();
        BuildShapeActionPlanningAgent agent =
                new BuildShapeActionPlanningAgent(target, capture::record);
        List<Step> steps = new ArrayList<>(requestedDepth);

        for (int index = 0; index < requestedDepth; index++) {
            TetrominoType currentType = futurePieces.get(index);
            TetrominoType nextType = futurePieces.get(index + 1);

            GameSnapshot spawned =
                    BoardSimulator.snapshotForSpawnedPiece(board, currentType);
            GameSnapshot snapshot = new GameSnapshot(
                    spawned.rows(),
                    spawned.cols(),
                    spawned.occupied(),
                    spawned.currentType(),
                    spawned.currentCells(),
                    nextType);

            if (!ActionPlanSimulator.hasReachableTerminalPlacement(snapshot)) {
                break;
            }

            capture.reset();
            AiPlan plan = agent.plan(snapshot);
            BuildShapeDecisionObservation observation = capture.current();
            if (observation == null) {
                throw new IllegalStateException(
                        "BUILD_SHAPE continuation did not emit decision observation");
            }

            PlacementCandidate placement =
                    ActionPlanSimulator.requireTerminalPlacement(snapshot, plan);
            ShapeProgress progress =
                    target.progress(placement.resultingBoard());
            steps.add(new Step(
                    index + 1,
                    currentType,
                    nextType,
                    placement,
                    observation,
                    progress));
            board = placement.resultingBoard();
        }

        return new Result(
                requestedDepth,
                steps.size(),
                steps.size() == requestedDepth,
                steps);
    }

    private static boolean[][] copyBoard(boolean[][] source) {
        Objects.requireNonNull(source, "source");
        if (source.length == 0 || source[0].length == 0) {
            throw new IllegalArgumentException(
                    "board dimensions must be positive");
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

    private static final class DecisionCapture {
        private BuildShapeDecisionObservation current;

        void reset() {
            current = null;
        }

        void record(BuildShapeDecisionObservation decision) {
            current = Objects.requireNonNull(decision, "decision");
        }

        BuildShapeDecisionObservation current() {
            return current;
        }
    }

    /**
     * One production BUILD_SHAPE continuation step.
     */
    public record Step(
            int depth,
            TetrominoType currentType,
            TetrominoType nextType,
            PlacementCandidate placement,
            BuildShapeDecisionObservation decision,
            ShapeProgress progress) {

        public Step {
            Objects.requireNonNull(currentType, "currentType");
            Objects.requireNonNull(nextType, "nextType");
            Objects.requireNonNull(placement, "placement");
            Objects.requireNonNull(decision, "decision");
            Objects.requireNonNull(progress, "progress");
            if (depth <= 0) {
                throw new IllegalArgumentException(
                        "depth must be positive");
            }
        }
    }

    /**
     * Continuation outcome for one post-action board.
     */
    public record Result(
            int requestedDepth,
            int survivedDepth,
            boolean reachedHorizon,
            List<Step> steps) {

        public Result {
            steps = List.copyOf(Objects.requireNonNull(steps, "steps"));
            if (requestedDepth <= 0
                    || survivedDepth < 0
                    || survivedDepth > requestedDepth
                    || survivedDepth != steps.size()
                    || reachedHorizon != (survivedDepth == requestedDepth)) {
                throw new IllegalArgumentException(
                        "invalid BUILD_SHAPE continuation result");
            }
        }
    }
}
