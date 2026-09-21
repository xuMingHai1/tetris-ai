/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import org.junit.jupiter.api.Test;
import xyz.xuminghai.tetris.core.BoardPosition;
import xyz.xuminghai.tetris.core.TetrominoType;

import java.net.URI;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JevTetrisAgentTest {

    @Test
    void mapsReturnedChoiceBackToDeterministicCandidate() {
        TypeSafeSystemOneClient client = client(
                """
                {"answers":{"move":{"type":"choice","choice":"c0","confidence":0.75}}}
                """);

        AiMove move = new JevTetrisAgent(client).decide(snapshot());

        assertEquals(new AiMove(0, -3), move);
    }

    @Test
    void rejectsUnknownChoiceSoExecutorCanFallback() {
        TypeSafeSystemOneClient client = client(
                """
                {"answers":{"move":{"type":"choice","choice":"invented","confidence":0.75}}}
                """);

        assertThrows(IllegalStateException.class, () -> new JevTetrisAgent(client).decide(snapshot()));
    }

    private static TypeSafeSystemOneClient client(String response) {
        return new TypeSafeSystemOneClient(
                "test-key",
                "jev-latest",
                URI.create("https://example.invalid/systemone"),
                Duration.ofSeconds(1),
                1,
                Duration.ofMillis(1),
                (request, requestBody) -> new TypeSafeSystemOneClient.RawResponse(200, response),
                duration -> {
                });
    }

    private static GameSnapshot snapshot() {
        return new GameSnapshot(
                20,
                10,
                new boolean[20][10],
                TetrominoType.I,
                List.of(
                        new BoardPosition(-1, 3),
                        new BoardPosition(-1, 4),
                        new BoardPosition(-1, 5),
                        new BoardPosition(-1, 6)));
    }
}
