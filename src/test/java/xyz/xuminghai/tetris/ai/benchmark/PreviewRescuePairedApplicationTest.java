/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai.benchmark;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PreviewRescuePairedApplicationTest {

    @Test
    void nearestRankTailIncludesRareSlowWarning() {
        assertEquals(0L, PreviewRescuePairedApplication.p95(List.of()));
        assertEquals(95L, PreviewRescuePairedApplication.p95(
                java.util.stream.LongStream.rangeClosed(1, 100).boxed().toList()));
        assertEquals(100L, PreviewRescuePairedApplication.p95(List.of(1L, 2L, 100L)));
    }
}
