/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Selects the application-level action planning implementation from environment configuration.
 *
 * <p>Placement-oriented agents remain available through adapters so the live application can move
 * to the action-native contract without removing existing strategies or benchmark baselines.</p>
 */
public final class AiPlanningAgentFactory {

    public static final String OBJECTIVE_ENV = "TETRIS_AI_OBJECTIVE";

    private AiPlanningAgentFactory() {
    }

    public static AiPlanningAgent fromEnvironment() {
        return from(System.getenv());
    }

    static AiPlanningAgent from(Map<String, String> environment) {
        Objects.requireNonNull(environment, "environment");
        String configured = environment
                .getOrDefault(TetrisAgentFactory.AGENT_ENV, "heuristic")
                .trim()
                .toLowerCase(Locale.ROOT);
        AiObjective objective = AiObjective.parse(
                environment.getOrDefault(OBJECTIVE_ENV, AiObjective.SURVIVAL.configValue()));

        if (objective != AiObjective.SURVIVAL && !"action".equals(configured)) {
            throw new IllegalArgumentException(
                    OBJECTIVE_ENV + "=" + objective.configValue()
                            + " currently requires "
                            + TetrisAgentFactory.AGENT_ENV + "=action");
        }

        return switch (configured) {
            case "", "heuristic" ->
                    AiPlanningAgent.fromPlacementAgent(new HeuristicTetrisAgent());
            case "jev" ->
                    AiPlanningAgent.fromPlacementAgent(
                            new JevTetrisAgent(requireApiKey(environment, configured)));
            case "action" -> switch (objective) {
                case SURVIVAL -> new DeterministicActionPlanningAgent();
                case TUCK_HUNTER -> new TuckHunterActionPlanningAgent();
            };
            case "jev-action" ->
                    new JevActionPlanningAgent(requireApiKey(environment, configured));
            default -> throw new IllegalArgumentException(
                    "Unsupported " + TetrisAgentFactory.AGENT_ENV + " value: " + configured
                            + ". Expected heuristic, jev, action or jev-action.");
        };
    }

    private static String requireApiKey(Map<String, String> environment, String configured) {
        String apiKey = environment.get(TetrisAgentFactory.TYPESAFE_API_KEY_ENV);
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    TetrisAgentFactory.TYPESAFE_API_KEY_ENV
                            + " is required when "
                            + TetrisAgentFactory.AGENT_ENV
                            + "="
                            + configured);
        }
        return apiKey;
    }
}
