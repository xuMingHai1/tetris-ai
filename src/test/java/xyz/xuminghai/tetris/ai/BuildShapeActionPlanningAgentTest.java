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

class BuildShapeActionPlanningAgentTest {

    @Test
    void selectedPlanStaysInsideRiskBudgetAndDoesNotRegressShapePreference() {
        GameSnapshot snapshot = snapshot();
        AtomicReference<BuildShapeDecisionObservation> observed = new AtomicReference<>();

        AiPlan selected =
                new BuildShapeActionPlanningAgent(ShapeTarget.HEART, observed::set).plan(snapshot);

        BuildShapeDecisionObservation observation = observed.get();
        assertNotNull(observation);

        List<ActionPlanCandidates.PlannedCandidate> shortlist =
                ActionPlanCandidates.ranked(snapshot).stream()
                        .limit(BuildShapeActionPlanningAgent.MAX_CURRENT_CANDIDATES)
                        .toList();
        ActionPlanCandidates.PlannedCandidate selectedCandidate = shortlist.stream()
                .filter(candidate -> candidate.plan().equals(selected))
                .findFirst()
                .orElseThrow();

        ObjectiveRiskController.Decision riskDecision =
                ObjectiveRiskController.adaptive().decide(shortlist.getFirst().placement());
        ObjectiveSafetyBudget.Assessment safety = riskDecision.profile().budget()
                .assess(shortlist.getFirst().placement(), selectedCandidate.placement());

        assertTrue(safety.allowed());
        assertEquals(riskDecision.level(), observation.riskLevel());
        assertEquals(riskDecision.profile(), observation.riskProfile());
        assertTrue(observation.selectedProgress().netScore()
                >= observation.baselineProgress().netScore());
        if (observation.selectedProgress().netScore()
                == observation.baselineProgress().netScore()) {
            assertTrue(observation.selectedProgress().matchedCells()
                    >= observation.baselineProgress().matchedCells());
        }
    }

    @Test
    void fallsBackToNoneWhenNoReachableLandingExists() {
        boolean[][] blocked = new boolean[20][10];
        for (int col = 0; col < blocked[0].length; col++) {
            blocked[0][col] = true;
            blocked[1][col] = true;
        }
        GameSnapshot snapshot = new GameSnapshot(
                20,
                10,
                blocked,
                TetrominoType.O,
                List.of(
                        new BoardPosition(0, 4),
                        new BoardPosition(0, 5),
                        new BoardPosition(1, 4),
                        new BoardPosition(1, 5)));

        assertEquals(
                AiPlan.fromPlacement(AiMove.NONE),
                new BuildShapeActionPlanningAgent().plan(snapshot));
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
                        new BoardPosition(1, 5)),
                TetrominoType.I);
    }
}
