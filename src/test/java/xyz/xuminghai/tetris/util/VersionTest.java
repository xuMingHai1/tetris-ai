/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VersionTest {

    @Test
    void releaseUrlPointsToCurrentRepository() {
        assertEquals("https://github.com/xuMingHai1/tetris-ai/releases", Version.RELEASE_URL);
    }

    @Test
    void semanticVersionComparisonSupportsMultiDigitComponents() {
        assertTrue(Version.compareVersions("v1.10.0", "v1.9.9") > 0);
        assertTrue(Version.compareVersions("v2.0.0", "v1.99.99") > 0);
        assertTrue(Version.compareVersions("v1.0.0", "v1.0.1") < 0);
        assertEquals(0, Version.compareVersions("v1.2.3", "v1.2.3"));
        assertEquals(0, Version.compareVersions("invalid", "v1.2.3"));
    }
}
