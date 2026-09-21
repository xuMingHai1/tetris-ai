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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JevTetrisAgentTest {

    @Test
    void mapsReturnedChoiceBackToBestCandidateInHeuristicShortlist() {
        TypeSafeSystemOneClient client = client(
                """
                {"answers":{"move":{"type":"choice","choice":"c0","confidence":0.75}},"usage":{"input_tokens":120,"output_tokens":16}}
                """);

        AiMove move = new JevTetrisAgent(client).decide(snapshot());

        assertEquals(new HeuristicTetrisAgent().decide(snapshot()), move);
    }

    @Test
    void canChooseAnotherCandidateWithinHeuristicShortlist() {
        List<PlacementCandidate> ranked = HeuristicTetrisAgent.rankCandidates(snapshot());
        TypeSafeSystemOneClient client = client(
                """
                {"answers":{"move":{"type":"choice","choice":"c4","confidence":0.75}},"usage":{"input_tokens":120,"output_tokens":16}}
                """);

        AiMove move = new JevTetrisAgent(client).decide(snapshot());

        assertEquals(ranked.get(4).move(), move);
    }

    @Test
    void emitsProviderTelemetryForOnlyTheSafetyShortlist() {
        AtomicReference<JevDecisionObservation> observed = new AtomicReference<>();
        TypeSafeSystemOneClient client = client(
                """
                {"answers":{"move":{"type":"choice","choice":"c0","confidence":0.75}},"usage":{"input_tokens":120,"output_tokens":16}}
                """);

        new JevTetrisAgent(client, observed::set).decide(snapshot());

        assertEquals(0.75, observed.get().confidence(), 0.0001);
        assertEquals(120, observed.get().inputTokens());
        assertEquals(16, observed.get().outputTokens());
        assertEquals(
                Math.min(JevTetrisAgent.MAX_REMOTE_CANDIDATES, BoardSimulator.candidates(snapshot()).size()),
                observed.get().candidateCount());
    }

    @Test
    void rejectsUnknownChoiceSoExecutorCanFallback() {
        TypeSafeSystemOneClient client = client(
                """
                {"answers":{"move":{"type":"choice","choice":"invented","confidence":0.75}},"usage":{"input_tokens":120,"output_tokens":16}}
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
