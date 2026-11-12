/*
 * Copyright (C) 2024-2026 xuMingHai
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * Trusted host writer allocation and one-shot direct-child pipe handoff. This
 * has no launch CLI, whole-toolchain certificate, execution permit or native probe.
 * Launch supervision retains the earlier host ceiling; direct-child terminal
 * observation is never evidence that all tracees or the whole suite completed.
 */
final class FixedWitnessWriterHost {
    static final int ROOT_BYTES = 10 * 1024 * 1024;
    static final int REPORT_BYTES = 32 * 1024;
    static final int COORDINATOR_BYTES = ROOT_BYTES - 2 * REPORT_BYTES;

    private final Path directory;
    private final LongSupplier clock;
    private final long started;
    private final long limit;
    private static final long CLEANUP_NANOS = TimeUnit.SECONDS.toNanos(3);
    private boolean launchAttempted;
    private int charged;
    private boolean issued;
    private volatile boolean failed;

    FixedWitnessWriterHost(Path directory) throws IOException {
        this(directory, System::nanoTime, TimeUnit.SECONDS.toNanos(900));
    }

    /** Begin timing before creating the exclusive session; never reuse a prefix. */
    static FixedWitnessWriterHost createSession(Path directory) throws IOException {
        long started = System.nanoTime();
        if (!directory.isAbsolute()) {
            throw new IllegalArgumentException("absolute new session required");
        }
        Files.createDirectory(directory);
        return new FixedWitnessWriterHost(directory, System::nanoTime, started, TimeUnit.SECONDS.toNanos(900));
    }

    /** Smaller ceilings and an injected clock are for synthetic tests only. */
    FixedWitnessWriterHost(Path directory, LongSupplier clock, long limit) throws IOException {
        this(directory, clock, clock.getAsLong(), limit);
    }

    private FixedWitnessWriterHost(Path directory, LongSupplier clock, long started, long limit) throws IOException {
        this.clock = clock;
        this.started = started;
        if (limit <= CLEANUP_NANOS || limit > TimeUnit.SECONDS.toNanos(900)) {
            throw new IllegalArgumentException("original host ceiling required");
        }
        this.limit = limit;
        if (!directory.isAbsolute() || !directory.equals(directory.toRealPath())
                || !Files.isDirectory(directory)) {
            throw new IllegalArgumentException("canonical session directory required");
        }
        this.directory = directory;
        remaining(CLEANUP_NANOS);
        write("host-incomplete.txt", "INCOMPLETE\n".getBytes(StandardCharsets.US_ASCII));
        remaining(CLEANUP_NANOS);
    }

    /**
     * Permanently issue both child partitions through this direct child's stdin.
     * Failed writes/close consume the attempt. Neither child partition has a
     * host-side writer or a refund path; the host may write only its own 32KiB.
     * The caller retains the earlier hard deadline and kills/reaps on failure.
     */
    synchronized void handoff(Process tracer) throws IOException {
        handoff(tracer, false);
    }

    private synchronized void handoff(Process tracer, boolean supervised) throws IOException {
        if (issued || failed || (launchAttempted && !supervised)) {
            throw new IllegalStateException("writer handoff already attempted");
        }
        issued = true;
        try {
            long parent = ProcessHandle.current().pid();
            if (!tracer.isAlive() || tracer.pid() <= 0 || tracer.pid() == parent) {
                throw new IllegalArgumentException("live direct tracer required");
            }
            var bytes = ((supervised ? "FIXED_WRITERS_V2 " : "FIXED_WRITERS_V1 ") + parent + " " + tracer.pid() + " "
                    + ROOT_BYTES + " " + REPORT_BYTES + " " + REPORT_BYTES + " "
                    + COORDINATOR_BYTES + (supervised ? " " + remaining(CLEANUP_NANOS) + " 1 1" : "")
                    + "\n").getBytes(StandardCharsets.US_ASCII);
            var input = tracer.getOutputStream();
            // Process stdin may buffer internally. A failed write/flush must not be
            // retried by close; the caller must terminate/reap the child instead.
            input.write(bytes);
            input.flush();
            input.close();
        } catch (IOException | RuntimeException | Error error) {
            failed = true;
            throw error;
        }
    }

