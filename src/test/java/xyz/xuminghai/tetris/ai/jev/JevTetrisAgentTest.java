/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai.jev;

import org.junit.jupiter.api.Test;
import xyz.xuminghai.tetris.ai.AiMove;
import xyz.xuminghai.tetris.ai.GameSnapshot;
import xyz.xuminghai.tetris.core.BoardPosition;
import xyz.xuminghai.tetris.core.TetrominoType;
import xyz.xuminghai.tetris.integration.typesafe.TypeSafeChoiceClient;
import xyz.xuminghai.tetris.integration.typesafe.TypeSafeChoiceResult;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JevTetrisAgentTest {

    @Test
    void mapsStructuredChoiceBackToLegalMove() {
        TypeSafeChoiceClient client = (state, instructions, criteria) -> {
            String lineClearingChoice = criteria.entrySet().stream()
                    .filter(entry -> isLineClearingMove(entry.getValue()))
                    .map(Map.Entry::getKey)
                    .findFirst()
                    .orElseThrow();

            assertTrue(state instanceof Map<?, ?>);
            return CompletableFuture.completedFuture(
                    new TypeSafeChoiceResult(
                            "jev-1.13.0",
                            lineClearingChoice,
                            0.91,
                            Map.of(lineClearingChoice, 1.0),
                            200,
                            24));
        };

        GameSnapshot snapshot = lineCompletionSnapshot();
        JevDecision decision = new JevTetrisAgent(client)
                .decideDetailed(snapshot)
                .toCompletableFuture()
                .join();

        assertEquals(new AiMove(0, 3), decision.move());
        assertEquals("jev-1.13.0", decision.model());
        assertEquals(0.91, decision.confidence());
        assertEquals(200, decision.inputTokens());
        assertEquals(24, decision.outputTokens());
    }

    private static GameSnapshot lineCompletionSnapshot() {
        boolean[][] board = new boolean[20][10];
        for (int col = 0; col < 6; col++) {
            board[19][col] = true;
        }
        return new GameSnapshot(
                20,
                10,
                board,
                TetrominoType.I,
                List.of(
                        new BoardPosition(-1, 3),
                        new BoardPosition(-1, 4),
                        new BoardPosition(-1, 5),
                        new BoardPosition(-1, 6)));
    }

    private static boolean isLineClearingMove(Object value) {
        if (!(value instanceof Map<?, ?> candidate)) {
            return false;
        }
        return Integer.valueOf(1).equals(candidate.get("cleared_lines"))
                && Integer.valueOf(3).equals(candidate.get("horizontal_shift"));
    }
}
