/*
 * Copyright (C) 2024-2026 xuMingHai
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Linux ordinary-JVM transport for caller-saved evidence only; never executes actions.
 * Parent independently owns the request/build receipts and permanently reserves both allowances
 * from one root. Observed bytes and ACK alone establish neither provenance nor replay authority.
 */
public final class FixedWitnessReportApplication {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String SCHEMA = "fixed-witness-report-jvm-v1";

    private FixedWitnessReportApplication() { }

    public static void main(String[] args) throws Exception {
        requireArguments(args);
        long deadline = System.nanoTime() + Duration.ofSeconds(120).toNanos();
        require(System.getProperty("os.name").equals("Linux") && Runtime.version().feature() == 27,
                "Linux JDK27 required");
        Path root = canonical(Path.of(args[1]));
        Path output = Path.of(args[2]);
        Path snapshot = canonical(Path.of(args[6]));
        Path home = canonical(Path.of(System.getProperty("user.home")));
        Path temporary = canonical(Path.of(System.getProperty("java.io.tmpdir")));
        FixedWitnessReplayApplication.validateOutput(root, output);
        validateDirectories(root, output, snapshot, home, temporary);
        validateCommand(args, home, temporary);
        var before = snapshot(snapshot);
        var handshake = new FixedWitnessReplayApplication.ArtifactQuota(Integer.parseInt(args[4]));
        var reportQuota = new FixedWitnessReplay.JournalQuota(Integer.parseInt(args[5]));
        Files.createDirectory(output);
        var observed = FixedWitnessJvmObservation.inspect(root, true);
        var ready = json(Map.ofEntries(Map.entry("schema", SCHEMA), Map.entry("state", "READY"),
                Map.entry("nonce", args[3]), Map.entry("observation", observed),
                Map.entry("handshake_bytes_limit", Integer.parseInt(args[4])),
                Map.entry("report_bytes_limit", Integer.parseInt(args[5])),
                Map.entry("request_sha256", args[7]), Map.entry("diagnostic_started", false),
                Map.entry("provenance_authenticated", false), Map.entry("eligible_for_runtime_validation", false)));
        handshake.write(output.resolve("ready.json"), ready);
        FixedWitnessReplayApplication.acknowledge(System.in, args[3], FixedWitnessInput.sha256(ready),
                Duration.ofNanos(Math.max(1, deadline - System.nanoTime())));
        require(System.nanoTime() < deadline, "report-only deadline expired");
        require(observed.equals(FixedWitnessJvmObservation.inspect(root, true)), "loaded observation drift");
        require(before.equals(snapshot(snapshot)), "snapshot drift before consumption");
        // Descriptor owns the sole input snapshots consumed by Request; no Replay.run path.
        var request = FixedWitnessReportDescriptor.read(snapshot, args[7]);
        var report = writeReport(request, output.resolve("report.json"), reportQuota, Integer.parseInt(args[5]));
        require(before.equals(snapshot(snapshot)), "snapshot drift after report");
        require(observed.equals(FixedWitnessJvmObservation.inspect(root, true)), "loaded observation drift");
        require(System.nanoTime() < deadline, "report-only deadline expired");
        handshake.write(output.resolve("reported.json"), json(Map.ofEntries(
                Map.entry("schema", SCHEMA), Map.entry("state", "REPORT_WRITTEN_NO_REPLAY"),
                Map.entry("ready_sha256", FixedWitnessInput.sha256(ready)), Map.entry("request_sha256", args[7]),
                Map.entry("report_sha256", report.get("sha256")), Map.entry("report_bytes", report.get("bytes")),
                Map.entry("journal_state", report.get("state")),
                Map.entry("handshake_bytes_limit", Integer.parseInt(args[4])),
                Map.entry("report_bytes_limit", Integer.parseInt(args[5])), Map.entry("diagnostic_started", false),
                Map.entry("provenance_authenticated", false), Map.entry("eligible_for_runtime_validation", false))));
    }

