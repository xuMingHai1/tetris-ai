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
 * Selects the application AI implementation from explicit environment configuration.
 */
public final class TetrisAgentFactory {

    public static final String AGENT_ENV = "TETRIS_AI_AGENT";
    public static final String TYPESAFE_API_KEY_ENV = "TYPESAFE_API_KEY";

    private TetrisAgentFactory() {
    }

    /**
     * Creates the configured agent. The default remains the local heuristic implementation.
     */
    public static TetrisAgent fromEnvironment() {
        return from(System.getenv());
    }

    static TetrisAgent from(Map<String, String> environment) {
        Objects.requireNonNull(environment, "environment");
        String configured = environment.getOrDefault(AGENT_ENV, "heuristic").trim().toLowerCase(Locale.ROOT);
        return switch (configured) {
            case "", "heuristic" -> new HeuristicTetrisAgent();
            case "jev" -> new JevTetrisAgent(requireApiKey(environment));
            default -> throw new IllegalArgumentException(
                    "Unsupported " + AGENT_ENV + " value: " + configured + ". Expected heuristic or jev.");
        };
    }

    private static String requireApiKey(Map<String, String> environment) {
        String apiKey = environment.get(TYPESAFE_API_KEY_ENV);
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    TYPESAFE_API_KEY_ENV + " is required when " + AGENT_ENV + "=jev");
        }
        return apiKey;
    }
}
