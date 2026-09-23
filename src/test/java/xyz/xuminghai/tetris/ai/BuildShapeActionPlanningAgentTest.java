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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuildShapeActionPlanningAgentTest {

    @Test
    void selectedPlanStaysInsideRiskBudgetAndDoesNotRegressVisualProgress() {
        GameSnapshot snapshot = emptySnapshot();
        AtomicReference<BuildShapeDecisionObservation> observed = new AtomicReference<>();

        AiPlan selected =
                new BuildShapeActionPlanningAgent(ShapeTarget.HEART, observed::set).plan(snapshot);

        BuildShapeDecisionObservation observation = observed.get();
        assertNotNull(observation);

        List<ActionPlanCandidates.PlannedCandidate> ranked =
                ActionPlanCandidates.ranked(snapshot);
        assertTrue(ranked.size() > BuildShapeActionPlanningAgent.MAX_CURRENT_CANDIDATES);
        List<ActionPlanCandidates.PlannedCandidate> shortlist =
                ranked.subList(
                        0,
                        Math.min(
                                BuildShapeActionPlanningAgent.MAX_CURRENT_CANDIDATES,
                                ranked.size()));

        assertEquals(ranked.size(), observation.reachableCandidateCount());
        assertEquals(shortlist.size(), observation.candidateCount());
        assertTrue(observation.selectedRank() <= BuildShapeActionPlanningAgent.MAX_CURRENT_CANDIDATES);

        ActionPlanCandidates.PlannedCandidate selectedCandidate = shortlist.stream()
                .filter(candidate -> candidate.plan().equals(selected))
                .findFirst()
                .orElseThrow();

        ObjectiveRiskController.Decision riskDecision =
                ObjectiveRiskController.adaptive().decide(shortlist.getFirst().placement());
        ObjectiveSafetyBudget safetyBudget = riskDecision.profile().budget();
        ObjectiveSafetyBudget.Assessment safety =
                safetyBudget.assess(shortlist.getFirst().placement(), selectedCandidate.placement());
        long expectedEligible = shortlist.stream()
                .filter(candidate -> safetyBudget
                        .assess(shortlist.getFirst().placement(), candidate.placement())
                        .allowed())
                .count();

        assertEquals(expectedEligible, observation.safetyEligibleCandidates());
        assertTrue(safety.allowed());
        assertEquals(riskDecision.level(), observation.riskLevel());
        assertEquals(riskDecision.profile(), observation.riskProfile());
        assertFalse(observation.creativeSuppressedByDanger());
        assertTrue(observation.selectedProgress().netScore()
                >= observation.baselineProgress().netScore());
        if (observation.selectedProgress().netScore()
                == observation.baselineProgress().netScore()) {
            assertTrue(observation.selectedProgress().forbiddenOccupiedCells()
                    <= observation.baselineProgress().forbiddenOccupiedCells());
        }
    }

    @Test
    void previewLookaheadStaysInsideCurrentQualityAndSafetyBoundaries() {
        GameSnapshot snapshot = emptySnapshot();
        AtomicReference<BuildShapeDecisionObservation> observed = new AtomicReference<>();

        AiPlan selected = BuildShapeActionPlanningAgent.previewLookahead(
                ShapeTarget.HEART,
                observed::set).plan(snapshot);

        BuildShapeDecisionObservation observation = observed.get();
        assertNotNull(observation);

        List<ActionPlanCandidates.PlannedCandidate> ranked =
                ActionPlanCandidates.ranked(snapshot);
        List<ActionPlanCandidates.PlannedCandidate> shortlist =
                ranked.subList(
                        0,
                        Math.min(
                                BuildShapeActionPlanningAgent.MAX_CURRENT_CANDIDATES,
                                ranked.size()));
        ActionPlanCandidates.PlannedCandidate selectedCandidate = shortlist.stream()
                .filter(candidate -> candidate.plan().equals(selected))
                .findFirst()
                .orElseThrow();

        ObjectiveRiskController.Decision riskDecision =
                ObjectiveRiskController.adaptive().decide(shortlist.getFirst().placement());
        ObjectiveSafetyBudget.Assessment safety = riskDecision.profile().budget()
                .assess(shortlist.getFirst().placement(), selectedCandidate.placement());

        assertTrue(safety.allowed());
        assertTrue(observation.selectedRank() <= BuildShapeActionPlanningAgent.MAX_CURRENT_CANDIDATES);
        assertEquals(selectedCandidate.plan(), selected);
    }

    @Test
    void previewLookaheadMatchesGreedyPlannerWhenPreviewPieceIsUnknown() {
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

        AiPlan greedy = new BuildShapeActionPlanningAgent().plan(snapshot);
        AiPlan preview = BuildShapeActionPlanningAgent.previewLookahead(
                ShapeTarget.HEART,
                ignored -> {
                }).plan(snapshot);

        assertEquals(greedy, preview);
    }

    @Test
    void previewLookaheadDangerStateReturnsExactSurvivalTopChoice() {
        GameSnapshot snapshot = dangerSnapshot();
        AtomicReference<BuildShapeDecisionObservation> observed = new AtomicReference<>();
        List<ActionPlanCandidates.PlannedCandidate> ranked =
                ActionPlanCandidates.ranked(snapshot);

        AiPlan selected = BuildShapeActionPlanningAgent.previewLookahead(
                ShapeTarget.HEART,
                observed::set).plan(snapshot);

        assertEquals(ranked.getFirst().plan(), selected);
        BuildShapeDecisionObservation observation = observed.get();
        assertNotNull(observation);
        assertEquals(ObjectiveRiskController.RiskLevel.DANGER, observation.riskLevel());
        assertTrue(observation.creativeSuppressedByDanger());
        assertEquals(1, observation.selectedRank());
        assertEquals(observation.baselineProgress(), observation.selectedProgress());
    }

    @Test
    void dangerStateReturnsExactSurvivalTopChoice() {
        GameSnapshot snapshot = dangerSnapshot();
        AtomicReference<BuildShapeDecisionObservation> observed = new AtomicReference<>();
        List<ActionPlanCandidates.PlannedCandidate> ranked =
                ActionPlanCandidates.ranked(snapshot);

        AiPlan selected =
                new BuildShapeActionPlanningAgent(ShapeTarget.HEART, observed::set).plan(snapshot);

        assertEquals(ranked.getFirst().plan(), selected);
        BuildShapeDecisionObservation observation = observed.get();
        assertNotNull(observation);
        assertEquals(ObjectiveRiskController.RiskLevel.DANGER, observation.riskLevel());
        assertTrue(observation.creativeSuppressedByDanger());
        assertEquals(1, observation.selectedRank());
        assertEquals(observation.baselineProgress(), observation.selectedProgress());
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

    private static GameSnapshot emptySnapshot() {
        return snapshot(new boolean[20][10]);
    }

    private static GameSnapshot dangerSnapshot() {
        boolean[][] board = new boolean[20][10];
        // A settled cell at row 5 creates max column height 15 / headroom 5 on a 20-row board,
        // which is the controller's explicit DANGER boundary without blocking the spawn area.
        board[5][0] = true;
        return snapshot(board);
    }

    private static GameSnapshot snapshot(boolean[][] board) {
        return new GameSnapshot(
                20,
                10,
                board,
                TetrominoType.T,
                List.of(
                        new BoardPosition(0, 4),
                        new BoardPosition(1, 3),
                        new BoardPosition(1, 4),
                        new BoardPosition(1, 5)),
                TetrominoType.I);
    }
}
