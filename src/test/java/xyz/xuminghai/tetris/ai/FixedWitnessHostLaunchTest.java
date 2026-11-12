/*
 * Copyright (C) 2024-2026 xuMingHai
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** All launches, waits and kills are injected; no native capability execution. */
class FixedWitnessHostLaunchTest {
    @TempDir Path directory;
    private final AtomicLong clock = new AtomicLong();

    private FixedWitnessWriterHost host() throws IOException {
        return new FixedWitnessWriterHost(directory.toRealPath(), clock::get, TimeUnit.SECONDS.toNanos(10));
    }

    @Test
    void sessionCreationIsExclusiveAndKeepsOriginalFailurePrefix() throws Exception {
        var path = directory.toRealPath().resolve("session");
        var host = FixedWitnessWriterHost.createSession(path);
        assertEquals(11, host.charged());
        assertThrows(IOException.class, () -> FixedWitnessWriterHost.createSession(path));
        assertEquals("INCOMPLETE\n", Files.readString(path.resolve("host-incomplete.txt")));
    }

    @Test
    void originalTimerIncludesPreparationAndStartAndNoSecondAttempt() throws Exception {
        var host = host();
        clock.set(TimeUnit.SECONDS.toNanos(2));
        var process = new Child();
        var starts = new AtomicInteger();
        assertEquals(0, host.launchAndObserve(() -> {
            starts.incrementAndGet();
            clock.addAndGet(TimeUnit.SECONDS.toNanos(1));
            return process;
        }));
        assertTrue(process.input.toString().endsWith(" 4000000000 1 1\n"));
        assertTrue(process.input.toString().startsWith("FIXED_WRITERS_V2 "));
        assertEquals(TimeUnit.SECONDS.toNanos(4), process.waitNanos);
        assertEquals(0, process.kills);
        assertEquals("DIRECT_TRACER_EXIT_0\nSUITE_UNVERIFIED\n", Files.readString(directory.resolve("host-tracer.txt")));
        assertThrows(IllegalStateException.class, () -> host.launchAndObserve(() -> process));
        assertEquals(1, starts.get());
        assertEquals("INCOMPLETE\n", Files.readString(directory.resolve("host-incomplete.txt")));
    }

    @Test
    void expiredBeforeStartConsumesAttemptWithoutStartingOrGrant() throws Exception {
        var host = host();
        clock.set(TimeUnit.SECONDS.toNanos(7));
        var starts = new AtomicInteger();
        assertThrows(IOException.class, () -> host.launchAndObserve(() -> { starts.incrementAndGet(); return new Child(); }));
        assertThrows(IllegalStateException.class, () -> host.launchAndObserve(Child::new));
        assertEquals(0, starts.get());
        assertFalse(Files.exists(directory.resolve("host-tracer.txt")));
    }

    @Test
    void failedStartIsNotRetried() throws Exception {
        var host = host();
        var original = new IOException("start failed");
        var error = assertThrows(IOException.class, () -> host.launchAndObserve(() -> { throw original; }));
        assertSame(original, error.getCause());
        assertThrows(IllegalStateException.class, () -> host.launchAndObserve(Child::new));
    }

    @Test
    void delayedStartIsRetainedKilledAndObservedBeforeGrant() throws Exception {
        var host = host();
        var process = new Child();
        assertThrows(IOException.class, () -> host.launchAndObserve(() -> {
            clock.set(TimeUnit.SECONDS.toNanos(8));
            return process;
        }));
        assertEquals(0, process.input.size());
        assertEquals(1, process.kills);
        assertFalse(process.isAlive());
        assertEquals(TimeUnit.SECONDS.toNanos(2), process.waitNanos);
    }

    @Test
    void failedGrantKeepsOriginalErrorAndPerformsBoundedTerminalWait() throws Exception {
        var host = host();
        var original = new IOException("grant flush failed");
        var process = new Child() {
            @Override public OutputStream getOutputStream() {
                return new ByteArrayOutputStream() {
                    @Override public void flush() throws IOException { throw original; }
                };
            }
        };
        var error = assertThrows(IOException.class, () -> host.launchAndObserve(() -> process));
        assertSame(original, error.getCause());
        assertEquals(1, process.kills);
        assertFalse(process.isAlive());
        assertThrows(IllegalStateException.class, () -> host.write("host-later.txt", new byte[1]));
    }

    @Test
    void activeTimeoutUsesOnlyOriginalCleanupReserveAndCannotReportSuccess() throws Exception {
        var host = host();
        var process = new Child() {
            @Override public boolean waitFor(long amount, TimeUnit unit) {
                if (kills == 0) {
                    clock.set(TimeUnit.SECONDS.toNanos(7));
                    return false;
                }
                return super.waitFor(amount, unit);
            }
        };
        assertThrows(IOException.class, () -> host.launchAndObserve(() -> process));
        assertEquals(1, process.kills);
        assertEquals(TimeUnit.SECONDS.toNanos(3), process.waitNanos);
        assertFalse(Files.exists(directory.resolve("host-tracer.txt")));
    }

