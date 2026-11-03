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

/** Verifies artificial failures and a real clean build; never executes historical inputs. */
@EnabledOnOs(OS.LINUX)
class FixedWitnessPrepareTest {
    @TempDir Path directory;

    @Test
    void pythonPreparationFailuresRunInTheRegularSuite() throws Exception {
        var log = directory.resolve("prepare-tests.log");
        var process = new ProcessBuilder("python3", "-m", "unittest", "discover", "-v",
                "-s", "scripts", "-p", "test_fixed_witness_replay_prepare.py")
                .directory(Path.of("").toAbsolutePath().toFile())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(45, TimeUnit.SECONDS), "artificial prepare tests timed out");
            var output = Files.readString(log);
            assertEquals(0, process.exitValue(), output);
            assertTrue(output.contains("Ran 21 tests"), output);
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

    @Test
    void realCleanWrapperClasspathIsExtractedAndChargedBeforeRetention() throws Exception {
        var script = """
                import pathlib, subprocess, sys, time
                source, base, jdk = map(pathlib.Path, sys.argv[1:])
                checkout, output = base / 'checkout', base / 'output'
                sha = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=source, timeout=10).decode().strip()
                subprocess.run(['git', 'clone', '--quiet', '--no-hardlinks', '--no-checkout',
                                str(source), str(checkout)], check=True, timeout=30)
                subprocess.run(['git', 'checkout', '--quiet', '--detach', sha], cwd=checkout, check=True, timeout=10)
                output.mkdir()
                sys.path.insert(0, str(checkout / 'scripts'))
                from fixed_witness_replay_prepare import FAILURE_BYTES, prepare, verify_prepared
                from fixed_witness_replay_process import OutputBudget
                budget = OutputBudget()
                receipt = prepare(checkout, jdk, output, budget, total_deadline=time.monotonic() + 650)
                manifest = verify_prepared(checkout, jdk, receipt)
                assert manifest['state'] == 'PREPARED' and not manifest['diagnostic_started']
                assert manifest['source']['source_sha'] == sha
                paths = manifest['classpath']['classpath'][2:]
                assert paths and (output / 'classpath.txt').read_bytes() == (':'.join(paths) + '\\n').encode('utf-8')
                artifacts = sum(p.stat().st_size for p in output.iterdir() if p.is_file())
                assert budget.quota.charged == FAILURE_BYTES + artifacts
                assert all(p['state'] == 'EXITED' and p['returncode'] == 0 for p in manifest['processes'])
                print('ACTUAL_CLEAN_BUILD_CLASSPATH_QUOTA_OK')
                """;
        var log = directory.resolve("real-prepare.log");
        var process = new ProcessBuilder("python3", "-c", script, Path.of("").toAbsolutePath().toString(),
                directory.toString(), Path.of(System.getProperty("java.home")).toRealPath().toString())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(700, TimeUnit.SECONDS), "real clean preparation timed out");
            var output = Files.readString(log);
            assertEquals(0, process.exitValue(), output);
            assertTrue(output.contains("ACTUAL_CLEAN_BUILD_CLASSPATH_QUOTA_OK"), output);
        } finally {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            if (process.isAlive()) {
                process.destroyForcibly();
                assertTrue(process.waitFor(5, TimeUnit.SECONDS), "real preparation process did not stop");
            }
        }
    }

    @Test
    void nativeNetworkDenialAndFailureCasesRunWithoutSkips() throws Exception {
        var log = directory.resolve("network-tests.log");
        var process = new ProcessBuilder("python3", "-B", "-c", "import sys; sys.path.insert(0,'scripts'); "
                + "from test_fixed_witness_replay_network import component_suite; component_suite()")
                .directory(Path.of("").toAbsolutePath().toFile())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(55, TimeUnit.SECONDS), "bounded network suite timed out");
            var output = Files.readString(log);
            assertEquals(0, process.exitValue(), output);
            assertTrue(output.contains("Ran 17 tests"), output);
            assertTrue(output.contains("\nOK\n"), output);
            assertFalse(output.contains("skipped"), output);
            System.out.println("ACTUAL_NETWORK_NATIVE_ABI_THREAD_FORK_FAILURES_OK: 17 tests");
        } finally {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            if (process.isAlive()) {
                process.destroyForcibly();
                assertTrue(process.waitFor(5, TimeUnit.SECONDS), "network suite did not stop");
            }
        }
    }
}
