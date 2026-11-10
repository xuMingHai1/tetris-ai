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

/** Pure ABI identity matching; no exec authentication or native probe. */
@EnabledOnOs(OS.LINUX)
class FixedWitnessAbiModelTest {
    @TempDir Path directory;

    @Test
    void syntheticAbiMatchingRunsInTheRegularSuite() throws Exception {
        var log = directory.resolve("abi-model-tests.log");
        var process = new ProcessBuilder("python3", "-B", "-m", "unittest", "discover", "-v",
                "-s", "scripts", "-p", "test_fixed_witness_replay_abi_model.py")
                .directory(Path.of("").toAbsolutePath().toFile())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "synthetic ABI model tests timed out");
            var output = Files.readString(log);
            assertEquals(0, process.exitValue(), output);
            assertTrue(output.contains("Ran 18 tests"), output);
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
