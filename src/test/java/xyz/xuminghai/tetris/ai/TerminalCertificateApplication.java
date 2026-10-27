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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;

/**
 * Explicit, fixed-budget test-only session producer. Never a default game or preflight mode.
 * Its completion authenticates neither the build nor elapsed time: a parent must bind the
 * observation to a clean-build manifest, supervise the whole JVM and check the original guard.
 */
public final class TerminalCertificateApplication {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private TerminalCertificateApplication() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 3 || !args[0].equals("--collect")) {
            throw new IllegalArgumentException("usage: --collect <checkout-root> <new-external-output>");
        }
        var root = Path.of(args[1]).toRealPath();
        var observation = TerminalCertificatePreflight.inspect(Path.of(args[1]),
                TerminalCertificateApplication.class.getName(), List.of(args));
        var output = Path.of(args[2]).toAbsolutePath();
        validateOutput(root, output);
        var report = run(output, observation, TerminalCertificateGeometry.Domain.heart(),
                TerminalCertificateSession.Limits.registered());
        System.out.println(JSON.writeValueAsString(report));
    }

    static void validateOutput(Path root, Path output) throws IOException {
        if (!output.equals(output.normalize()) || output.startsWith(root)
                || !output.getParent().equals(output.getParent().toRealPath())) {
            throw new IllegalArgumentException("canonical external output required");
        }
    }

    /** Only artificial fixtures supply a smaller domain/limits; the CLI has no such options. */
    static Map<String, Object> run(Path output, Map<String, Object> observation,
                                   TerminalCertificateGeometry.Domain domain,
                                   TerminalCertificateSession.Limits limits) throws IOException {
        // Do not enter the catch before exclusive creation: a prior attempt is immutable.
        Files.createDirectory(output);
        try {
            write(output.resolve("observation.json"), observation);
            write(output.resolve("started.json"), Map.of("schema", "terminal-certificate-cli-v1",
                    "status", "INCOMPLETE", "diagnostic_started", true,
                    "provenance_authenticated", false, "eligible_for_runtime_validation", false));
            var raw = output.resolve("session.jsonl");
            var result = TerminalCertificateJournal.run(raw, domain, limits);
            // This second independent file replay is inside the same supervised JVM budget.
            var checked = TerminalCertificateJournalReader.read(raw);
            if (checked.state() != TerminalCertificateJournalReader.State.COMPLETE
                    || checked.attempts() != result.geometry().decompositions()
                    || checked.enumerations() != result.enumerations()
                    || checked.solverReplays() != result.solverReplays()
                    || checked.checkerReplays() != result.checkerReplays()) {
                throw new IllegalStateException("journal/session result mismatch");
            }
            var report = Map.<String, Object>of("schema", "terminal-certificate-cli-v1",
                    "status", "SESSION_COMPLETE", "search_status", result.status().name(),
                    "journal_state", checked.state().name(), "enumerations", result.enumerations(),
                    "solver_replays", result.solverReplays(), "checker_replays", result.checkerReplays(),
                    "diagnostic_started", true, "provenance_authenticated", false,
                    "eligible_for_runtime_validation", false);
            write(output.resolve("completion.json"), report);
            return report;
        } catch (IOException | RuntimeException | Error failure) {
            try {
                write(output.resolve("error.json"), Map.of("schema", "terminal-certificate-cli-v1",
                        "status", "ERROR", "exception_class", failure.getClass().getName(),
                        "provenance_authenticated", false, "eligible_for_runtime_validation", false));
            } catch (IOException | RuntimeException | Error recordingFailure) {
                failure.addSuppressed(recordingFailure);
            }
            throw failure;
        }
    }

    private static void write(Path path, Map<String, ?> data) throws IOException {
        var bytes = ByteBuffer.wrap((JSON.writeValueAsString(data) + "\n").getBytes(StandardCharsets.UTF_8));
        try (var channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            while (bytes.hasRemaining()) channel.write(bytes);
            channel.force(true);
        }
    }
}
