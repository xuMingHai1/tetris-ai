/*
 * Copyright (C) 2024-2026 xuMingHai
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Checks real Linux proc observation using an artificial Python child, never a witness. */
@EnabledOnOs(OS.LINUX)
class FixedWitnessObservationTest {
    @TempDir Path directory;

    @Test
    void artificialProcessObservationRunsInTheRegularSuite() throws Exception {
        var log = directory.resolve("observation-tests.log");
        var process = new ProcessBuilder("python3", "-m", "unittest", "discover", "-v",
                "-s", "scripts", "-p", "test_fixed_witness_replay_observation.py")
                .directory(Path.of("").toAbsolutePath().toFile())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(45, TimeUnit.SECONDS), "artificial observation tests timed out");
            var output = Files.readString(log);
            assertEquals(0, process.exitValue(), output);
            assertTrue(output.contains("Ran 13 tests"), output);
            assertTrue(output.contains("\nOK\n"), output);
            assertFalse(output.contains("skipped"), output);
        } finally {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            if (process.isAlive()) {
                process.destroyForcibly();
                assertTrue(process.waitFor(5, TimeUnit.SECONDS), "test process did not stop");
            }
        }
    }
}