    static void requireArguments(String[] args) {
        if (args.length != 8 || !args[0].equals("--report-only") || !args[3].matches("[0-9a-f]{64}")
                || !args[7].matches("[0-9a-f]{64}") || !positive(args[4], 2 * 1024 * 1024)
                || !positive(args[5], 10 * 1024 * 1024)) {
            throw new IllegalArgumentException("usage: --report-only <checkout> <new-output> <nonce>"
                    + " <handshake-bytes> <report-bytes> <snapshot-directory> <expected-request-sha256>");
        }
    }

    private static boolean positive(String value, int max) {
        return value.matches("[1-9][0-9]{0,7}") && Long.parseLong(value) <= max;
    }

    static void validateDirectories(Path... directories) {
        for (int i = 0; i < directories.length; i++) for (int j = i + 1; j < directories.length; j++) {
            require(!directories[i].startsWith(directories[j]) && !directories[j].startsWith(directories[i]),
                    "disjoint checkout/output/snapshot/HOME/TMP required");
        }
    }

    private static Path canonical(Path directory) throws Exception {
        require(directory.isAbsolute() && directory.equals(directory.toRealPath())
                && Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS), "canonical directory required");
        for (Path p = directory; p != null; p = p.getParent()) {
            require(!Files.isSymbolicLink(p), "linked ancestor");
        }
        return directory;
    }

    private static void validateCommand(String[] args, Path home, Path temporary) {
        for (String name : List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "CLASSPATH")) {
            require(System.getenv(name) == null, "injected JVM environment: " + name);
        }
        var expected = new ArrayList<>(List.of("-Xmx256m", "-XX:MaxMetaspaceSize=128m",
                "-Duser.home=" + home, "-Djava.io.tmpdir=" + temporary, "-cp",
                System.getProperty("java.class.path"), FixedWitnessReportApplication.class.getName()));
        expected.addAll(List.of(args));
        require(expected.equals(List.of(ProcessHandle.current().info().arguments().orElseThrow())),
                "unexpected JVM command/options");
    }

    /** Metadata only: evidence bytes must not be consumed before parent ACK. */
    private static Map<String, Object> snapshot(Path directory) throws Exception {
        canonical(directory);
        try (var entries = Files.list(directory)) {
            require(entries.map(p -> p.getFileName().toString()).collect(java.util.stream.Collectors.toSet())
                    .equals(Set.of("request.json", "input.csv", "journal.jsonl")), "snapshot exact file set required");
        }
        var values = new java.util.LinkedHashMap<String, Object>();
        for (String name : List.of("", "request.json", "input.csv", "journal.jsonl")) {
            Path path = name.isEmpty() ? directory : directory.resolve(name);
            var attributes = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            require(attributes.fileKey() != null && !attributes.isSymbolicLink()
                    && (name.isEmpty() ? attributes.isDirectory() : attributes.isRegularFile()),
                    "file type/identity unavailable");
            values.put(name, List.of(attributes.fileKey(), attributes.size(), attributes.lastModifiedTime()));
        }
        return values;
    }

    /** Only reads our written report to bind raw bytes and propagate its actual journal state. */
    static Map<String, Object> writeReport(FixedWitnessReportRequest request, Path output,
            FixedWitnessReplay.JournalQuota quota, int limit) throws Exception {
        request.write(output, quota);
        byte[] raw;
        try (var stream = Files.newInputStream(output, LinkOption.NOFOLLOW_LINKS)) {
            raw = stream.readNBytes(limit + 1);
        }
        require(raw.length <= limit, "report size limit");
        String state = JSON.readTree(raw).get("journalState").asString();
        require(Set.of("INCOMPLETE", "INVALID", "EVIDENCE_MISMATCH", "COMPLETE").contains(state), "journal state");
        return Map.of("sha256", FixedWitnessInput.sha256(raw), "bytes", raw.length, "state", state);
    }

    private static byte[] json(Map<String, ?> value) {
        return (JSON.writeValueAsString(value) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    private static void require(boolean condition, String message) {
        FixedWitnessJvmObservation.require(condition, message);
    }
}
