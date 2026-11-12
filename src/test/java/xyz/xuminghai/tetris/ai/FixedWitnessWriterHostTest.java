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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Real Java/Python grant pipe; all ptrace/fork/wait/signal transports are fake. */
class FixedWitnessWriterHostTest {
    @TempDir Path directory;

    @Test
    void retainsOnlyHostPartitionAndRejectsDuplicatesAndNames() throws Exception {
        var host = new FixedWitnessWriterHost(directory.toRealPath());
        assertEquals(11, host.charged());
        host.write("host-last.txt", new byte[FixedWitnessWriterHost.REPORT_BYTES - 11]);
        assertEquals(FixedWitnessWriterHost.REPORT_BYTES, host.charged());
        assertThrows(IllegalArgumentException.class, () -> host.write("host-extra.txt", new byte[1]));
        assertThrows(IOException.class, () -> new FixedWitnessWriterHost(directory.toRealPath()));
    }

    @Test
    void failedExclusiveOpenKeepsItsFullCharge() throws Exception {
        var host = new FixedWitnessWriterHost(directory.toRealPath());
        assertThrows(IOException.class, () -> host.write("host-incomplete.txt", new byte[3]));
        assertEquals(14, host.charged());
        assertEquals("INCOMPLETE\n", Files.readString(directory.resolve("host-incomplete.txt")));
        assertThrows(IllegalStateException.class, () -> host.handoff(new FakeChild(new ByteArrayOutputStream())));
        assertThrows(IllegalStateException.class, () -> host.write("host-later.txt", new byte[1]));
    }

    @Test
    void invalidNameAndOverflowPermanentlyStopHostAndHandoff() throws Exception {
        for (var name : new String[]{"tracer-lifecycle.json", "host-overflow.txt"}) {
            var session = Files.createDirectory(directory.resolve(name)).toRealPath();
            var host = new FixedWitnessWriterHost(session);
            var bytes = new byte[name.startsWith("host-") ? FixedWitnessWriterHost.REPORT_BYTES : 1];
            assertThrows(IllegalArgumentException.class, () -> host.write(name, bytes));
            assertEquals(11, host.charged());
            assertThrows(IllegalStateException.class, () -> host.handoff(new FakeChild(new ByteArrayOutputStream())));
            assertThrows(IllegalStateException.class, () -> host.write("host-later.txt", new byte[1]));
        }
    }

    @Test
    void sendsFrozenPidBoundGrantOnceAndRequiresEof() throws Exception {
        var host = new FixedWitnessWriterHost(directory.toRealPath());
        var sink = new ByteArrayOutputStream();
        var child = new FakeChild(sink);
        host.handoff(child);
        assertEquals("FIXED_WRITERS_V1 " + ProcessHandle.current().pid() + " 1 10485760 32768 32768 10420224\n",
                sink.toString(StandardCharsets.US_ASCII));
        assertThrows(IllegalStateException.class, () -> host.handoff(child));
        assertEquals(11, host.charged());
    }

    @Test
    void failedFlushDoesNotCloseRetryOrReissue() throws Exception {
        var host = new FixedWitnessWriterHost(directory.toRealPath());
        int[] calls = new int[3];
        var sink = new OutputStream() {
            @Override public void write(int value) { calls[0]++; }
            @Override public void flush() throws IOException { calls[1]++; throw new IOException("flush failed"); }
            @Override public void close() { calls[2]++; }
        };
        var child = new FakeChild(sink);
        assertThrows(IOException.class, () -> host.handoff(child));
        assertThrows(IllegalStateException.class, () -> host.handoff(child));
        assertTrue(calls[0] > 0);
        assertEquals(1, calls[1]);
        assertEquals(0, calls[2]);
        assertThrows(IllegalStateException.class, () -> host.write("host-later.txt", new byte[1]));
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void javaPipeGrantReachesActualSupervisorWithFakeKernel() throws Exception {
        var session = Files.createDirectory(directory.resolve("session")).toRealPath();
        var host = new FixedWitnessWriterHost(session);
        var code = "import sys; sys.path.insert(0, 'scripts'); "
                + "from test_fixed_witness_replay_writer_handoff import java_fixture; java_fixture(sys.argv[1])";
        // No child output escapes either allowance. Retained test assertions
        // use the actual tracer report; OS errors still produce a nonzero exit.
        var process = new ProcessBuilder("python3", "-B", "-c", code, session.toString())
                .directory(Path.of("").toAbsolutePath().toFile())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            host.handoff(process);
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "fake supervisor child timed out");
            assertEquals(0, process.exitValue());
            var report = Files.readString(session.resolve("tracer-lifecycle.json"));
            assertTrue(report.contains("DRAINED_OBSERVATION"), report);
            assertTrue(report.contains("\"suite_verified\":false"), report);
            assertEquals("charged", Files.readString(session.resolve("coordinator-stdout.log")));
            assertThrows(IllegalStateException.class, () -> host.handoff(process));
        } finally {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            if (process.isAlive()) {
                process.destroyForcibly();
                assertTrue(process.waitFor(5, TimeUnit.SECONDS), "test child did not stop");
            }
        }
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void allInjectedHandoffCasesRunInTheRegularSuite() throws Exception {
        var log = directory.resolve("handoff-tests.log");
        var process = new ProcessBuilder("python3", "-B", "-m", "unittest", "discover", "-v",
                "-s", "scripts", "-p", "test_fixed_witness_replay_writer_handoff.py")
                .directory(Path.of("").toAbsolutePath().toFile())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "handoff cases timed out");
            var output = Files.readString(log);
            assertEquals(0, process.exitValue(), output);
            assertTrue(output.contains("Ran 13 tests"), output);
            assertTrue(output.contains("\nOK\n"), output);
            assertFalse(output.contains("skipped"), output);
        } finally {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            if (process.isAlive()) {
                process.destroyForcibly();
                assertTrue(process.waitFor(5, TimeUnit.SECONDS), "test child did not stop");
            }
        }
    }

    private static final class FakeChild extends Process {
        private final OutputStream input;
        FakeChild(OutputStream input) { this.input = input; }
        @Override public OutputStream getOutputStream() { return input; }
        @Override public InputStream getInputStream() { return InputStream.nullInputStream(); }
        @Override public InputStream getErrorStream() { return InputStream.nullInputStream(); }
        @Override public int waitFor() { return 0; }
        @Override public int exitValue() { return 0; }
        @Override public void destroy() { }
        @Override public boolean isAlive() { return true; }
        @Override public long pid() { return 1; }
    }
}
