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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActionProvenanceBenchmarkTest {

    @Test
    void evaluatesProvenanceWithoutChangingDeterministicBestPlan() {
        GameSnapshot snapshot = snapshot();

        ActionProvenanceBenchmark.Observation observation =
                ActionProvenanceBenchmark.evaluate(snapshot);

        assertEquals(
                new DeterministicActionPlanningAgent().plan(snapshot),
                observation.selectedPlan());
        assertTrue(observation.totalActionCandidates() > 0);
        assertTrue(observation.totalActionOnlyCandidates() > 0);
        assertTrue(observation.bestActionOnlyRank() > 0);
        assertTrue(
                observation.topFiveActionOnlyCandidates()
                        <= observation.totalActionOnlyCandidates());
    }

    @Test
    void rejectsInconsistentActionOnlySummary() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ActionProvenanceBenchmark.Observation(
                        AiPlan.fromPlacement(AiMove.NONE),
                        10,
                        0,
                        3,
                        0));
    }

    private static GameSnapshot snapshot() {
        return new GameSnapshot(
                20,
                10,
                new boolean[20][10],
                TetrominoType.T,
                List.of(
                        new BoardPosition(0, 4),
                        new BoardPosition(1, 3),
                        new BoardPosition(1, 4),
                        new BoardPosition(1, 5)));
    }
}
