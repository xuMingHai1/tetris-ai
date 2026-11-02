/*
 * Copyright (C) 2024-2026 xuMingHai
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

/** Only handshake/class observations; no historical fixture, generator or replay calls. */
class FixedWitnessReplayApplicationTest {
    private static final String NONCE = "a".repeat(64);
    private static final String HASH = "b".repeat(64);
    @TempDir Path directory;

    @Test
    void rejectsCollectImplicitAndOverrideArguments() {
        for (var args : new String[][]{{}, {"--collect", "root", "output", NONCE},
                {"--verify-only", "root", "output", "bad"},
                {"--verify-only", "root", "output", NONCE, "--replay"}}) {
            assertThrows(IllegalArgumentException.class, () -> FixedWitnessReplayApplication.main(args));
        }
        FixedWitnessReplayApplication.requireArguments(new String[]{"--verify-only", "root", "output", NONCE});
    }

    @Test
    void requiresExactAckNonceHashLfAndEof() throws Exception {
        var valid = "ACK " + NONCE + " " + HASH + "\n";
        FixedWitnessReplayApplication.acknowledge(bytes(valid), NONCE, HASH, Duration.ofSeconds(1));
        for (var invalid : List.of("", valid.stripTrailing(), valid + "x", valid + "\n",
                valid.replace(HASH, NONCE), valid.replace(NONCE, HASH), valid.replace("\n", "\r\n"))) {
            assertThrows(IllegalStateException.class, () ->
                    FixedWitnessReplayApplication.acknowledge(bytes(invalid), NONCE, HASH, Duration.ofSeconds(1)));
        }
    }

    @Test
    void blockedAckHasBoundedWait() throws Exception {
        var released = new CountDownLatch(1);
        var entered = new CountDownLatch(1);
        var input = new InputStream() {
            @Override public int read() {
                entered.countDown();
                try {
                    released.await();
                    return -1;
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return -1;
                }
            }
        };
        try {
            assertThrows(TimeoutException.class, () -> FixedWitnessReplayApplication.acknowledge(
                    input, NONCE, HASH, Duration.ofMillis(50)));
            assertTrue(entered.await(1, TimeUnit.SECONDS));
        } finally {
            released.countDown();
        }
    }

    @Test
    void requiresCanonicalExternalOutput() throws Exception {
        var root = Files.createDirectory(directory.resolve("root")).toRealPath();
        var external = directory.toRealPath().resolve("fresh");
        FixedWitnessReplayApplication.validateOutput(root, external);
        assertThrows(IllegalStateException.class, () ->
                FixedWitnessReplayApplication.validateOutput(root, root.resolve("output")));
        assertThrows(IllegalStateException.class, () ->
                FixedWitnessReplayApplication.validateOutput(root, directory.resolve("missing/../fresh")));
        assertThrows(IllegalStateException.class, () ->
                FixedWitnessReplayApplication.validateOutput(root, Path.of("relative")));
    }

    static class UninitializedFixture {
        static { System.setProperty("fixed.witness.fixture.initialized", "yes"); }
        static class Nested { }
    }

    @Test
    void observesNestedByteSourcesWithoutInitializationAndRejectsWrongOrigin() throws Exception {
        var base = Path.of(FixedWitnessReplayApplicationTest.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI()).toRealPath();
        System.clearProperty("fixed.witness.fixture.initialized");
        // Surefire patches a named module and may expose its resources twice. The entrypoint
        // explicitly requires an ordinary classpath, so test that same loader topology.
        try (var loader = new java.net.URLClassLoader(new java.net.URL[]{base.toUri().toURL()},
                null)) {
            var type = Class.forName(UninitializedFixture.class.getName(), false, loader);
            var result = new LinkedHashMap<String, Object>();
            FixedWitnessJvmObservation.observe(type, base, loader, result);
            assertNull(System.getProperty("fixed.witness.fixture.initialized"));
            assertEquals(2, result.size());
            assertTrue(result.containsKey(type.getName() + "$Nested"));
            var expected = FixedWitnessInput.sha256(Files.readAllBytes(base.resolve(
                    type.getName().replace('.', '/') + ".class")));
            assertEquals(expected, ((java.util.Map<?, ?>) result.get(type.getName())).get("sha256"));
            assertThrows(IllegalStateException.class, () -> FixedWitnessJvmObservation.observe(type,
                    directory.toRealPath(), loader, new LinkedHashMap<>()));
        }
    }

