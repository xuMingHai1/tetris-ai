/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai.benchmark;

import xyz.xuminghai.tetris.ai.ActionPlanSimulator;
import xyz.xuminghai.tetris.ai.AiMove;
import xyz.xuminghai.tetris.ai.AiPlanningAgent;
import xyz.xuminghai.tetris.ai.BoardSimulator;
import xyz.xuminghai.tetris.ai.GameSnapshot;
import xyz.xuminghai.tetris.ai.PlacementCandidate;
import xyz.xuminghai.tetris.ai.TetrisAgent;
import xyz.xuminghai.tetris.core.BagPieceGenerator;
import xyz.xuminghai.tetris.core.BoardPosition;
import xyz.xuminghai.tetris.core.Cell;
import xyz.xuminghai.tetris.core.PieceGenerator;
import xyz.xuminghai.tetris.core.Tetris;
import xyz.xuminghai.tetris.core.TetrominoType;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Runs deterministic Tetris games without JavaFX animation, input, audio or wall-clock gravity.
 *
 * <p>Placement-oriented runs advance from {@link BoardSimulator} candidates. Action-native runs
 * resolve {@link xyz.xuminghai.tetris.ai.AiPlan} through {@link ActionPlanSimulator}. Both paths
 * therefore reuse production movement, collision, hard-drop and row-clear semantics instead of
 * introducing benchmark-only Tetris rules.</p>
 */
public final class HeadlessGameRunner {

    public static final int DEFAULT_ROWS = 20;
    public static final int DEFAULT_COLS = 10;

    private final int rows;
    private final int cols;

    public HeadlessGameRunner() {
        this(DEFAULT_ROWS, DEFAULT_COLS);
    }

    public HeadlessGameRunner(int rows, int cols) {
        if (rows <= 0 || cols <= 0) {
            throw new IllegalArgumentException("rows and cols must be greater than 0");
        }
        this.rows = rows;
        this.cols = cols;
    }

    /**
     * Runs one reproducible placement-oriented game.
     *
     * @param seed deterministic 7-bag seed
     * @param pieceLimit maximum pieces placed before ending the sample
     * @param primaryAgent strategy under evaluation
     * @param fallbackAgent optional fallback used only when the primary fails or returns an illegal move
     */
    public GameBenchmarkResult run(
            long seed,
            int pieceLimit,
            TetrisAgent primaryAgent,
            TetrisAgent fallbackAgent) {
        Objects.requireNonNull(primaryAgent, "primaryAgent");
        return runGame(
                seed,
                pieceLimit,
                primaryAgent,
                fallbackAgent,
                new TurnAdapter<TetrisAgent, List<PlacementCandidate>>() {
                    @Override
                    public List<PlacementCandidate> prepare(GameSnapshot snapshot) {
                        List<PlacementCandidate> candidates = BoardSimulator.candidates(snapshot);
                        return candidates.isEmpty() ? null : candidates;
                    }

                    @Override
                    public PlacementCandidate decide(
                            TetrisAgent agent,
                            GameSnapshot snapshot,
                            List<PlacementCandidate> candidates) {
                        AiMove move = requireLegalMove(agent.decide(snapshot), candidates);
                        return candidateFor(move, candidates);
                    }
                });
    }

    public GameBenchmarkResult run(long seed, int pieceLimit, TetrisAgent agent) {
        return run(seed, pieceLimit, agent, null);
    }

    /**
     * Runs one reproducible action-native game.
     *
     * <p>The distinct method name intentionally avoids overload ambiguity between the
     * {@link TetrisAgent} and {@link AiPlanningAgent} functional interfaces.</p>
     *
     * @param seed deterministic 7-bag seed
     * @param pieceLimit maximum pieces placed before ending the sample
     * @param primaryAgent action-native strategy under evaluation
     * @param fallbackAgent optional action-native fallback for provider or illegal-plan failures
     */
    public GameBenchmarkResult runPlanning(
            long seed,
            int pieceLimit,
            AiPlanningAgent primaryAgent,
            AiPlanningAgent fallbackAgent) {
        Objects.requireNonNull(primaryAgent, "primaryAgent");
        return runGame(
                seed,
                pieceLimit,
                primaryAgent,
                fallbackAgent,
                new TurnAdapter<AiPlanningAgent, Boolean>() {
                    @Override
                    public Boolean prepare(GameSnapshot snapshot) {
                        return ActionPlanSimulator.hasReachableTerminalPlacement(snapshot)
                                ? Boolean.TRUE
                                : null;
                    }

                    @Override
                    public PlacementCandidate decide(
                            AiPlanningAgent agent,
                            GameSnapshot snapshot,
                            Boolean ignored) {
                        return ActionPlanSimulator.requireTerminalPlacement(
                                snapshot,
                                agent.plan(snapshot));
                    }
                });
    }

    public GameBenchmarkResult runPlanning(long seed, int pieceLimit, AiPlanningAgent agent) {
        return runPlanning(seed, pieceLimit, agent, null);
    }

