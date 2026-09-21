/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AiModeTest {

    @Test
    void cyclesManualHeuristicJevAndBackToManual() {
        assertEquals(AiMode.HEURISTIC, AiMode.MANUAL.next());
        assertEquals(AiMode.JEV, AiMode.HEURISTIC.next());
        assertEquals(AiMode.MANUAL, AiMode.JEV.next());
    }
}