    @Test
    void rejectsDuplicateClassResourceEvenWhenLoadedClassHasExpectedOrigin() throws Exception {
        var base = Path.of(FixedWitnessReplayApplicationTest.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI()).toRealPath();
        var resource = UninitializedFixture.class.getName().replace('.', '/') + ".class";
        var shadow = directory.toRealPath().resolve(resource);
        Files.createDirectories(shadow.getParent());
        Files.copy(base.resolve(resource), shadow);
        try (var loader = new java.net.URLClassLoader(new java.net.URL[]{
                base.toUri().toURL(), directory.toRealPath().toUri().toURL()},
                null)) {
            var type = Class.forName(UninitializedFixture.class.getName(), false, loader);
            var error = assertThrows(IllegalStateException.class, () ->
                    FixedWitnessJvmObservation.observe(type, base, loader, new LinkedHashMap<>()));
            assertEquals("shadow class resource", error.getMessage());
        }
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void ordinaryJvmHoldsReadyUntilAckThenExitsWithoutReplay() throws Exception {
        var output = directory.toRealPath().resolve("verify");
        var root = Path.of("").toRealPath();
        var process = launch(root, output, List.of());
        try {
            var ready = output.resolve("ready.json");
            awaitReady(process, ready);
            assertTrue(process.isAlive());
            assertFalse(Files.exists(output.resolve("verified.json")));
            var raw = Files.readAllBytes(ready);
            var json = JsonMapper.builder().build().readTree(raw);
            assertFalse(json.get("diagnostic_started").asBoolean());
            assertFalse(json.get("provenance_authenticated").asBoolean());
            assertTrue(json.get("observation").get("classes").size() > 35);
            process.getOutputStream().write(("ACK " + NONCE + " " + FixedWitnessInput.sha256(raw) + "\n")
                    .getBytes(StandardCharsets.US_ASCII));
            process.getOutputStream().close();
            assertTrue(process.waitFor(15, TimeUnit.SECONDS));
            assertEquals(0, process.exitValue(), log(output));
            var verified = JsonMapper.builder().build().readTree(Files.readAllBytes(output.resolve("verified.json")));
            assertEquals("VERIFIED_NO_REPLAY", verified.get("state").asString());
            assertFalse(verified.get("provenance_authenticated").asBoolean());
            try (var files = Files.list(output)) {
                assertEquals(List.of("ready.json", "verified.json"),
                        files.map(path -> path.getFileName().toString()).sorted().toList());
            }
            // A second invocation cannot overwrite the original evidence.
            var again = launch(root, output, List.of());
            try {
                again.getOutputStream().close();
                assertTrue(again.waitFor(15, TimeUnit.SECONDS));
                assertNotEquals(0, again.exitValue());
                assertArrayEquals(raw, Files.readAllBytes(ready));
            } finally { stop(again); }
        } finally { stop(process); }
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void eofRetainsReadyPrefixAndUnexpectedJvmFlagsFailBeforeOutput() throws Exception {
        var root = Path.of("").toRealPath();
        var output = directory.toRealPath().resolve("eof");
        var process = launch(root, output, List.of());
        try {
            awaitReady(process, output.resolve("ready.json"));
            process.getOutputStream().close();
            assertTrue(process.waitFor(15, TimeUnit.SECONDS));
            assertNotEquals(0, process.exitValue());
            assertFalse(Files.exists(output.resolve("verified.json")));
        } finally { stop(process); }
        var bad = directory.toRealPath().resolve("bad-flags");
        var injected = launch(root, bad, List.of("-Dunexpected=true"));
        try {
            injected.getOutputStream().close();
            assertTrue(injected.waitFor(15, TimeUnit.SECONDS));
            assertNotEquals(0, injected.exitValue());
            assertFalse(Files.exists(bad));
        } finally { stop(injected); }
    }

    private Process launch(Path root, Path output, List<String> extraFlags) throws Exception {
        // The ordinary JVM needs the complete dependency classpath, not the modular test launcher.
        var classpath = System.getProperty("surefire.test.class.path");
        assertNotNull(classpath, "Surefire must provide the explicit dependency classpath");
        var home = Files.createTempDirectory(directory, "home").toRealPath();
        var temporary = Files.createTempDirectory(directory, "tmp").toRealPath();
        var args = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin/java").toString(),
                "-Xmx256m", "-XX:MaxMetaspaceSize=128m",
                "-Duser.home=" + home, "-Djava.io.tmpdir=" + temporary));
        args.addAll(extraFlags);
        args.addAll(List.of("-cp", classpath, FixedWitnessReplayApplication.class.getName(),
                "--verify-only", root.toString(), output.toString(), NONCE));
        var builder = new ProcessBuilder(args).directory(root.toFile())
                .redirectErrorStream(true).redirectOutput(directory.resolve(output.getFileName() + ".log").toFile());
        builder.environment().clear();
        builder.environment().put("LANG", "C.UTF-8");
        return builder.start();
    }

    private String log(Path output) throws Exception {
        return Files.readString(directory.resolve(output.getFileName() + ".log"));
    }

    private void awaitReady(Process process, Path ready) throws Exception {
        long until = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (!Files.exists(ready) && process.isAlive() && System.nanoTime() < until) Thread.sleep(10);
        assertTrue(Files.exists(ready), log(ready.getParent()));
        // Creation precedes force/close. Do not acknowledge a partial JSON file.
        while (process.isAlive() && System.nanoTime() < until) {
            try {
                JsonMapper.builder().build().readTree(Files.readAllBytes(ready));
                if (Files.readString(ready).endsWith("\n")) return;
            } catch (RuntimeException partial) { /* bounded wait for the writer */ }
            Thread.sleep(10);
        }
        fail("READY was not completed");
    }

    private static ByteArrayInputStream bytes(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.US_ASCII));
    }

    private static void stop(Process process) throws Exception {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        if (process.isAlive()) process.destroyForcibly();
        assertTrue(process.waitFor(5, TimeUnit.SECONDS));
    }
}