    private <A, C> GameBenchmarkResult runGame(
            long seed,
            int pieceLimit,
            A primaryAgent,
            A fallbackAgent,
            TurnAdapter<A, C> adapter) {
        if (pieceLimit <= 0) {
            throw new IllegalArgumentException("pieceLimit must be greater than 0");
        }
        Objects.requireNonNull(primaryAgent, "primaryAgent");
        Objects.requireNonNull(adapter, "adapter");

        PieceGenerator pieceGenerator = new BagPieceGenerator(seed);
        Tetris currentPiece = pieceGenerator.next();
        Tetris nextPiece = pieceGenerator.next();
        boolean[][] board = new boolean[rows][cols];
        int piecesPlaced = 0;
        int linesCleared = 0;
        int decisions = 0;
        int primaryFailures = 0;
        int fallbackDecisions = 0;
        long totalDecisionNanos = 0L;
        long maxDecisionNanos = 0L;
        long aggregateHeightSum = 0L;
        long holesSum = 0L;
        long bumpinessSum = 0L;
        int maxAggregateHeight = 0;
        int maxHoles = 0;
        int maxBumpiness = 0;
        PlacementCandidate lastSelected = null;

        while (piecesPlaced < pieceLimit) {
            // Live GameWorld requests AI only after the newly spawned piece has completed the
            // first automatic gravity move. Mirror that exact decision boundary here so top-edge
            // reachability and rotation checks are evaluated from the same coordinates.
            currentPiece.downMove();
            GameSnapshot snapshot = snapshot(board, currentPiece, nextPiece);
            C context = adapter.prepare(snapshot);
            if (context == null) {
                break;
            }

            long started = System.nanoTime();
            PlacementCandidate selected;
            try {
                selected = adapter.decide(primaryAgent, snapshot, context);
            }
            catch (RuntimeException primaryFailure) {
                primaryFailures++;
                if (fallbackAgent == null) {
                    throw new IllegalStateException(
                            "Primary benchmark agent failed and no fallback is configured", primaryFailure);
                }
                fallbackDecisions++;
                selected = adapter.decide(fallbackAgent, snapshot, context);
            }
            long elapsed = System.nanoTime() - started;

            board = selected.resultingBoard();
            linesCleared += selected.clearedLines();
            aggregateHeightSum += selected.aggregateHeight();
            holesSum += selected.holes();
            bumpinessSum += selected.bumpiness();
            maxAggregateHeight = Math.max(maxAggregateHeight, selected.aggregateHeight());
            maxHoles = Math.max(maxHoles, selected.holes());
            maxBumpiness = Math.max(maxBumpiness, selected.bumpiness());
            lastSelected = selected;
            piecesPlaced++;
            decisions++;
            totalDecisionNanos += elapsed;
            maxDecisionNanos = Math.max(maxDecisionNanos, elapsed);

            currentPiece = nextPiece;
            nextPiece = pieceGenerator.next();
        }

        BoardHealthSummary boardHealth = boardHealth(
                lastSelected,
                piecesPlaced,
                aggregateHeightSum,
                holesSum,
                bumpinessSum,
                maxAggregateHeight,
                maxHoles,
                maxBumpiness);

        return new GameBenchmarkResult(
                seed,
                pieceLimit,
                piecesPlaced,
                linesCleared,
                decisions,
                primaryFailures,
                fallbackDecisions,
                totalDecisionNanos,
                maxDecisionNanos,
                boardHealth,
                piecesPlaced == pieceLimit);
    }

    private static BoardHealthSummary boardHealth(
            PlacementCandidate lastSelected,
            int piecesPlaced,
            long aggregateHeightSum,
            long holesSum,
            long bumpinessSum,
            int maxAggregateHeight,
            int maxHoles,
            int maxBumpiness) {
        if (lastSelected == null || piecesPlaced == 0) {
            return BoardHealthSummary.empty();
        }
        return new BoardHealthSummary(
                lastSelected.aggregateHeight(),
                (double) aggregateHeightSum / piecesPlaced,
                maxAggregateHeight,
                lastSelected.holes(),
                (double) holesSum / piecesPlaced,
                maxHoles,
                lastSelected.bumpiness(),
                (double) bumpinessSum / piecesPlaced,
                maxBumpiness);
    }

    private GameSnapshot snapshot(boolean[][] board, Tetris piece, Tetris nextPiece) {
        Cell[] cells = piece.getCells();
        return new GameSnapshot(
                rows,
                cols,
                board,
                TetrominoType.from(piece),
                Arrays.stream(cells)
                        .map(cell -> new BoardPosition(cell.getRow(), cell.getCol()))
                        .toList(),
                TetrominoType.from(nextPiece));
    }

    private static AiMove requireLegalMove(AiMove move, List<PlacementCandidate> candidates) {
        Objects.requireNonNull(move, "TetrisAgent returned null");
        candidateFor(move, candidates);
        return move;
    }

    private static PlacementCandidate candidateFor(AiMove move, List<PlacementCandidate> candidates) {
        return candidates.stream()
                .filter(candidate -> candidate.move().equals(move))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "TetrisAgent returned a move that is not legal for the current snapshot: " + move));
    }

    private interface TurnAdapter<A, C> {

        /**
         * Prepares legality facts outside the measured agent latency.
         *
         * @return turn context, or {@code null} when the game has no legal terminal placement
         */
        C prepare(GameSnapshot snapshot);

        PlacementCandidate decide(A agent, GameSnapshot snapshot, C context);
    }
}
