/*
 * Copyright (C) 2024-2026 xuMingHai
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** Selected real file bytes and injected child lifecycle; no native probe. */
class FixedWitnessHostCommandTest {
    @TempDir Path directory;

    private FixedWitnessWriterHost.RetainedCommand receipt() throws Exception {
        var root = directory.toRealPath();
        var executable = Files.writeString(root.resolve("python"), "interpreter");
        var entry = Files.writeString(root.resolve("tracer.py"), "source");
        return new FixedWitnessWriterHost.RetainedCommand(
                List.of(executable.toString(), "-I", "-B", entry.toString(), root.toString()), root,
                Map.of("LANG", "C.UTF-8"), Map.of(executable, hash(executable), entry, hash(entry)));
    }

    private static String hash(Path path) throws IOException {
        return FixedWitnessInput.sha256(Files.readAllBytes(path));
    }

    @Test
    void retainedCollectionsCannotChangeAfterCallerMutation() throws Exception {
        var original = receipt();
        var arguments = new ArrayList<>(original.arguments());
        var environment = new HashMap<>(original.environment());
        var files = new HashMap<>(original.files());
        var retained = new FixedWitnessWriterHost.RetainedCommand(arguments, original.cwd(), environment, files);
        arguments.set(0, "other");
        environment.put("PYTHONPATH", "injection");
        files.clear();
        var host = new FixedWitnessWriterHost(directory.toRealPath());
        var child = new Child();
        assertEquals(0, host.launchAndObserve(retained, () -> child));
        assertEquals(original, retained);
        assertTrue(child.input.toString().startsWith("FIXED_WRITERS_V2 "));
    }

    @Test
    void prelaunchSourceDriftNeverStartsAndPermanentlyConsumesAttempt() throws Exception {
        var retained = receipt();
        Files.writeString(Path.of(retained.arguments().get(3)), "changed");
        var host = new FixedWitnessWriterHost(directory.toRealPath());
        var starts = new AtomicInteger();
        assertThrows(IOException.class, () -> host.launchAndObserve(retained, () -> {
            starts.incrementAndGet();
            return new Child();
        }));
        assertEquals(0, starts.get());
        assertThrows(IllegalStateException.class, () -> host.launchAndObserve(retained, Child::new));
        assertThrows(IllegalStateException.class, () -> host.write("host-later.txt", new byte[1]));
        assertFalse(Files.exists(directory.resolve("host-tracer.txt")));
    }

    @Test
    void postStartInterpreterDriftKillsWithoutIssuingGrant() throws Exception {
        var retained = receipt();
        var host = new FixedWitnessWriterHost(directory.toRealPath());
        var child = new Child();
        assertThrows(IOException.class, () -> host.launchAndObserve(retained, () -> {
            Files.writeString(Path.of(retained.arguments().getFirst()), "changed");
            return child;
        }));
        assertEquals(0, child.input.size());
        assertEquals(1, child.kills);
        assertFalse(child.alive);
        assertFalse(Files.exists(directory.resolve("host-tracer.txt")));
    }

    @Test
    void terminalSourceDriftCannotCreateSuccessfulHostObservation() throws Exception {
        var retained = receipt();
        var host = new FixedWitnessWriterHost(directory.toRealPath());
        var child = new Child() {
            @Override public boolean waitFor(long amount, TimeUnit unit) {
                try {
                    Files.writeString(Path.of(retained.arguments().get(3)), "changed");
                } catch (IOException error) {
                    throw new IllegalStateException(error);
                }
                return super.waitFor(amount, unit);
            }
        };
        assertThrows(IOException.class, () -> host.launchAndObserve(retained, () -> child));
        assertTrue(child.input.size() > 0);
        assertEquals(0, child.kills);
        assertFalse(Files.exists(directory.resolve("host-tracer.txt")));
        assertEquals("INCOMPLETE\n", Files.readString(directory.resolve("host-incomplete.txt")));
        assertThrows(IllegalStateException.class, () -> host.launchAndObserve(retained, Child::new));
    }

    @Test
    void sourceChecksKeepOriginalDeadlineAndCleanupReserve() throws Exception {
        var retained = receipt();
        var clock = new AtomicLong();
        var host = new FixedWitnessWriterHost(directory.toRealPath(), clock::get, TimeUnit.SECONDS.toNanos(10));
        var child = new Child();
        assertThrows(IOException.class, () -> host.launchAndObserve(retained, () -> {
            clock.set(TimeUnit.SECONDS.toNanos(7));
            return child;
        }));
        assertEquals(0, child.input.size());
        assertEquals(1, child.kills);
        assertFalse(child.alive);
    }

