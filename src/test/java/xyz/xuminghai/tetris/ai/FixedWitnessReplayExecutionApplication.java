/*
 * Copyright (C) 2024-2026 xuMingHai
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import tools.jackson.databind.json.JsonMapper;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Explicit fixed-action ordinary JVM: metadata-only READY, bound ACK, one replay, saved report.
 * The parent must independently authenticate build/request receipts and permanently reserve
 * all four allowances from one root. This cooperative transport grants no historical session,
 * provenance, network isolation or runtime permission; no historical input is supplied here.
 */
public final class FixedWitnessReplayExecutionApplication {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String SCHEMA = "fixed-witness-replay-execution-v1";

    private FixedWitnessReplayExecutionApplication() { }

    public static void main(String[] args) throws Exception {
        requireArguments(args);
        long deadline = System.nanoTime() + Duration.ofSeconds(120).toNanos();
        require(System.getProperty("os.name").equals("Linux") && Runtime.version().feature() == 27,
                "Linux JDK27 required");
        Path root = canonical(Path.of(args[1]));
        Path output = Path.of(args[2]);
        Path input = canonical(Path.of(args[8]));
        Path home = canonical(Path.of(System.getProperty("user.home")));
        Path temporary = canonical(Path.of(System.getProperty("java.io.tmpdir")));
        FixedWitnessReplayApplication.validateOutput(root, output);
        FixedWitnessReportApplication.validateDirectories(root, output, input, home, temporary);
        validateCommand(args, home, temporary);
        // No request/input content is consumed until ACK, including malformed content.
        var snapshot = FixedWitnessReplayDescriptor.inspect(input);
        var handshake = new FixedWitnessReplayApplication.ArtifactQuota(Integer.parseInt(args[4]));
        Files.createDirectory(output);
        var observed = FixedWitnessJvmObservation.inspectExecution(root);
        var readyFields = fields(args);
        readyFields.putAll(Map.of("state", "READY", "nonce", args[3], "observation", observed,
                "actions_started", false));
        byte[] ready = json(readyFields);
        handshake.write(output.resolve("ready.json"), ready);
        FixedWitnessReplayApplication.acknowledge(System.in, args[3], FixedWitnessInput.sha256(ready),
                Duration.ofNanos(Math.max(1, deadline - System.nanoTime())));
        check(root, snapshot, observed, deadline);
        var request = snapshot.read(args[9]);
        check(root, snapshot, observed, deadline);
        // This is the only action call. Any failure escapes; nothing retries or repairs a prefix.
        replay(request, output, new FixedWitnessReplay.JournalQuota(Integer.parseInt(args[5])));
        check(root, snapshot, observed, deadline);
        var artifacts = report(request, output, Integer.parseInt(args[5]),
                new FixedWitnessReplay.JournalQuota(Integer.parseInt(args[6])),
                new FixedWitnessReplay.JournalQuota(Integer.parseInt(args[7])), Integer.parseInt(args[7]));
        check(root, snapshot, observed, deadline);
        var terminal = fields(args);
        terminal.putAll(artifacts);
        terminal.putAll(Map.of("state", "REPLAY_REPORTED", "actions_started", true,
                "ready_sha256", FixedWitnessInput.sha256(ready)));
        handshake.write(output.resolve("replayed.json"), json(terminal));
    }

    static void requireArguments(String[] args) {
        if (args.length != 10 || !args[0].equals("--replay-fixed")
                || !args[3].matches("[0-9a-f]{64}") || !args[9].matches("[0-9a-f]{64}")
                || !positive(args[4], 2 * 1024 * 1024) || !positive(args[5], 10 * 1024 * 1024)
                || !positive(args[6], 16 * 1024) || !positive(args[7], 10 * 1024 * 1024)) {
            throw new IllegalArgumentException("usage: --replay-fixed <checkout> <new-output> <nonce>"
                    + " <handshake-bytes> <journal-bytes> <declaration-bytes> <report-bytes>"
                    + " <request-directory> <expected-request-sha256>");
        }
        long total = 0;
        for (int index = 4; index <= 7; index++) total += Integer.parseInt(args[index]);
        require(total <= 10 * 1024 * 1024, "combined allowances exceed frozen root");
    }

    private static boolean positive(String value, int limit) {
        return value.matches("[1-9][0-9]{0,7}") && Long.parseLong(value) <= limit;
    }

