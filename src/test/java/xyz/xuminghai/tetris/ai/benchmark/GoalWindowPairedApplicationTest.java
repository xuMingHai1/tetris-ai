/*
 * Copyright (C) 2024-2026 xuMingHai
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai.benchmark;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import xyz.xuminghai.tetris.ai.AiAction;
import xyz.xuminghai.tetris.ai.AiPlan;
import xyz.xuminghai.tetris.ai.BuildShapeActionPlanningAgent;
import xyz.xuminghai.tetris.ai.GoalWindowCandidate;
import xyz.xuminghai.tetris.ai.ShapeProgress;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static xyz.xuminghai.tetris.ai.benchmark.GoalWindowPairedRunner.*;

/** Raw-stream and class-origin checks. No full warmup/population or build certification here. */
class GoalWindowPairedApplicationTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void verifyOnlyNeverStartsGamesAndOutputCannotOverwriteAnEarlierAttempt() throws Exception {
        var output = directory.resolve("verify.jsonl");
        GoalWindowPairedApplication.execute(output, Map.of("test_fixture", true), true,
                sink -> fail("verify-only must not run games"));
        var lines = lines(output);
        assertEquals(List.of("provenance_verified", "verification_complete"), types(lines));
        assertFalse(lines.getLast().get("data").get("population_started").asBoolean());
        var original = Files.readString(output);
        assertThrows(IOException.class, () -> GoalWindowPairedApplication.execute(output, Map.of(), true,
                sink -> fail("existing output must fail before game")));
        assertEquals(original, Files.readString(output));
    }

    @Test
    void missingGamesOrWrongOrderCannotWriteSessionComplete() throws Exception {
        for (boolean wrongOrder : List.of(false, true)) {
            var output = directory.resolve("missing-" + wrongOrder + ".jsonl");
            assertThrows(IllegalStateException.class, () -> GoalWindowPairedApplication.execute(
                    output, Map.of("test_fixture", true), false, sink -> {
                        if (wrongOrder) sink.started(new Invocation(Phase.MEASURED, Arm.CANDIDATE, 20001, 1000));
                    }));
            assertEquals(List.of("provenance_verified", "session_started", "session_incomplete"),
                    types(lines(output)));
        }
    }

    @Test
    void syntheticCompleteStreamRequiresAllExpectedGamesAndSerializesGameSummaries() throws Exception {
        var output = directory.resolve("synthetic-complete.jsonl");
        // Format/state-machine fixture only. No game engine or measured provenance is supplied.
        GoalWindowPairedApplication.execute(output, Map.of("test_fixture", true), false, sink ->
                new GoalWindowPairedRunner(invocation -> {
                    sink.started(invocation);
                    var facts = new ArrayList<GoalWindowGameEvidence.Turn>();
                    for (int index = 1; index <= 10; index++) {
                        var choice = invocation.arm() == Arm.BASELINE ? null : new GoalWindowCandidate.Observation(
                                GoalWindowCandidate.Choice.BASELINE_CHOICE, 2, 0, List.of(), List.of());
                        var turn = new GoalWindowGameEvidence.Turn(index, 1, index * 2L,
                                new ShapeProgress(32, 0, 16, 0, 0),
                                GoalWindowCandidate.terminal(new boolean[20][10]), choice);
                        facts.add(turn);
                        sink.turn(invocation, new GoalWindowGameEvidence.Update(turn, List.of()));
                    }
                    var result = new GameBenchmarkResult(invocation.seed(), invocation.pieceLimit(), 10, 0, 10,
                            0, 0, 10, 1, BoardHealthSummary.empty(), invocation.pieceLimit() == 10);
                    var game = new CompletedGame(invocation, result,
                            new GoalWindowGameEvidence.Snapshot(List.copyOf(facts), List.of(),
                                    "synthetic-format-only", java.util.Collections.nCopies(10, 1L)), 0);
                    sink.completed(game);
                    return game;
                }).run(java.util.stream.LongStream.range(20000, 20080).boxed().toList(), 1000, ignored -> { }));
        var lines = lines(output);
        assertEquals(180, types(lines).stream().filter("game_complete"::equals).count());
        assertEquals(1800, types(lines).stream().filter("turn"::equals).count());
        assertEquals("session_complete", lines.getLast().get("type").asText());
        assertEquals(160, lines.getLast().get("data").get("measured_games").asInt());
    }

    @Test
    void realTurnIsFlushedBeforeNextAgentCallAndIllegalOrAgentFailureRetainsPrefix() throws Exception {
        for (boolean illegal : List.of(false, true)) {
            var output = directory.resolve("failure-" + illegal + ".jsonl");
            var calls = new AtomicInteger();
            var failure = assertThrows(IncompleteRun.class, () -> GoalWindowPairedApplication.execute(
                    output, Map.of("test_fixture", true), false, sink -> runObservedGame(
                            new Invocation(Phase.WARMUP, Arm.BASELINE, 19000, 10), (arm, observer) -> input -> {
                                if (calls.incrementAndGet() == 2) {
                                    try {
                                        var persisted = lines(output).getLast();
                                        assertEquals("turn", persisted.get("type").asText());
                                        assertEquals(1, persisted.get("data").get("decision_fact").get("decision").asInt());
                                    }
                                    catch (IOException problem) { throw new UncheckedIOException(problem); }
                                    if (illegal) return new AiPlan(List.of(AiAction.LEFT)); // Not terminal.
                                    throw new IllegalArgumentException("fixture agent failed");
                                }
                                return new BuildShapeActionPlanningAgent().plan(input);
                            }, sink)));
            assertEquals(illegal ? FailureKind.ILLEGAL_PLAN : FailureKind.AGENT_FAILURE, failure.kind());
            assertEquals(1, failure.prefix().turns().size());
            assertEquals(2, calls.get());
            var lines = lines(output);
            assertEquals(List.of("provenance_verified", "session_started", "game_started", "turn",
                    "game_incomplete", "session_incomplete"), types(lines));
            var turn = lines.get(3).get("data").get("decision_fact");
            assertEquals(failure.prefix().decisionNanos().getFirst().longValue(), turn.get("decision_nanos").asLong());
            assertFalse(turn.get("clean_completion").asBoolean());
            assertNotNull(lines.get(3).get("data").get("actual_q"));
            var incomplete = lines.get(4).get("data");
            assertEquals(1, incomplete.get("primary_failures").asInt());
            assertEquals(illegal ? 1 : 0, incomplete.get("illegal_plans").asInt());
            for (int index = 0; index < lines.size(); index++) {
                assertEquals(index + 1, lines.get(index).get("sequence").asInt());
                assertEquals("goal-window-raw-v1", lines.get(index).get("schema").asText());
            }
        }
    }

    @Test
    void outputFailureIsEvidenceFailureAndNeverPrimaryOrNormalGameOver() throws Exception {
        var output = directory.resolve("output-failure.jsonl");
        var failure = assertThrows(IncompleteRun.class, () -> GoalWindowPairedApplication.execute(
                output, Map.of("test_fixture", true), false, sink -> runObservedGame(
                        new Invocation(Phase.WARMUP, Arm.BASELINE, 19000, 10),
                        (arm, observer) -> new BuildShapeActionPlanningAgent(), new EventSink() {
                            @Override public void started(Invocation game) { sink.started(game); }
                            @Override public void turn(Invocation game, GoalWindowGameEvidence.Update update) {
                                sink.turn(game, update);
                                throw new UncheckedIOException(new IOException("fixture disk failure"));
                            }
                            @Override public void completed(CompletedGame game) { fail("no completed game after output error"); }
                            @Override public void failed(IncompleteRun problem) { sink.failed(problem); }
                        })));
        assertEquals(FailureKind.EVIDENCE_FAILURE, failure.kind());
        assertEquals(0, failure.primaryFailures());
        assertEquals(0, failure.illegalPlans());
        assertEquals(1, failure.prefix().turns().size());
        assertFalse(types(lines(output)).contains("game_complete"));
        assertEquals("session_incomplete", lines(output).getLast().get("type").asText());
    }

    @Test
    void actualLoadedCriticalClassesMustMatchRecordedOriginAndHash() throws Exception {
        var root = Path.of(GoalWindowPairedApplication.class.getProtectionDomain().getCodeSource()
                .getLocation().toURI()).toRealPath().getParent().getParent();
        var hashes = new LinkedHashMap<String, String>();
        for (var suffix : GoalWindowBuildProvenance.CRITICAL) {
            var relative = "xyz/xuminghai/tetris/" + suffix.replace('.', '/') + ".class";
            var path = root.resolve(suffix.contains("GoalWindow") ? "target/test-classes" : "target/classes")
                    .resolve(relative);
            hashes.put(root.relativize(path).toString().replace(java.io.File.separatorChar, '/'),
                    GoalWindowBuildProvenance.sha256(Files.readAllBytes(path)));
        }
        var manifest = mapper.readTree(mapper.writeValueAsString(Map.of("class_files", hashes)));
        var loaded = GoalWindowBuildProvenance.verifyLoaded(root, manifest);
        assertEquals(GoalWindowBuildProvenance.CRITICAL.size(), loaded.size());
        assertTrue(loaded.get("xyz.xuminghai.tetris.ai.GoalWindowCandidate").get("origin").endsWith("test-classes"));
        assertTrue(loaded.get("xyz.xuminghai.tetris.ai.benchmark.HeadlessGameRunner").get("origin").endsWith("classes"));
        hashes.put("target/test-classes/xyz/xuminghai/tetris/ai/GoalWindowCandidate.class", "0".repeat(64));
        var wrong = mapper.readTree(mapper.writeValueAsString(Map.of("class_files", hashes)));
        assertThrows(IllegalStateException.class, () -> GoalWindowBuildProvenance.verifyLoaded(root, wrong));
    }

    @Test
    void wrongManifestDigestOrCheckoutIsRejectedBeforeAnyGameOrVerifierProcess() throws Exception {
        var path = directory.resolve("manifest.json");
        Files.writeString(path, "{}");
        assertThrows(IllegalStateException.class, () -> GoalWindowBuildProvenance.verify(path, "0".repeat(64), new String[0]));
        Files.writeString(path, mapper.writeValueAsString(Map.of("root", directory.toString())));
        var hash = GoalWindowBuildProvenance.sha256(Files.readAllBytes(path));
        assertThrows(IllegalStateException.class, () -> GoalWindowBuildProvenance.verify(path, hash, new String[0]));
        assertThrows(IllegalArgumentException.class, () -> GoalWindowPairedApplication.main(new String[0]));
    }

    private List<JsonNode> lines(Path path) throws IOException {
        var result = new ArrayList<JsonNode>();
        for (var line : Files.readAllLines(path)) result.add(mapper.readTree(line));
        return result;
    }

    private static List<String> types(List<JsonNode> lines) {
        return lines.stream().map(line -> line.get("type").asText()).toList();
    }
}
