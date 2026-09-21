/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TetrisAgentFactoryTest {

    @Test
    void defaultsToLocalHeuristicAgent() {
        assertInstanceOf(HeuristicTetrisAgent.class, TetrisAgentFactory.from(Map.of()));
    }

    @Test
    void createsJevAgentOnlyWhenExplicitlyConfigured() {
        assertInstanceOf(
                JevTetrisAgent.class,
                TetrisAgentFactory.from(Map.of(
                        TetrisAgentFactory.AGENT_ENV, "jev",
                        TetrisAgentFactory.TYPESAFE_API_KEY_ENV, "test-key")));
    }

    @Test
    void failsFastWhenJevApiKeyIsMissing() {
        assertThrows(
                IllegalStateException.class,
                () -> TetrisAgentFactory.from(Map.of(TetrisAgentFactory.AGENT_ENV, "jev")));
    }

    @Test
    void rejectsUnknownAgentMode() {
        assertThrows(
                IllegalArgumentException.class,
                () -> TetrisAgentFactory.from(Map.of(TetrisAgentFactory.AGENT_ENV, "unknown")));
    }
}
