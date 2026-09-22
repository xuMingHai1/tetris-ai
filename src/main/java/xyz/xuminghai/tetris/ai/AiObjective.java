/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import java.util.Locale;
import java.util.Objects;

/**
 * High-level intent applied above deterministic movement legality and board rules.
 *
 * <p>Objectives may change which already-reachable plan is preferred, but must never replace
 * {@link ActionStateSearch}, {@link BoardSimulator}, {@link xyz.xuminghai.tetris.core.BoardRules}
 * or live collision checks as the source of movement legality.</p>
 */
public enum AiObjective {

    /**
     * Preserve the existing local survival heuristic.
     */
    SURVIVAL("survival"),

    /**
     * Stay inside the current safety shortlist while preferring setups that let the known preview
     * piece reach a useful action-only landing on the next turn.
     */
    TUCK_HUNTER("tuck-hunter");

    private final String configValue;

    AiObjective(String configValue) {
        this.configValue = configValue;
    }

    public String configValue() {
        return configValue;
    }

    static AiObjective parse(String configured) {
        String normalized = Objects.requireNonNull(configured, "configured")
                .trim()
                .toLowerCase(Locale.ROOT);
        for (AiObjective objective : values()) {
            if (objective.configValue.equals(normalized)) {
                return objective;
            }
        }
        throw new IllegalArgumentException(
                "Unsupported AI objective: " + configured
                        + ". Expected survival or tuck-hunter.");
    }
}
