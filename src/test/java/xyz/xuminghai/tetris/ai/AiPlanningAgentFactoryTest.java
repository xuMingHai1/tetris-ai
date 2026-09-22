/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AiPlanningAgentFactoryTest {

    @Test
    void defaultsToPlacementHeuristicAdapter() {
        assertInstanceOf(
                AiPlanningAgent.class,
                AiPlanningAgentFactory.from(Map.of()));
    }

    @Test
    void createsDeterministicActionPlannerWhenExplicitlyConfigured() {
        assertInstanceOf(
                DeterministicActionPlanningAgent.class,
                AiPlanningAgentFactory.from(Map.of(
                        TetrisAgentFactory.AGENT_ENV, "action")));
    }

    @Test
    void createsTuckHunterWhenActionObjectiveIsConfigured() {
        assertInstanceOf(
                TuckHunterActionPlanningAgent.class,
                AiPlanningAgentFactory.from(Map.of(
                        TetrisAgentFactory.AGENT_ENV, "action",
                        AiPlanningAgentFactory.OBJECTIVE_ENV, "tuck-hunter")));
    }

    @Test
    void rejectsTuckHunterObjectiveForPlacementAgent() {
        assertThrows(
                IllegalArgumentException.class,
                () -> AiPlanningAgentFactory.from(Map.of(
                        TetrisAgentFactory.AGENT_ENV, "heuristic",
                        AiPlanningAgentFactory.OBJECTIVE_ENV, "tuck-hunter")));
    }

    @Test
    void rejectsUnknownObjective() {
        assertThrows(
                IllegalArgumentException.class,
                () -> AiPlanningAgentFactory.from(Map.of(
                        TetrisAgentFactory.AGENT_ENV, "action",
                        AiPlanningAgentFactory.OBJECTIVE_ENV, "unknown")));
    }

    @Test
    void createsJevActionPlannerWhenExplicitlyConfigured() {
        assertInstanceOf(
                JevActionPlanningAgent.class,
                AiPlanningAgentFactory.from(Map.of(
                        TetrisAgentFactory.AGENT_ENV, "jev-action",
                        TetrisAgentFactory.TYPESAFE_API_KEY_ENV, "test-key")));
    }

    @Test
    void requiresApiKeyForJevActionPlanner() {
        assertThrows(
                IllegalStateException.class,
                () -> AiPlanningAgentFactory.from(Map.of(
                        TetrisAgentFactory.AGENT_ENV, "jev-action")));
    }

    @Test
    void rejectsUnknownPlanningMode() {
        assertThrows(
                IllegalArgumentException.class,
                () -> AiPlanningAgentFactory.from(Map.of(
                        TetrisAgentFactory.AGENT_ENV, "unknown")));
    }
}
