/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai.benchmark;

import xyz.xuminghai.tetris.ai.AiMove;
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
 * <p>The runner does not implement a second game engine. Each turn is advanced by the resulting
 * board of a {@link PlacementCandidate} produced by the same {@link BoardSimulator} used by live
 * agents. It therefore measures strategy quality and decision cost while reusing the production
 * placement and row-clear semantics.</p>
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
     * Runs one reproducible game.
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
        if (pieceLimit <= 0) {
            throw new IllegalArgumentException("pieceLimit must be greater than 0");
        }
        Objects.requireNonNull(primaryAgent, "primaryAgent");

        PieceGenerator pieceGenerator = new BagPieceGenerator(seed);
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
            Tetris piece = pieceGenerator.next();
            GameSnapshot snapshot = snapshot(board, piece);
            List<PlacementCandidate> candidates = BoardSimulator.candidates(snapshot);
            if (candidates.isEmpty()) {
                break;
            }

            long started = System.nanoTime();
            AiMove move;
            try {
                move = requireLegalMove(primaryAgent.decide(snapshot), candidates);
            }
            catch (RuntimeException primaryFailure) {
                primaryFailures++;
                if (fallbackAgent == null) {
                    throw new IllegalStateException(
                            "Primary benchmark agent failed and no fallback is configured", primaryFailure);
                }
                fallbackDecisions++;
                move = requireLegalMove(fallbackAgent.decide(snapshot), candidates);
            }
            long elapsed = System.nanoTime() - started;

            PlacementCandidate selected = candidateFor(move, candidates);
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

    public GameBenchmarkResult run(long seed, int pieceLimit, TetrisAgent agent) {
        return run(seed, pieceLimit, agent, null);
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

    private GameSnapshot snapshot(boolean[][] board, Tetris piece) {
        Cell[] cells = piece.getCells();
        return new GameSnapshot(
                rows,
                cols,
                board,
                TetrominoType.from(piece),
                Arrays.stream(cells)
                        .map(cell -> new BoardPosition(cell.getRow(), cell.getCol()))
                        .toList());
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
}
