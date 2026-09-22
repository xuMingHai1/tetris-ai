/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai.benchmark;

import org.junit.jupiter.api.Test;
import xyz.xuminghai.tetris.ai.AiAction;
import xyz.xuminghai.tetris.ai.AiMove;
import xyz.xuminghai.tetris.ai.AiPlan;
import xyz.xuminghai.tetris.ai.DeterministicActionPlanningAgent;
import xyz.xuminghai.tetris.ai.GameSnapshot;
import xyz.xuminghai.tetris.ai.HeuristicTetrisAgent;
import xyz.xuminghai.tetris.core.BagPieceGenerator;
import xyz.xuminghai.tetris.core.BoardPosition;
import xyz.xuminghai.tetris.core.Tetris;
import xyz.xuminghai.tetris.core.TetrominoType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HeadlessGameRunnerTest {

    @Test
    void reproducesTheSameGameForTheSameSeed() {
        HeadlessGameRunner runner = new HeadlessGameRunner();

        GameBenchmarkResult first = runner.run(42L, 40, new HeuristicTetrisAgent());
        GameBenchmarkResult second = runner.run(42L, 40, new HeuristicTetrisAgent());

        assertEquals(first.piecesPlaced(), second.piecesPlaced());
        assertEquals(first.linesCleared(), second.linesCleared());
        assertEquals(first.decisions(), second.decisions());
        assertEquals(first.boardHealth(), second.boardHealth());
        assertEquals(first.reachedPieceLimit(), second.reachedPieceLimit());
    }


    @Test
    void reproducesTheSameActionNativeGameForTheSameSeed() {
        HeadlessGameRunner runner = new HeadlessGameRunner();

        GameBenchmarkResult first =
                runner.runPlanning(42L, 15, new DeterministicActionPlanningAgent());
        GameBenchmarkResult second =
                runner.runPlanning(42L, 15, new DeterministicActionPlanningAgent());

        assertEquals(first.piecesPlaced(), second.piecesPlaced());
        assertEquals(first.linesCleared(), second.linesCleared());
        assertEquals(first.boardHealth(), second.boardHealth());
        assertEquals(first.reachedPieceLimit(), second.reachedPieceLimit());
    }

    @Test
    void actionNativeBenchmarkUsesFallbackWhenPrimaryFails() {
        HeadlessGameRunner runner = new HeadlessGameRunner();

        GameBenchmarkResult result = runner.runPlanning(
                7L,
                1,
                snapshot -> {
                    throw new IllegalStateException("primary unavailable");
                },
                new DeterministicActionPlanningAgent());

        assertEquals(1, result.piecesPlaced());
        assertEquals(1, result.primaryFailures());
        assertEquals(1, result.fallbackDecisions());
    }

    @Test
    void actionNativeBenchmarkTreatsNonTerminalPlanAsFailure() {
        HeadlessGameRunner runner = new HeadlessGameRunner();

        GameBenchmarkResult result = runner.runPlanning(
                9L,
                1,
                snapshot -> new AiPlan(List.of(AiAction.LEFT)),
                new DeterministicActionPlanningAgent());

        assertEquals(1, result.piecesPlaced());
        assertEquals(1, result.primaryFailures());
        assertEquals(1, result.fallbackDecisions());
    }

    @Test
    void startsAiDecisionFromTheSamePostSpawnGravityPositionAsRuntime() {
        long seed = 42L;
        BagPieceGenerator expectedGenerator = new BagPieceGenerator(seed);
        Tetris expectedPiece = expectedGenerator.next();
        Tetris expectedNextPiece = expectedGenerator.next();
        expectedPiece.downMove();
        AtomicReference<GameSnapshot> observed = new AtomicReference<>();

        new HeadlessGameRunner().run(seed, 1, snapshot -> {
            observed.set(snapshot);
            return new HeuristicTetrisAgent().decide(snapshot);
        });

        GameSnapshot snapshot = observed.get();
        assertEquals(TetrominoType.from(expectedPiece), snapshot.currentType());
        assertEquals(
                Arrays.stream(expectedPiece.getCells())
                        .map(cell -> new BoardPosition(cell.getRow(), cell.getCol()))
                        .toList(),
                snapshot.currentCells());
        assertEquals(TetrominoType.from(expectedNextPiece), snapshot.nextType().orElseThrow());
    }

    @Test
    void advancesThePreviewPieceWithoutSkippingTheBagSequence() {
        List<GameSnapshot> snapshots = new ArrayList<>();

        new HeadlessGameRunner().run(42L, 2, snapshot -> {
            snapshots.add(snapshot);
            return new HeuristicTetrisAgent().decide(snapshot);
        });

        assertEquals(2, snapshots.size());
        assertEquals(snapshots.getFirst().nextType().orElseThrow(), snapshots.get(1).currentType());
    }

    @Test
    void recordsBoardHealthFromSelectedProductionCandidates() {
        HeadlessGameRunner runner = new HeadlessGameRunner();

        GameBenchmarkResult result = runner.run(42L, 25, new HeuristicTetrisAgent());

        BoardHealthSummary health = result.boardHealth();
        assertTrue(health.averageAggregateHeight() >= 0);
        assertTrue(health.averageHoles() >= 0);
        assertTrue(health.averageBumpiness() >= 0);
        assertTrue(health.maxAggregateHeight() >= health.finalAggregateHeight());
        assertTrue(health.maxHoles() >= health.finalHoles());
        assertTrue(health.maxBumpiness() >= health.finalBumpiness());
    }

    @Test
    void usesFallbackWhenPrimaryAgentFails() {
        HeadlessGameRunner runner = new HeadlessGameRunner();

        GameBenchmarkResult result = runner.run(
                7L,
                1,
                snapshot -> {
                    throw new IllegalStateException("primary unavailable");
                },
                new HeuristicTetrisAgent());

        assertEquals(1, result.piecesPlaced());
        assertEquals(1, result.primaryFailures());
        assertEquals(1, result.fallbackDecisions());
    }

    @Test
    void treatsIllegalPrimaryMoveAsFailureAndFallsBack() {
        HeadlessGameRunner runner = new HeadlessGameRunner();

        GameBenchmarkResult result =
                runner.run(9L, 1, snapshot -> new AiMove(99, 99), new HeuristicTetrisAgent());

        assertEquals(1, result.piecesPlaced());
        assertEquals(1, result.primaryFailures());
        assertEquals(1, result.fallbackDecisions());
        assertTrue(result.totalDecisionNanos() >= 0);
    }
}