    @Test
    void foreignSessionNeverStarts() throws Exception {
        var retained = receipt();
        var arguments = new ArrayList<>(retained.arguments());
        arguments.set(4, directory.resolve("other").toString());
        var foreign = new FixedWitnessWriterHost.RetainedCommand(arguments, retained.cwd(), retained.environment(), retained.files());
        var host = new FixedWitnessWriterHost(directory.toRealPath());
        var starts = new AtomicInteger();
        assertThrows(IOException.class, () -> host.launchAndObserve(foreign, () -> { starts.incrementAndGet(); return new Child(); }));
        assertEquals(0, starts.get());
    }

    @Test
    void missingSelectedDependencyFailsBeforeLaunch() throws Exception {
        var retained = receipt();
        var dependency = Files.writeString(directory.resolve("dependency.py"), "dependency");
        var files = new HashMap<>(retained.files());
        files.put(dependency, hash(dependency));
        var command = new FixedWitnessWriterHost.RetainedCommand(retained.arguments(), retained.cwd(), retained.environment(), files);
        Files.delete(dependency);
        var host = new FixedWitnessWriterHost(directory.toRealPath());
        var starts = new AtomicInteger();
        assertThrows(IOException.class, () -> host.launchAndObserve(command, () -> { starts.incrementAndGet(); return new Child(); }));
        assertEquals(0, starts.get());
    }

    @Test
    void isolatedArgvAndAllowlistedEnvironmentAndBothIdentitiesRequired() throws Exception {
        var retained = receipt();
        assertThrows(IllegalArgumentException.class, () -> new FixedWitnessWriterHost.RetainedCommand(
                retained.arguments(), retained.cwd(), Map.of("PYTHONPATH", "injected"), retained.files()));
        assertThrows(IllegalArgumentException.class, () -> new FixedWitnessWriterHost.RetainedCommand(
                List.of("python3", "-c", "injected"), retained.cwd(), Map.of(), retained.files()));
        var files = new HashMap<>(retained.files());
        files.remove(Path.of(retained.arguments().getFirst()));
        assertThrows(IllegalArgumentException.class, () -> new FixedWitnessWriterHost.RetainedCommand(
                retained.arguments(), retained.cwd(), Map.of(), files));
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void retainedCommandReachesActualGrantAndSupervisorWithFakeKernel() throws Exception {
        var root = Path.of("").toRealPath();
        var session = directory.toRealPath().resolve("session");
        var host = FixedWitnessWriterHost.createSession(session);
        var entry = directory.resolve("tracer.py");
        Files.writeString(entry, "import os, sys\n"
                + "assert 'PYTHONPATH' not in os.environ and 'PATH' not in os.environ\n"
                + "assert sys.flags.isolated and sys.dont_write_bytecode\n"
                + "sys.path.insert(0, " + "'" + root.resolve("scripts") + "')\n"
                + "from test_fixed_witness_replay_writer_handoff import java_fixture\n"
                + "java_fixture(sys.argv[1])\n");
        var executable = Path.of("/usr/bin/python3").toRealPath();
        var files = new HashMap<Path, String>();
        files.put(executable, hash(executable));
        files.put(entry, hash(entry));
        try (var inputs = Files.list(root.resolve("scripts"))) {
            for (var path : inputs.filter(path -> path.toString().endsWith(".py")).toList()) {
                files.put(path, hash(path));
            }
        }
        var command = new FixedWitnessWriterHost.RetainedCommand(
                List.of(executable.toString(), "-I", "-B", entry.toString(), session.toString()),
                root, Map.of("LANG", "C.UTF-8", "LC_ALL", "C.UTF-8", "TZ", "UTC"), files);
        assertEquals(0, host.launchAndObserve(command));
        assertTrue(Files.readString(session.resolve("tracer-lifecycle.json")).contains("DRAINED_OBSERVATION"));
        assertTrue(Files.readString(session.resolve("host-tracer.txt")).contains("SUITE_UNVERIFIED"));
        assertEquals("charged", Files.readString(session.resolve("coordinator-stdout.log")));
    }

    private static class Child extends Process {
        final ByteArrayOutputStream input = new ByteArrayOutputStream();
        boolean alive = true;
        int kills;
        @Override public OutputStream getOutputStream() { return input; }
        @Override public InputStream getInputStream() { return InputStream.nullInputStream(); }
        @Override public InputStream getErrorStream() { return InputStream.nullInputStream(); }
        @Override public int waitFor() { throw new AssertionError("unbounded wait forbidden"); }
        @Override public boolean waitFor(long amount, TimeUnit unit) { alive = false; return true; }
        @Override public int exitValue() { return 0; }
        @Override public void destroy() { throw new AssertionError("TERM forbidden"); }
        @Override public Process destroyForcibly() { kills++; return this; }
        @Override public boolean isAlive() { return alive; }
        @Override public long pid() { return 1; }
    }
}