    static void validateCommand(String[] args, Path home, Path temporary) {
        for (String name : List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "CLASSPATH")) {
            require(System.getenv(name) == null, "injected JVM environment: " + name);
        }
        var expected = new ArrayList<>(List.of("-Xmx256m", "-XX:MaxMetaspaceSize=128m",
                "-Duser.home=" + home, "-Djava.io.tmpdir=" + temporary, "-cp",
                System.getProperty("java.class.path"), FixedWitnessReplayExecutionApplication.class.getName()));
        expected.addAll(List.of(args));
        require(expected.equals(List.of(ProcessHandle.current().info().arguments()
                        .orElseThrow(() -> new IllegalStateException("actual JVM arguments unavailable")))),
                "unexpected JVM command/options");
    }

    private static Path canonical(Path path) throws Exception {
        require(path.isAbsolute() && path.equals(path.toRealPath())
                && Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS), "canonical directory required");
        for (Path ancestor = path; ancestor != null; ancestor = ancestor.getParent()) {
            require(!Files.isSymbolicLink(ancestor), "linked ancestor");
        }
        return path;
    }

    private static void check(Path root, FixedWitnessReplayDescriptor.Snapshot snapshot,
                              Map<String, Object> observed, long deadline) throws Exception {
        require(System.nanoTime() < deadline, "execution deadline expired");
        snapshot.verify();
        require(observed.equals(FixedWitnessJvmObservation.inspectExecution(root)), "loaded observation drift");
    }

    /** Package seam for bounded artificial failure tests; never a historical default entry. */
    static void replay(FixedWitnessReplayRequest request, Path output,
                       FixedWitnessReplay.JournalQuota journalQuota) throws Exception {
        FixedWitnessReplay.run(request.inputBytes(), request.inputHash(), request.paths(), request.expected(),
                output.resolve("journal.jsonl"), journalQuota);
    }

    /** Journal is closed before its owned bytes are consumed; report assembly never replays. */
    static Map<String, Object> report(FixedWitnessReplayRequest request, Path output, int journalLimit,
            FixedWitnessReplay.JournalQuota declarationQuota, FixedWitnessReplay.JournalQuota reportQuota,
            int reportLimit) throws Exception {
        byte[] journal = read(output.resolve("journal.jsonl"), journalLimit);
        var derived = new FixedWitnessReportRequest(request.inputBytes(), request.inputHash(), request.paths(),
                request.expected(), journal, FixedWitnessInput.sha256(journal));
        byte[] declaration = derived.canonicalBytes();
        write(output.resolve("report-request.json"), declaration, declarationQuota);
        var report = FixedWitnessReportApplication.writeReport(derived, output.resolve("report.json"),
                reportQuota, reportLimit);
        require(!report.get("state").equals("INCOMPLETE"), "execution journal has no closed END");
        return Map.of("journal_sha256", FixedWitnessInput.sha256(journal), "journal_bytes", journal.length,
                "declaration_sha256", FixedWitnessInput.sha256(declaration), "declaration_bytes", declaration.length,
                "report_sha256", report.get("sha256"), "report_bytes", report.get("bytes"),
                "journal_state", report.get("state"));
    }

    private static byte[] read(Path path, int limit) throws Exception {
        require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && Files.size(path) <= limit,
                "journal type/size limit");
        try (var stream = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] raw = stream.readNBytes(limit + 1);
            require(raw.length <= limit, "journal size limit");
            return raw;
        }
    }

    private static void write(Path path, byte[] bytes, FixedWitnessReplay.JournalQuota quota) throws Exception {
        quota.charge(bytes.length);
        try (var channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            var buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
    }

    private static Map<String, Object> fields(String[] args) {
        return new LinkedHashMap<>(Map.of("schema", SCHEMA, "request_sha256", args[9],
                "handshake_bytes_limit", Integer.parseInt(args[4]), "journal_bytes_limit", Integer.parseInt(args[5]),
                "declaration_bytes_limit", Integer.parseInt(args[6]), "report_bytes_limit", Integer.parseInt(args[7]),
                "diagnostic_authorized", false, "network_isolation_verified", false,
                "provenance_authenticated", false, "eligible_for_runtime_validation", false));
    }

    private static byte[] json(Object value) {
        return (JSON.writeValueAsString(value) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    private static void require(boolean condition, String message) {
        FixedWitnessJvmObservation.require(condition, message);
    }
}
