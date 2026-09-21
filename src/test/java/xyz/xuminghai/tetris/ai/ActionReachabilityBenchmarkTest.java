/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import org.junit.jupiter.api.Test;
import xyz.xuminghai.tetris.core.BoardPosition;
import xyz.xuminghai.tetris.core.TetrominoType;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActionReachabilityBenchmarkTest {

    @Test
    void actionSearchCoversLegacyLandingsOnEmptyBoard() {
        GameSnapshot snapshot = new GameSnapshot(
                20,
                10,
                new boolean[20][10],
                TetrominoType.T,
                List.of(
                        new BoardPosition(0, 4),
                        new BoardPosition(1, 3),
                        new BoardPosition(1, 4),
                        new BoardPosition(1, 5)));

        ActionReachabilityBenchmark.Result result = ActionReachabilityBenchmark.measure(snapshot);

        assertTrue(result.actionLandings() >= result.placementLandings());
        assertTrue(result.visitedActionStates() > result.actionLandings());
        assertTrue(result.placementNanos() >= 0);
        assertTrue(result.actionNanos() >= 0);
    }

    @Test
    void identicalMeasurementsHaveStableReachabilityCounts() {
        GameSnapshot snapshot = new GameSnapshot(
                10,
                8,
                new boolean[10][8],
                TetrominoType.L,
                List.of(
                        new BoardPosition(0, 3),
                        new BoardPosition(1, 3),
                        new BoardPosition(2, 3),
                        new BoardPosition(2, 4)));

        ActionReachabilityBenchmark.Result first = ActionReachabilityBenchmark.measure(snapshot);
        ActionReachabilityBenchmark.Result second = ActionReachabilityBenchmark.measure(snapshot);

        assertEquals(first.placementLandings(), second.placementLandings());
        assertEquals(first.actionLandings(), second.actionLandings());
        assertEquals(first.actionOnlyLandings(), second.actionOnlyLandings());
        assertEquals(first.visitedActionStates(), second.visitedActionStates());
    }
}
