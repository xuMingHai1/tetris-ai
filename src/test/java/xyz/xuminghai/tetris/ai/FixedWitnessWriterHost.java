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

/**
 * Trusted host writer allocation and one-shot direct-child pipe handoff. This
 * has no launch CLI, source authentication, execution permit or native probe.
 * The future whole host must start its hard deadline before launching the child.
 */
final class FixedWitnessWriterHost {
    static final int ROOT_BYTES = 10 * 1024 * 1024;
    static final int REPORT_BYTES = 32 * 1024;
    static final int COORDINATOR_BYTES = ROOT_BYTES - 2 * REPORT_BYTES;

    private final Path directory;
    private int charged;
    private boolean issued;
    private boolean failed;

    FixedWitnessWriterHost(Path directory) throws IOException {
        if (!directory.isAbsolute() || !directory.equals(directory.toRealPath())
                || !Files.isDirectory(directory)) {
            throw new IllegalArgumentException("canonical session directory required");
        }
        this.directory = directory;
        write("host-incomplete.txt", "INCOMPLETE\n".getBytes(StandardCharsets.US_ASCII));
    }

    /**
     * Permanently issue both child partitions through this direct child's stdin.
     * Failed writes/close consume the attempt. Neither child partition has a
     * host-side writer or a refund path; the host may write only its own 32KiB.
     * The caller retains the earlier hard deadline and kills/reaps on failure.
     */
    synchronized void handoff(Process tracer) throws IOException {
        if (issued || failed) {
            throw new IllegalStateException("writer handoff already attempted");
        }
        issued = true;
        try {
            long parent = ProcessHandle.current().pid();
            if (!tracer.isAlive() || tracer.pid() <= 0 || tracer.pid() == parent) {
                throw new IllegalArgumentException("live direct tracer required");
            }
            var bytes = ("FIXED_WRITERS_V1 " + parent + " " + tracer.pid() + " "
                    + ROOT_BYTES + " " + REPORT_BYTES + " " + REPORT_BYTES + " "
                    + COORDINATOR_BYTES + "\n").getBytes(StandardCharsets.US_ASCII);
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
