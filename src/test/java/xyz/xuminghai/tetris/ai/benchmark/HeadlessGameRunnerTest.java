/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai.benchmark;

import org.junit.jupiter.api.Test;
import xyz.xuminghai.tetris.ai.AiMove;
import xyz.xuminghai.tetris.ai.HeuristicTetrisAgent;

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
