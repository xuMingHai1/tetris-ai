/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import java.util.Locale;
import java.util.Objects;

/**
 * Named objective-risk profiles used to calibrate how much board-health degradation a
 * non-survival objective may accept relative to SURVIVAL top-1.
 *
 * <p>The adaptive desktop controller may select {@link #STRICT}, {@link #CONSERVATIVE} or
 * {@link #BALANCED} from board danger. {@link #RISKY} remains calibration-only because it permits
 * one additional hole and showed a sharp survival regression in deterministic calibration.</p>
 */
public enum ObjectiveRiskProfile {

    STRICT("strict", new ObjectiveSafetyBudget(0, 0, 2)),
    CONSERVATIVE("conservative", ObjectiveSafetyBudget.conservative()),
    BALANCED("balanced", new ObjectiveSafetyBudget(0, 8, 8)),
    RISKY("risky", new ObjectiveSafetyBudget(1, 8, 8));

    private final String configValue;
    private final ObjectiveSafetyBudget budget;

    ObjectiveRiskProfile(String configValue, ObjectiveSafetyBudget budget) {
        this.configValue = configValue;
        this.budget = budget;
    }

    public String configValue() {
        return configValue;
    }

    public ObjectiveSafetyBudget budget() {
        return budget;
    }

    public static ObjectiveRiskProfile parse(String configured) {
        String normalized = Objects.requireNonNull(configured, "configured")
                .trim()
                .toLowerCase(Locale.ROOT);
        for (ObjectiveRiskProfile profile : values()) {
            if (profile.configValue.equals(normalized)) {
                return profile;
            }
        }
        throw new IllegalArgumentException(
                "Unsupported objective risk profile: " + configured
                        + ". Expected strict, conservative, balanced or risky.");
    }
}
