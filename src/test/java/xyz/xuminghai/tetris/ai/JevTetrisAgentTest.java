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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

        AtomicReference<JevDecisionObservation> observed = new AtomicReference<>();
        AiMove move = new JevTetrisAgent(client, observed::set).decide(snapshot());

        assertEquals(ranked.get(4).move(), move);
        assertEquals(5, observed.get().selectedRank());
        assertEquals(
                ranked.get(4).clearedLines() - ranked.getFirst().clearedLines(),
                observed.get().clearedLinesDelta());
        assertEquals(
                ranked.get(4).aggregateHeight() - ranked.getFirst().aggregateHeight(),
                observed.get().aggregateHeightDelta());
        assertEquals(ranked.get(4).holes() - ranked.getFirst().holes(), observed.get().holesDelta());
        assertEquals(
                ranked.get(4).bumpiness() - ranked.getFirst().bumpiness(),
                observed.get().bumpinessDelta());
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
        assertEquals(1, observed.get().selectedRank());
        assertEquals(0, observed.get().clearedLinesDelta());
        assertEquals(0, observed.get().aggregateHeightDelta());
        assertEquals(0, observed.get().holesDelta());
        assertEquals(0, observed.get().bumpinessDelta());
        assertEquals(0, observed.get().actionOnlyCandidateCount());
        assertFalse(observed.get().selectedActionOnly());
    }

    @Test
    void sendsKnownPreviewPieceAndDeterministicOutlookToProvider() {
        AtomicReference<String> requestBody = new AtomicReference<>();
        TypeSafeSystemOneClient client = client(
                """
                {"answers":{"move":{"type":"choice","choice":"c0","confidence":0.75}},"usage":{"input_tokens":120,"output_tokens":16}}
                """,
                requestBody);

        new JevTetrisAgent(client).decide(snapshotWithNextPiece());

        String body = requestBody.get();
        assertTrue(body.contains("\"next_piece\":\"T\""));
        assertTrue(body.contains("\"next_piece_outlook\""));
        assertTrue(body.contains("\"legal_placements\""));
        assertTrue(body.contains("\"best_local_response_metrics\""));
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
        return client(response, null);
    }

    private static TypeSafeSystemOneClient client(String response, AtomicReference<String> capturedBody) {
        return new TypeSafeSystemOneClient(
                "test-key",
                "jev-latest",
                URI.create("https://example.invalid/systemone"),
                Duration.ofSeconds(1),
                1,
                Duration.ofMillis(1),
                (request, requestBody) -> {
                    if (capturedBody != null) {
                        capturedBody.set(requestBody);
                    }
                    return new TypeSafeSystemOneClient.RawResponse(200, response);
                },
                duration -> {
                });
    }

    private static GameSnapshot snapshotWithNextPiece() {
        GameSnapshot snapshot = snapshot();
        return new GameSnapshot(
                snapshot.rows(),
                snapshot.cols(),
                snapshot.occupied(),
                snapshot.currentType(),
                snapshot.currentCells(),
                TetrominoType.T);
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