    /**
     * Start once, send the original remaining time/one reserved tracer slot,
     * and observe the direct child's terminal state. Startup and grant I/O run
     * on a host virtual thread so a blocked start/flush cannot reset the timer.
     * Stdout/stderr are discarded, never inherited outside the writer quota.
     * The trusted caller must authenticate the command before calling this;
     * this method does not authorize or implement the manual acceptance entry.
     */
    int launchAndObserve(ProcessBuilder command) throws IOException, InterruptedException {
        command.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        command.redirectError(ProcessBuilder.Redirect.DISCARD);
        command.redirectInput(ProcessBuilder.Redirect.PIPE);
        command.redirectErrorStream(false);
        return launchAndObserve(command::start);
    }

    /**
     * Bind a retained command to this session, with no ambient environment or
     * caller-owned mutable ProcessBuilder. Hashes must be retained independently
     * by the trusted caller, not collected from the child. Recheck selected bytes
     * before start, before grant and after terminal observation, on the same
     * bounded worker. This is cooperative continuity, not complete Python/JDK
     * authentication or protection against concurrent change-and-restore.
     */
    int launchAndObserve(RetainedCommand command) throws IOException, InterruptedException {
        return launchAndObserve(command, () -> {
            var builder = new ProcessBuilder(command.arguments());
            builder.directory(command.cwd().toFile());
            builder.environment().clear();
            builder.environment().putAll(command.environment());
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            builder.redirectError(ProcessBuilder.Redirect.DISCARD);
            return builder.start();
        });
    }

    // Inject only process creation in synthetic continuity/cleanup tests.
    int launchAndObserve(RetainedCommand command, Callable<Process> start) throws IOException, InterruptedException {
        return launchAndObserve(() -> {
            command.recheck(this);
            return start.call();
        }, () -> command.recheck(this));
    }

