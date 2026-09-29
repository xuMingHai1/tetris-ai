/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Selects the application-level action planning implementation from environment configuration.
 *
 * <p>Placement-oriented agents remain available through adapters so the live application can move
 * to the action-native contract without removing existing strategies or benchmark baselines.</p>
 */
public final class AiPlanningAgentFactory {

    public static final String OBJECTIVE_ENV = "TETRIS_AI_OBJECTIVE";
    public static final String PREVIEW_RESCUE_ENV = "TETRIS_AI_PREVIEW_RESCUE";

    private AiPlanningAgentFactory() {
    }

    public static AiPlanningAgent fromEnvironment() {
        return from(System.getenv());
    }

    /** Attaches preview-rescue observations when that opt-in planner is selected. */
    public static AiPlanningAgent fromEnvironment(
            Consumer<PreviewRescueActionPlanningAgent.Observation> observer) {
        return from(System.getenv(), observer);
    }

    static AiPlanningAgent from(Map<String, String> environment) {
        return from(environment, ignored -> {
        });
    }

    static AiPlanningAgent from(Map<String, String> environment,
            Consumer<PreviewRescueActionPlanningAgent.Observation> observer) {
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(observer, "observer");
        String configured = environment
                .getOrDefault(TetrisAgentFactory.AGENT_ENV, "heuristic")
                .trim()
                .toLowerCase(Locale.ROOT);
        AiObjective objective = AiObjective.parse(
                environment.getOrDefault(OBJECTIVE_ENV, AiObjective.SURVIVAL.configValue()));
        boolean previewRescue = switch (environment.getOrDefault(PREVIEW_RESCUE_ENV, "false")
                .trim().toLowerCase(Locale.ROOT)) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalArgumentException(
                    PREVIEW_RESCUE_ENV + " must be true or false");
        };

        if (objective != AiObjective.SURVIVAL && !"action".equals(configured)) {
            throw new IllegalArgumentException(
                    OBJECTIVE_ENV + "=" + objective.configValue()
                            + " currently requires "
                            + TetrisAgentFactory.AGENT_ENV + "=action");
        }
        if (previewRescue && (!"action".equals(configured) || objective != AiObjective.BUILD_SHAPE)) {
            throw new IllegalArgumentException(PREVIEW_RESCUE_ENV
                    + "=true requires " + TetrisAgentFactory.AGENT_ENV
                    + "=action and " + OBJECTIVE_ENV + "=build-shape");
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
                case BUILD_SHAPE -> previewRescue
                        ? new PreviewRescueActionPlanningAgent(observer)
                        : new BuildShapeActionPlanningAgent();
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
