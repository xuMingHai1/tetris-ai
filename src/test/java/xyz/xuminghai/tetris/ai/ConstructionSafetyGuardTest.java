/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConstructionSafetyGuardTest {

    @Test
    void preserveBaselineRequiresFullPreviewCapacity() {
        ConstructionSafetyGuard.Profile profile =
                ConstructionSafetyGuard.Profile.PRESERVE_BASELINE;

        assertTrue(profile.allows(10, 10));
        assertTrue(profile.allows(10, 12));
        assertFalse(profile.allows(10, 9));
        assertFalse(profile.allows(10, 0));
    }

    @Test
    void retainHalfAllowsExactlyHalfOrMore() {
        ConstructionSafetyGuard.Profile profile =
                ConstructionSafetyGuard.Profile.RETAIN_HALF;

        assertTrue(profile.allows(10, 5));
        assertTrue(profile.allows(9, 5));
        assertFalse(profile.allows(10, 4));
        assertFalse(profile.allows(9, 4));
        assertFalse(profile.allows(10, 0));
    }

    @Test
    void anyContinuationOnlyRejectsZeroContinuation() {
        ConstructionSafetyGuard.Profile profile =
                ConstructionSafetyGuard.Profile.ANY_CONTINUATION;

        assertTrue(profile.allows(50, 1));
        assertTrue(profile.allows(0, 1));
        assertFalse(profile.allows(50, 0));
    }
}
