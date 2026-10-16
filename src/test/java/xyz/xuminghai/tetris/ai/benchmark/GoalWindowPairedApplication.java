/*
 * Copyright (C) 2024-2026 xuMingHai
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai.benchmark;

import tools.jackson.databind.ObjectMapper;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;

/**
 * Test-classpath-only raw JSONL entry. No environment strategy switches or partial population mode.
 * Workflow/driver acceptance is still required before the fixed population may be collected.
 */
public final class GoalWindowPairedApplication {
    private GoalWindowPairedApplication() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 3 && args.length != 4) {
            throw new IllegalArgumentException("usage: <manifest> <manifest-sha256> <new-output.jsonl> [--verify-only]");
        }
        if (args.length == 4 && !args[3].equals("--verify-only")) {
            throw new IllegalArgumentException("only --verify-only is accepted as fourth argument");
        }
        var verified = GoalWindowBuildProvenance.verify(Path.of(args[0]), args[1], args);
        execute(Path.of(args[2]), Map.of("manifest_sha256", verified.manifestSha256(),
                "manifest", verified.manifest(), "loaded_classes", verified.loadedClasses()),
                args.length == 4, sink -> GoalWindowPairedRunner.runSession(
                        LongStream.range(20000, 20080).boxed().toList(), 1000, sink));
    }

    /** Session seam for failure/output tests; main always uses the entire frozen population. */
    static void execute(Path output, Map<String, ?> provenance, boolean verifyOnly, Session session) throws IOException {
        try (var sink = new JsonLines(output)) {
            sink.write("provenance_verified", provenance);
            if (verifyOnly) {
                sink.write("verification_complete", Map.of("population_started", false));
                return;
            }
            sink.write("session_started", Map.of("expected_warmup_games", 20, "expected_measured_games", 160,
                    "timing_scope", "agent.plan-through-validated-terminal-placement"));
            try {
                session.run(sink);
                sink.requireFullSession();
                sink.write("session_complete", Map.of("warmup_games", 20, "measured_games", 160));
            }
            catch (RuntimeException failure) {
                sink.write("session_incomplete", Map.of("causes", causes(failure)));
                throw failure;
            }
        }
    }

    @FunctionalInterface
    interface Session { void run(GoalWindowPairedRunner.EventSink sink); }

    private static List<String> causes(Throwable failure) {
        var result = new ArrayList<String>();
        for (var cause = failure; cause != null; cause = cause.getCause()) result.add(cause.toString());
        return List.copyOf(result);
    }

    private static Map<String, Object> identity(GoalWindowPairedRunner.Invocation game) {
        return Map.of("phase", game.phase().name(), "arm", game.arm().name(),
                "seed", game.seed(), "pieceLimit", game.pieceLimit());
    }

    private static List<Map<String, Object>> predictions(List<GoalWindowGameEvidence.Prediction> values) {
        return values.stream().map(p -> Map.<String, Object>of("setupDecision", p.setupDecision(),
                "nextValidatedDecision", p.nextValidatedDecision(), "resolution", p.resolution().name())).toList();
    }

    /** Flush every line so a killed JVM retains its last fully written turn. */
    static final class JsonLines implements GoalWindowPairedRunner.EventSink, AutoCloseable {
        private final BufferedWriter writer;
        private final ObjectMapper mapper = new ObjectMapper();
        private int sequence;
        private int completed;
        private GoalWindowPairedRunner.Invocation current;
        private int decisions;
        private boolean failed;

        JsonLines(Path output) throws IOException {
            writer = Files.newBufferedWriter(output, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        }

        void write(String type, Object data) {
            try {
                writer.write(mapper.writeValueAsString(Map.of("schema", "goal-window-raw-v1",
                        "sequence", ++sequence, "type", type, "data", data)));
                writer.newLine();
                writer.flush();
            }
            catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        }

        @Override
        public void started(GoalWindowPairedRunner.Invocation invocation) {
            require(!failed && current == null && completed < 180 && invocation.equals(expected(completed)),
                    "missing/duplicate/out-of-order game");
            current = invocation;
            decisions = 0;
            write("game_started", identity(invocation));
        }

        @Override
        public void turn(GoalWindowPairedRunner.Invocation invocation, GoalWindowGameEvidence.Update update) {
            require(!failed && invocation.equals(current) && update.turn().decision() == decisions + 1
                    && decisions < invocation.pieceLimit(), "missing/out-of-order turn");
            decisions++;
            var turn = update.turn();
            var progress = turn.actual();
            var data = new LinkedHashMap<String, Object>();
            data.put("game", identity(invocation));
            data.put("decision_fact", Map.of("decision", turn.decision(), "decision_nanos", turn.decisionNanos(),
                    "selected_matched_required_cells", progress.matchedRequiredCells(),
                    "selected_forbidden_occupied_cells", progress.forbiddenOccupiedCells(),
                    "selected_visual_error_cells", progress.visualErrorCells(),
                    "clean_completion", progress.cleanCompletion()));
            data.put("elapsed_game_nanos", turn.elapsedGameNanos());
            data.put("actual_progress", progress);
            data.put("actual_q", turn.actualQ());
            data.put("q_accepted", turn.actualQ().accepted());
            data.put("candidate_choice", turn.candidateChoice()); // null is explicit on baseline.
            data.put("resolved_predictions", predictions(update.resolvedPredictions()));
            write("turn", data);
        }

        @Override
        public void completed(GoalWindowPairedRunner.CompletedGame game) {
            require(!failed && game.invocation().equals(current) && game.result().decisions() == decisions
                    && game.evidence().turns().size() == decisions, "game evidence count mismatch");
            if (current.phase() == GoalWindowPairedRunner.Phase.WARMUP) {
                require(decisions == 10, "incomplete warmup");
            }
            // Turn facts already streamed. End retains exact summaries and unresolved predictions.
            write("game_complete", Map.of("game", identity(current), "result", game.result(),
                    "illegal_plans", game.illegalPlans(), "completion_csv", game.evidence().completionCsv(),
                    "predictions", predictions(game.evidence().predictions())));
            completed++;
            current = null;
        }

        @Override
        public void failed(GoalWindowPairedRunner.IncompleteRun failure) {
            failed = true;
            write("game_incomplete", Map.of("game", identity(failure.invocation()), "kind", failure.kind().name(),
                    "primary_failures", failure.primaryFailures(), "illegal_plans", failure.illegalPlans(),
                    "recorded_prefix_decisions", failure.prefix().turns().size(),
                    "completion_csv", failure.prefix().completionCsv(),
                    "predictions", predictions(failure.prefix().predictions()), "causes", causes(failure)));
        }

        void requireFullSession() {
            require(!failed && current == null && completed == 180, "INCOMPLETE session: missing games");
        }

        private static GoalWindowPairedRunner.Invocation expected(int index) {
            if (index < 20) {
                return new GoalWindowPairedRunner.Invocation(GoalWindowPairedRunner.Phase.WARMUP,
                        GoalWindowPairedRunner.Arm.values()[index / 10], 19000 + index % 10, 10);
            }
            int offset = index - 20;
            long seed = 20000 + offset / 2;
            var arm = GoalWindowPairedRunner.Arm.values()[(int) ((seed % 2 + offset % 2) % 2)];
            return new GoalWindowPairedRunner.Invocation(GoalWindowPairedRunner.Phase.MEASURED, arm, seed, 1000);
        }

        private static void require(boolean condition, String message) {
            if (!condition) throw new IllegalStateException(message);
        }

        @Override
        public void close() throws IOException { writer.close(); }
    }
}
