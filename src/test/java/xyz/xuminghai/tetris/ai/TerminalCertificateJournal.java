/*
 * Copyright (C) 2024-2026 xuMingHai
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Exclusive, forced JSONL evidence for a synchronous session. No CLI or provenance claim.
 * A missing final record (including a torn last line) always means an incomplete session.
 */
final class TerminalCertificateJournal implements AutoCloseable {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final FileChannel output;

    private TerminalCertificateJournal(FileChannel output) { this.output = output; }

    /** Fixtures can lower budgets; this method does not grant permission to run HEART. */
    static TerminalCertificateSession.Result run(Path path, TerminalCertificateGeometry.Domain domain,
                                                TerminalCertificateSession.Limits limits) throws IOException {
        try (var journal = new TerminalCertificateJournal(FileChannel.open(path,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE))) {
            try {
                journal.append("start", Map.of("state", "INCOMPLETE", "domain", Map.of(
                        "rows", domain.rows(), "cols", domain.cols(),
                        "required", domain.required().toString(16), "allowed", domain.allowed().toString(16)),
                        "limits", Map.of("nodes", limits.nodes(), "decompositions", limits.decompositions(),
                                "enumerations", limits.enumerations(), "steps", limits.steps())));
                var result = TerminalCertificateSession.search(domain, limits, attempt -> {
                    try {
                        journal.attempt(attempt);
                    } catch (IOException failure) {
                        throw new java.io.UncheckedIOException(failure);
                    }
                });
                journal.append("finish", Map.of("state", "SESSION_COMPLETE", "search_status", result.status().name(),
                        "geometry", Map.of("nodes", result.geometry().nodes(),
                                "decompositions", result.geometry().decompositions(),
                                "stop", result.geometry().stop().name()),
                        "enumerations", result.enumerations(), "solver_replays", result.solverReplays(),
                        "checker_replays", result.checkerReplays(),
                        "enumeration_limit_reached", result.enumerationLimitReached(),
                        "certificate_decomposition", result.certificate().map(
                                TerminalCertificateSession.Attempt::decomposition).orElse(0)));
                return result;
            } catch (IOException | RuntimeException | Error failure) {
                // Best effort only: a failed output device may preserve just the earlier prefix.
                // Never replace the primary failure, nor write a normal miss after an exception.
                try {
                    journal.append("error", Map.of("state", "ERROR", "exception_class", failure.getClass().getName()));
                } catch (IOException | RuntimeException | Error recordingFailure) {
                    failure.addSuppressed(recordingFailure);
                }
                throw failure;
            }
        }
    }

    private void attempt(TerminalCertificateSession.Attempt attempt) throws IOException {
        var order = attempt.order();
        var data = new LinkedHashMap<String, Object>();
        data.put("decomposition", attempt.decomposition());
        data.put("order_stop", order.stop().name());
        data.put("enumerations", order.enumerations());
        data.put("solver_replays", order.replays());
        data.put("steps", order.order().map(TerminalCertificateRecords::value).orElse(null));
        data.put("facts", order.facts().stream().map(fact -> Map.of(
                "index", fact.index(), "bag_before", fact.bagBefore(), "bag_after", fact.bagAfter(),
                "survival_rank", fact.survivalRank(), "ordinary_allowed", fact.ordinaryAllowed(),
                "original_guard", guard(fact.originalGuard()),
                "resulting_facts", terminal(fact.resultingFacts()))).toList());
        data.put("checked", attempt.checked().map(checked -> Map.of(
                "replays", checked.replays(), "final_board", checked.finalBoard().toString(16),
                "terminal", terminal(checked.terminal()))).orElse(null));
        append("attempt", data);
    }

    private static Map<String, Object> guard(GoalWindowCandidate.Guard guard) {
        return Map.of("outside_top_five", guard.outsideTopFive(), "danger", guard.danger(),
                "budget_allowed", guard.budgetAllowed(), "holes_delta", guard.holesDelta(),
                "aggregate_height_delta", guard.aggregateHeightDelta(), "bumpiness_delta", guard.bumpinessDelta(),
                "risk", guard.risk(), "profile", guard.profile());
    }

    private static Map<String, Object> terminal(GoalWindowCandidate.Terminal terminal) {
        return Map.of("clean", terminal.clean(), "matched_required", terminal.matchedRequired(),
                "forbidden_occupied", terminal.forbiddenOccupied(), "max_height", terminal.maxHeight(),
                "raw_holes", terminal.rawHoles(), "required_holes", terminal.requiredHoles(),
                "support_holes", terminal.supportHoles(), "outside_holes", terminal.outsideHoles(),
                "forbidden_holes", terminal.rawHoles() - terminal.requiredHoles()
                        - terminal.supportHoles() - terminal.outsideHoles(), "q", terminal.accepted());
    }

    private void append(String event, Map<String, ?> data) throws IOException {
        var record = Map.of("schema", "terminal-certificate-session-v1", "event", event, "data", data,
                "provenance_authenticated", false, "eligible_for_runtime_validation", false);
        var bytes = ByteBuffer.wrap((JSON.writeValueAsString(record) + "\n").getBytes(StandardCharsets.UTF_8));
        while (bytes.hasRemaining()) output.write(bytes);
        // Each complete event survives normal process failure without relying on close/finish.
        output.force(true);
    }

    @Override
    public void close() throws IOException { output.close(); }
}
