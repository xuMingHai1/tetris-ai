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

class JevActionPlanningAgentTest {

    @Test
    void mapsTopChoiceBackToDeterministicActionPlan() {
        TypeSafeSystemOneClient client = client(
                """
                {"answers":{"plan":{"type":"choice","choice":"c0","confidence":0.82}},"usage":{"input_tokens":140,"output_tokens":18}}
                """);

        AiPlan plan = new JevActionPlanningAgent(client).plan(snapshot());

        assertEquals(new DeterministicActionPlanningAgent().plan(snapshot()), plan);
    }

    @Test
    void canChooseAnotherReachablePlanWithinSafetyShortlist() {
        List<ActionPlanCandidates.PlannedCandidate> ranked = ActionPlanCandidates.ranked(snapshot());
        TypeSafeSystemOneClient client = client(
                """
                {"answers":{"plan":{"type":"choice","choice":"c4","confidence":0.73}},"usage":{"input_tokens":150,"output_tokens":20}}
                """);

        AtomicReference<JevDecisionObservation> observed = new AtomicReference<>();
        AiPlan plan = new JevActionPlanningAgent(client, observed::set).plan(snapshot());

        assertEquals(ranked.get(4).plan(), plan);
        assertEquals(5, observed.get().selectedRank());
        assertEquals(5, observed.get().candidateCount());
        assertEquals(
                ranked.get(4).placement().holes() - ranked.getFirst().placement().holes(),
                observed.get().holesDelta());
        List<ActionPlanCandidates.PlannedCandidate> shortlist =
                ranked.subList(0, Math.min(JevActionPlanningAgent.MAX_REMOTE_CANDIDATES, ranked.size()));
        ActionPlanProvenance.Classification provenance =
                ActionPlanProvenance.classify(snapshot(), shortlist);
        assertEquals(
                provenance.actionOnlyCandidateCount(),
                observed.get().actionOnlyCandidateCount());
        assertEquals(
                provenance.isActionOnly(ranked.get(4)),
                observed.get().selectedActionOnly());
    }

    @Test
    void sendsExplicitActionsAndPreviewOutlookToProvider() {
        AtomicReference<String> requestBody = new AtomicReference<>();
        TypeSafeSystemOneClient client = client(
                """
                {"answers":{"plan":{"type":"choice","choice":"c0","confidence":0.80}},"usage":{"input_tokens":160,"output_tokens":18}}
                """,
                requestBody);

        new JevActionPlanningAgent(client).plan(snapshotWithNextPiece());

        String body = requestBody.get();
        assertTrue(body.contains("\"actions\""));
        assertTrue(body.contains("\"HARD_DROP\""));
        assertTrue(body.contains("\"action_count\""));
        assertTrue(body.contains("\"next_piece\":\"T\""));
        assertTrue(body.contains("\"next_piece_outlook\""));
        assertFalse(body.contains("legacyPlacementReachable"));
        assertFalse(body.contains("actionOnly"));
        assertFalse(body.contains("placement_reachable"));
    }

    @Test
    void rejectsUnknownChoiceSoExecutorCanFallback() {
        TypeSafeSystemOneClient client = client(
                """
                {"answers":{"plan":{"type":"choice","choice":"invented","confidence":0.75}},"usage":{"input_tokens":120,"output_tokens":16}}
                """);

        assertThrows(
                IllegalStateException.class,
                () -> new JevActionPlanningAgent(client).plan(snapshot()));
    }

    private static TypeSafeSystemOneClient client(String response) {
        return client(response, null);
    }

    private static TypeSafeSystemOneClient client(
            String response,
            AtomicReference<String> capturedBody) {
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