    /** Immutable caller receipt; selecting the complete trusted input set is still a prerequisite. */
    record RetainedCommand(List<String> arguments, Path cwd, Map<String, String> environment,
                           Map<Path, String> files) {
        RetainedCommand {
            arguments = List.copyOf(arguments);
            environment = Map.copyOf(environment);
            files = Map.copyOf(files);
            if (arguments.size() != 5 || !arguments.get(1).equals("-I") || !arguments.get(2).equals("-B")
                    || !cwd.isAbsolute() || !cwd.equals(cwd.normalize())
                    || !files.containsKey(Path.of(arguments.getFirst()))
                    || !files.containsKey(Path.of(arguments.get(3)))
                    || arguments.stream().anyMatch(value -> value.isEmpty() || value.indexOf('\0') >= 0)
                    || environment.keySet().stream().anyMatch(key -> !List.of("HOME", "TMPDIR", "LANG", "LC_ALL", "TZ").contains(key))
                    || environment.entrySet().stream().anyMatch(entry -> entry.getValue().indexOf('\0') >= 0)
                    || files.entrySet().stream().anyMatch(entry -> !entry.getKey().isAbsolute()
                        || !entry.getValue().matches("[0-9a-f]{64}"))) {
                throw new IllegalArgumentException("retained isolated Python argv/environment/file hashes required");
            }
        }

        private void recheck(FixedWitnessWriterHost host) throws IOException {
            host.remaining(CLEANUP_NANOS);
            if (!arguments.getLast().equals(host.directory.toString())) {
                throw new IOException("retained tracer session mismatch");
            }
            if (!cwd.equals(cwd.toRealPath()) || !Files.isDirectory(cwd)) {
                throw new IOException("retained tracer cwd drift");
            }
            for (var entry : files.entrySet()) {
                var path = entry.getKey();
                if (!path.equals(path.toRealPath()) || !Files.isRegularFile(path)) {
                    throw new IOException("retained tracer file drift");
                }
                MessageDigest digest;
                try {
                    digest = MessageDigest.getInstance("SHA-256");
                } catch (NoSuchAlgorithmException error) {
                    throw new IllegalStateException(error);
                }
                try (var input = Files.newInputStream(path)) {
                    var buffer = new byte[8192];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        host.remaining(CLEANUP_NANOS);
                        digest.update(buffer, 0, count);
                    }
                }
                host.remaining(CLEANUP_NANOS);
                if (!entry.getValue().equals(HexFormat.of().formatHex(digest.digest()))) {
                    throw new IOException("retained tracer bytes drift");
                }
            }
            host.remaining(CLEANUP_NANOS);
        }
    }

    // Fake Process transports exercise failure ordering without native probes.
    int launchAndObserve(Callable<Process> start) throws IOException, InterruptedException {
        return launchAndObserve(start, () -> { });
    }

    private int launchAndObserve(Callable<Process> start, CheckedContinuity continuity) throws IOException, InterruptedException {
        synchronized (this) {
            if (launchAttempted || issued || failed) {
                throw new IllegalStateException("host tracer launch already consumed/unavailable");
            }
            launchAttempted = true; // Permanent tracer attempt/live reservation BEFORE start.
        }
        var child = new AtomicReference<Process>();
        var cancelled = new AtomicBoolean();
        var killed = new AtomicBoolean();
        var finished = new CompletableFuture<Integer>();
        Runnable stop = () -> {
            var process = child.get();
            if (process != null && process.isAlive() && killed.compareAndSet(false, true)) {
                process.destroyForcibly();
            }
        };
        remaining(CLEANUP_NANOS);
        Thread.startVirtualThread(() -> {
            try {
                remaining(CLEANUP_NANOS);
                var process = start.call();
                child.set(process); // Retain before every post-start gate and grant write.
                if (cancelled.get()) {
                    stop.run();
                    long left = limit - (clock.getAsLong() - started);
                    if (left > 0) {
                        process.waitFor(left, TimeUnit.NANOSECONDS);
                    }
                    throw new IOException("late tracer launch; terminal observation unavailable");
                }
                remaining(CLEANUP_NANOS);
                continuity.recheck();
                handoff(process, true);
                if (!process.waitFor(remaining(CLEANUP_NANOS), TimeUnit.NANOSECONDS)) {
                    throw new IOException("host tracer active deadline exhausted");
                }
                remaining(CLEANUP_NANOS);
                continuity.recheck();
                finished.complete(process.exitValue());
            } catch (Throwable error) {
                finished.completeExceptionally(error);
            }
        });
        boolean interrupted = false;
        boolean observed = false;
        Throwable primary = null;
        try {
            int exit = finished.get(remaining(CLEANUP_NANOS), TimeUnit.NANOSECONDS);
            if (exit != 0) {
                throw new IOException("tracer exited unsuccessfully: " + exit);
            }
            remaining(CLEANUP_NANOS);
            write("host-tracer.txt", "DIRECT_TRACER_EXIT_0\nSUITE_UNVERIFIED\n".getBytes(StandardCharsets.US_ASCII));
            remaining(0);
            observed = true;
            return exit;
        } catch (ExecutionException | TimeoutException error) {
            var failure = new IOException("host tracer launch/observation failed",
                    error instanceof ExecutionException ? error.getCause() : error);
            primary = failure;
            throw failure;
        } catch (InterruptedException error) {
            interrupted = true;
            primary = error;
            throw error;
        } catch (IOException | RuntimeException | Error error) {
            primary = error;
            throw error;
        } finally {
            if (!observed) {
                failed = true;
            }
            cancelled.set(true);
            // No extra cleanup window. A late start is killed by the worker;
            // unknown/pending startup can never be claimed as reaped here.
            try {
                stop.run();
                var process = child.get();
                if (process != null && process.isAlive()) {
                    long left = limit - (clock.getAsLong() - started);
                    if (left <= 0 || !process.waitFor(left, TimeUnit.NANOSECONDS)) {
                        throw new IOException("direct tracer terminal cleanup unobserved");
                    }
                }
            } catch (IOException | InterruptedException | RuntimeException | Error error) {
                if (error instanceof InterruptedException) {
                    interrupted = true;
                }
                if (primary != null) {
                    primary.addSuppressed(error);
                } else {
                    throw error;
                }
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    @FunctionalInterface
    private interface CheckedContinuity {
        void recheck() throws IOException;
    }

    private long remaining(long reserve) throws IOException {
        long elapsed = clock.getAsLong() - started;
        if (elapsed < 0 || elapsed >= limit - reserve) {
            throw new IOException("original host deadline exhausted");
        }
        return limit - reserve - elapsed;
    }

    /** Charge before exclusive raw I/O; no overwrite, buffering retry or refund. */
    synchronized void write(String name, byte[] bytes) throws IOException {
        if (failed) {
            throw new IllegalStateException("host writer previously failed");
        }
        try {
            if (!name.matches("host-[a-z-]+\\.(txt|json)") || bytes.length > REPORT_BYTES - charged) {
                throw new IllegalArgumentException("host report name/allowance required");
            }
            charged += bytes.length;
            try (var channel = FileChannel.open(directory.resolve(name), StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE)) {
                if (channel.write(ByteBuffer.wrap(bytes)) != bytes.length) {
                    throw new IOException("short host evidence write");
                }
                channel.force(true);
            }
        } catch (IOException | RuntimeException | Error error) {
            failed = true;
            throw error;
        }
    }

    synchronized int charged() {
        return charged;
    }
}