    @Test
    void terminalCleanupErrorDoesNotReplaceOriginalFailure() throws Exception {
        var host = host();
        var process = new Child() {
            @Override public OutputStream getOutputStream() { throw new IllegalStateException("original grant error"); }
            @Override public Process destroyForcibly() { throw new IllegalArgumentException("cleanup failed"); }
        };
        var error = assertThrows(IOException.class, () -> host.launchAndObserve(() -> process));
        assertEquals("original grant error", error.getCause().getMessage());
        assertEquals("cleanup failed", error.getSuppressed()[0].getMessage());
    }

    @Test
    void unobservedTerminalKeepsOriginalErrorAndNeverStartsAgain() throws Exception {
        var host = host();
        var process = new Child() {
            @Override public OutputStream getOutputStream() { throw new IllegalStateException("original grant error"); }
            @Override public boolean waitFor(long amount, TimeUnit unit) {
                waitNanos = unit.toNanos(amount);
                return false;
            }
        };
        var error = assertThrows(IOException.class, () -> host.launchAndObserve(() -> process));
        assertEquals("original grant error", error.getCause().getMessage());
        assertEquals("direct tracer terminal cleanup unobserved", error.getSuppressed()[0].getMessage());
        assertEquals(TimeUnit.SECONDS.toNanos(10), process.waitNanos);
        assertThrows(IllegalStateException.class, () -> host.launchAndObserve(Child::new));
        assertFalse(Files.exists(directory.resolve("host-tracer.txt")));
    }

    @Test
    void blockedGrantDoesNotBlockHostDeadlineAndIsNeverClosedForRetry() throws Exception {
        var host = new FixedWitnessWriterHost(directory.toRealPath(), System::nanoTime,
                TimeUnit.SECONDS.toNanos(4));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var finished = new CountDownLatch(1);
        var closes = new AtomicInteger();
        var process = new Child() {
            @Override public OutputStream getOutputStream() {
                return new ByteArrayOutputStream() {
                    @Override public void flush() throws IOException {
                        entered.countDown();
                        try {
                            release.await();
                            throw new IOException("closed by original host timeout");
                        } catch (InterruptedException error) {
                            throw new IOException(error);
                        } finally {
                            finished.countDown();
                        }
                    }
                    @Override public void close() { closes.incrementAndGet(); }
                };
            }
            @Override public Process destroyForcibly() {
                super.destroyForcibly();
                release.countDown();
                return this;
            }
        };
        try {
            assertThrows(IOException.class, () -> host.launchAndObserve(() -> process));
            assertEquals(0, entered.getCount());
            assertTrue(finished.await(2, TimeUnit.SECONDS));
            assertEquals(1, process.kills);
            assertFalse(process.isAlive());
            assertEquals(0, closes.get());
        } finally {
            release.countDown();
        }
    }

    @Test
    void nonzeroExitNeverWritesSuccessSuffix() throws Exception {
        var host = host();
        var process = new Child() { @Override public int exitValue() { return 125; } };
        assertThrows(IOException.class, () -> host.launchAndObserve(() -> process));
        assertFalse(Files.exists(directory.resolve("host-tracer.txt")));
    }

    @Test
    void startReturningAfterTimeoutIsStillKilledWithoutGrant() throws Exception {
        var host = new FixedWitnessWriterHost(directory.toRealPath(), System::nanoTime,
                TimeUnit.SECONDS.toNanos(4));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var killed = new CountDownLatch(1);
        var process = new Child() {
            @Override public Process destroyForcibly() {
                super.destroyForcibly();
                killed.countDown();
                return this;
            }
        };
        try {
            assertThrows(IOException.class, () -> host.launchAndObserve(() -> {
                entered.countDown();
                release.await();
                return process;
            }));
            assertEquals(0, entered.getCount());
        } finally {
            release.countDown();
        }
        assertTrue(killed.await(2, TimeUnit.SECONDS));
        assertEquals(0, process.input.size());
        assertFalse(Files.exists(directory.resolve("host-tracer.txt")));
    }

    private static class Child extends Process {
        final ByteArrayOutputStream input = new ByteArrayOutputStream();
        volatile boolean alive = true;
        int kills;
        long waitNanos;
        @Override public OutputStream getOutputStream() { return input; }
        @Override public InputStream getInputStream() { return InputStream.nullInputStream(); }
        @Override public InputStream getErrorStream() { return InputStream.nullInputStream(); }
        @Override public int waitFor() { throw new AssertionError("unbounded wait forbidden"); }
        @Override public boolean waitFor(long amount, TimeUnit unit) {
            waitNanos = unit.toNanos(amount);
            alive = false;
            return true;
        }
        @Override public int exitValue() { return 0; }
        @Override public void destroy() { throw new AssertionError("TERM forbidden"); }
        @Override public Process destroyForcibly() { kills++; return this; }
        @Override public boolean isAlive() { return alive; }
        @Override public long pid() { return 1; }
    }
}
