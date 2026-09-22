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

class TuckHunterActionPlanningAgentTest {

    @Test
    void staysInsideCurrentTopFiveSafetyShortlist() {
        GameSnapshot snapshot = snapshot(TetrominoType.I);

        AiPlan selected = new TuckHunterActionPlanningAgent().plan(snapshot);
        List<AiPlan> shortlist = ActionPlanCandidates.ranked(snapshot).stream()
                .limit(TuckHunterActionPlanningAgent.MAX_CURRENT_CANDIDATES)
                .map(ActionPlanCandidates.PlannedCandidate::plan)
                .toList();

        assertTrue(shortlist.contains(selected));
    }

    @Test
    void fallsBackToSurvivalWithoutPreviewWhenCurrentPieceHasNoActionOnlyOutcome() {
        GameSnapshot snapshot = new GameSnapshot(
                20,
                10,
                new boolean[20][10],
                TetrominoType.O,
                List.of(
                        new BoardPosition(0, 4),
                        new BoardPosition(0, 5),
                        new BoardPosition(1, 4),
                        new BoardPosition(1, 5)));

        assertEquals(
                new DeterministicActionPlanningAgent().plan(snapshot),
                new TuckHunterActionPlanningAgent().plan(snapshot));
    }

    @Test
    void emitsObjectiveTelemetryWithoutChangingTheSafetyBoundary() {
        GameSnapshot snapshot = snapshot(TetrominoType.T);
        AtomicReference<TuckHunterDecisionObservation> observed = new AtomicReference<>();

        AiPlan selected = new TuckHunterActionPlanningAgent(observed::set).plan(snapshot);

        TuckHunterDecisionObservation observation = observed.get();
        assertNotNull(observation);
        assertTrue(observation.candidateCount() <= TuckHunterActionPlanningAgent.MAX_CURRENT_CANDIDATES);
        assertTrue(observation.selectedRank() >= 1);
        assertTrue(observation.selectedRank() <= observation.candidateCount());

        List<AiPlan> shortlist = ActionPlanCandidates.ranked(snapshot).stream()
                .limit(TuckHunterActionPlanningAgent.MAX_CURRENT_CANDIDATES)
                .map(ActionPlanCandidates.PlannedCandidate::plan)
                .toList();
        assertTrue(shortlist.contains(selected));

        if (observation.selectedCurrentActionOnly()) {
            assertEquals(0, observation.setupCandidates());
            assertEquals(0, observation.selectedFutureActionOnlyCandidates());
        }
    }

    private static GameSnapshot snapshot(TetrominoType nextType) {
        return new GameSnapshot(
                20,
                10,
                new boolean[20][10],
                TetrominoType.I,
                List.of(
                        new BoardPosition(0, 3),
                        new BoardPosition(0, 4),
                        new BoardPosition(0, 5),
                        new BoardPosition(0, 6)),
                nextType);
    }
}
