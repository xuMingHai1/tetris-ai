/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ObjectiveRiskProfileTest {

    @Test
    void exposesExpectedCalibrationBudgets() {
        assertEquals(
                new ObjectiveSafetyBudget(0, 0, 2),
                ObjectiveRiskProfile.STRICT.budget());
        assertEquals(
                ObjectiveSafetyBudget.conservative(),
                ObjectiveRiskProfile.CONSERVATIVE.budget());
        assertEquals(
                new ObjectiveSafetyBudget(0, 8, 8),
                ObjectiveRiskProfile.BALANCED.budget());
        assertEquals(
                new ObjectiveSafetyBudget(1, 8, 8),
                ObjectiveRiskProfile.RISKY.budget());
    }

    @Test
    void parsesConfiguredProfileCaseInsensitively() {
        assertEquals(
                ObjectiveRiskProfile.BALANCED,
                ObjectiveRiskProfile.parse(" BALANCED "));
    }

    @Test
    void rejectsUnknownProfile() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ObjectiveRiskProfile.parse("unbounded"));
    }
}
