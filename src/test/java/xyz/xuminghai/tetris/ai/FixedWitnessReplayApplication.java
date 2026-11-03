/*
 * Copyright (C) 2024-2026 xuMingHai
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/**
 * Ordinary JVM verify-only handshake. There is deliberately no collect/replay mode.
 * READY waits for a parent acknowledgement bound to these observation bytes; even a valid
 * acknowledgement never asserts provenance, network isolation or historical execution permission.
 */
public final class FixedWitnessReplayApplication {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private FixedWitnessReplayApplication() { }

    public static void main(String[] args) throws Exception {
        requireArguments(args);
        long deadline = System.nanoTime() + Duration.ofSeconds(120).toNanos();
        FixedWitnessJvmObservation.require(System.getProperty("os.name").equals("Linux")
                && Runtime.version().feature() == 27, "Linux JDK27 required");
        var root = canonical(Path.of(args[1]));
        var output = Path.of(args[2]);
        validateOutput(root, output);
        var home = canonical(Path.of(System.getProperty("user.home")));
        var temporary = canonical(Path.of(System.getProperty("java.io.tmpdir")));
        validateCommand(args, home, temporary);
        var quota = new ArtifactQuota(Integer.parseInt(args[4]));
        Files.createDirectory(output);
        var observed = FixedWitnessJvmObservation.inspect(root);
        var bytes = json(Map.of("schema", "fixed-witness-jvm-v1", "state", "READY",
                "nonce", args[3], "observation", observed,
                "artifact_bytes_limit", quota.limit,
                "diagnostic_started", false, "provenance_authenticated", false,
                "eligible_for_runtime_validation", false));
        quota.write(output.resolve("ready.json"), bytes);
        acknowledge(System.in, args[3], FixedWitnessInput.sha256(bytes),
                Duration.ofNanos(Math.max(1, deadline - System.nanoTime())));
        FixedWitnessJvmObservation.require(System.nanoTime() < deadline, "verify-only deadline expired");
        FixedWitnessJvmObservation.require(observed.equals(FixedWitnessJvmObservation.inspect(root)),
                "loaded class observation drift");
        quota.write(output.resolve("verified.json"), json(Map.of("schema", "fixed-witness-jvm-v1",
                "state", "VERIFIED_NO_REPLAY", "ready_sha256", FixedWitnessInput.sha256(bytes),
                "artifact_bytes_limit", quota.limit,
                "diagnostic_started", false, "provenance_authenticated", false,
                "eligible_for_runtime_validation", false)));
    }

    static void requireArguments(String[] args) {
        if (args.length != 5 || !args[0].equals("--verify-only")
                || !args[3].matches("[0-9a-f]{64}") || !args[4].matches("[1-9][0-9]{0,6}")
                || Integer.parseInt(args[4]) > 2 * 1024 * 1024) {
            throw new IllegalArgumentException("usage: --verify-only <checkout-root> <new-external-output>"
                    + " <64-hex-nonce> <artifact-bytes:1..2097152>");
        }
    }

    private static Path canonical(Path path) throws Exception {
        FixedWitnessJvmObservation.require(path.isAbsolute() && path.equals(path.toRealPath()),
                "canonical absolute directory required");
        FixedWitnessJvmObservation.require(Files.isDirectory(path), "directory required");
        return path;
    }

    static void validateOutput(Path root, Path output) throws Exception {
        FixedWitnessJvmObservation.require(output.isAbsolute() && output.equals(output.normalize())
                && !output.startsWith(root) && !root.startsWith(output)
                && output.getParent() != null, "canonical external output required");
        canonical(output.getParent());
    }

    private static void validateCommand(String[] args, Path home, Path temporary) {
        FixedWitnessJvmObservation.require(!home.startsWith(temporary) && !temporary.startsWith(home),
                "disjoint HOME/TMPDIR required");
        for (var name : List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "CLASSPATH")) {
            FixedWitnessJvmObservation.require(System.getenv(name) == null, "injected JVM environment: " + name);
        }
        var expected = new java.util.ArrayList<>(List.of("-Xmx256m", "-XX:MaxMetaspaceSize=128m",
                "-Duser.home=" + home, "-Djava.io.tmpdir=" + temporary, "-cp",
                System.getProperty("java.class.path"), FixedWitnessReplayApplication.class.getName()));
        expected.addAll(List.of(args));
        var actual = ProcessHandle.current().info().arguments()
                .orElseThrow(() -> new IllegalStateException("actual JVM arguments unavailable"));
        FixedWitnessJvmObservation.require(expected.equals(List.of(actual)), "unexpected JVM command/options");
    }

    /** Parent sends exactly ACK nonce ready_sha256 LF, then closes stdin. EOF is mandatory. */
    static void acknowledge(InputStream input, String nonce, String hash, Duration remaining) throws Exception {
        var expected = ("ACK " + nonce + " " + hash + "\n").getBytes(StandardCharsets.US_ASCII);
        var task = new FutureTask<>(() -> input.readNBytes(expected.length + 1));
        var reader = new Thread(task, "fixed-witness-ack");
        reader.setDaemon(true); // A blocked pipe cannot keep a timed-out ordinary JVM alive.
        reader.start();
        try {
            var actual = task.get(remaining.toNanos(), TimeUnit.NANOSECONDS);
            FixedWitnessJvmObservation.require(Arrays.equals(actual, expected), "missing/incorrect parent acknowledgement");
        } finally {
            task.cancel(true);
        }
    }

    private static byte[] json(Map<String, ?> value) {
        return (JSON.writeValueAsString(value) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    /** One cooperative JVM writer, charged before CREATE_NEW; failed I/O never refunds bytes. */
    static final class ArtifactQuota {
        private final int limit;
        private int charged;

        ArtifactQuota(int limit) {
            if (limit <= 0 || limit > 2 * 1024 * 1024) throw new IllegalArgumentException("artifact allowance");
            this.limit = limit;
        }

        synchronized void write(Path file, byte[] bytes) throws Exception {
            FixedWitnessJvmObservation.require(bytes.length <= 1024 * 1024
                    && bytes.length <= limit - charged, "JVM artifact allowance exhausted");
            charged += bytes.length;
            try (var channel = FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                var buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
        }
    }
}
