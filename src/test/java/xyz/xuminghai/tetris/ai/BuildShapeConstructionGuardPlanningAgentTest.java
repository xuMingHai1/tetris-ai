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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuildShapeConstructionGuardPlanningAgentTest {

    @Test
    void selectedPlanIsReachableAndRetainsPreviewContinuation() {
        GameSnapshot snapshot = snapshot(new boolean[20][10], true);
        AtomicReference<BuildShapeConstructionGuardObservation> observed =
                new AtomicReference<>();

        AiPlan selected =
                new BuildShapeConstructionGuardPlanningAgent(
                                ShapeTarget.HEART,
                                ConstructionSafetyGuard.Profile.RETAIN_HALF,
                                observed::set)
                        .plan(snapshot);

        List<ActionPlanCandidates.PlannedCandidate> ranked =
                ActionPlanCandidates.ranked(snapshot);
        BuildShapeConstructionGuardObservation observation = observed.get();

        assertNotNull(observation);
        assertTrue(ranked.size() > BuildShapeActionPlanningAgent.MAX_CURRENT_CANDIDATES);
        assertTrue(ranked.stream().anyMatch(candidate -> candidate.plan().equals(selected)));
        assertEquals(ranked.size(), observation.reachableCandidateCount());
        assertTrue(observation.previewAvailable());
        assertTrue(observation.baselineNextReachableOutcomes() > 0);
        assertTrue(observation.selectedNextReachableOutcomes() > 0);
        assertTrue(observation.selectedRank() <= ranked.size());
        assertTrue(observation.selectedProgress().netScore()
                >= observation.baselineProgress().netScore());
    }

    @Test
    void missingPreviewFallsBackToExactSurvivalTopChoice() {
        GameSnapshot snapshot = snapshot(new boolean[20][10], false);
        AtomicReference<BuildShapeConstructionGuardObservation> observed =
                new AtomicReference<>();
        List<ActionPlanCandidates.PlannedCandidate> ranked =
                ActionPlanCandidates.ranked(snapshot);

        AiPlan selected =
                new BuildShapeConstructionGuardPlanningAgent(
                                ShapeTarget.HEART,
                                ConstructionSafetyGuard.Profile.ANY_CONTINUATION,
                                observed::set)
                        .plan(snapshot);

        assertEquals(ranked.getFirst().plan(), selected);
        BuildShapeConstructionGuardObservation observation = observed.get();
        assertNotNull(observation);
        assertEquals(1, observation.selectedRank());
        assertEquals(-1, observation.baselineNextReachableOutcomes());
        assertEquals(-1, observation.selectedNextReachableOutcomes());
        assertEquals(0, observation.guardCheckedCandidates());
    }

    private static GameSnapshot snapshot(boolean[][] board, boolean includePreview) {
        List<BoardPosition> cells = List.of(
                new BoardPosition(0, 4),
                new BoardPosition(1, 3),
                new BoardPosition(1, 4),
                new BoardPosition(1, 5));
        if (includePreview) {
            return new GameSnapshot(
                    20,
                    10,
                    board,
                    TetrominoType.T,
                    cells,
                    TetrominoType.I);
        }
        return new GameSnapshot(
                20,
                10,
                board,
                TetrominoType.T,
                cells);
    }
}
