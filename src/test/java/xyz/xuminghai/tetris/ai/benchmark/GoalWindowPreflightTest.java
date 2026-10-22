/*
 * Copyright (C) 2024-2026 xuMingHai
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai.benchmark;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Runs the real clean Wrapper -> ordinary classpath JVM path, without warmup or population games. */
@EnabledOnOs(OS.LINUX)
class GoalWindowPreflightTest {
    @TempDir Path directory;
    private final Path source = Path.of("").toAbsolutePath();

    @Test
    void cleanCheckoutBuildAndOrdinaryJvmVerifyActualClassOriginsWithoutGames() throws Exception {
        var checkout = directory.resolve("checkout");
        var sha = run(source, List.of("git", "rev-parse", "HEAD"), "source-sha").trim();
        run(source, List.of("git", "clone", "--quiet", "--no-hardlinks", "--no-checkout",
                source.toString(), checkout.toString()), "clone");
        run(checkout, List.of("git", "checkout", "--quiet", "--detach", sha), "checkout");
        var mapper = new ObjectMapper();
        var protocol = mapper.readTree(Files.readString(checkout.resolve("docs/research/heart-acceptance-v1.json")));
        var base = protocol.get("production_base_sha").asText();
        // Build & Test may have a shallow checkout. Fetch only the protocol's pinned public commit if absent.
        var probe = new ProcessBuilder("git", "cat-file", "-e", base + "^{commit}")
                .directory(checkout.toFile()).redirectErrorStream(true)
                .redirectOutput(directory.resolve("base-probe.log").toFile()).start();
        assertTrue(probe.waitFor(10, TimeUnit.SECONDS), "git base probe timed out");
        if (probe.exitValue() != 0) {
            run(checkout, List.of("git", "fetch", "--no-tags", "--depth=1",
                    "https://github.com/xuMingHai1/tetris-ai.git", base), "pinned-base-fetch");
        }
        var output = directory.resolve("preflight");
        run(checkout, List.of("python3", "scripts/drive_goal_window.py", "--root", checkout.toString(),
                "--output", output.toString()), "preflight");
        var report = mapper.readTree(Files.readString(output.resolve("report.json")));
        assertEquals("VERIFIED", report.get("status").asText());
        assertFalse(report.get("population_started").asBoolean());
        assertFalse(report.get("runtime_permission").asBoolean());
        assertEquals(sha, report.get("build").get("source_sha").asText());
        assertEquals(0, report.get("process").get("returncode").asInt());
        assertEquals(300, report.get("build_process").get("timeout_seconds").asInt());
        assertEquals(120, report.get("process").get("timeout_seconds").asInt());
        var lines = Files.readAllLines(output.resolve("raw.jsonl"));
        assertEquals(2, lines.size());
        var provenance = mapper.readTree(lines.getFirst());
        assertEquals("provenance_verified", provenance.get("type").asText());
        assertTrue(provenance.get("data").get("loaded_classes").size() > 0);
        assertEquals("verification_complete", mapper.readTree(lines.getLast()).get("type").asText());
        assertTrue(Files.size(output.resolve("build.log")) > 0);
        assertTrue(Files.size(output.resolve("build-console.log")) > 0);
        assertTrue(Files.size(output.resolve("dependency-tree.txt")) > 0);
        assertTrue(Files.isRegularFile(output.resolve("stdout.log")));
        assertTrue(Files.isRegularFile(output.resolve("stderr.log")));
    }

    @Test
    void pythonProvenanceAndProcessFailureCasesRunInTheRegularSuite() throws Exception {
        var output = run(source, List.of("python3", "-m", "unittest", "discover", "-s", "scripts",
                "-p", "test_*goal_window*.py"), "python-tests");
        assertTrue(output.contains("OK"), output);
    }

    private String run(Path cwd, List<String> command, String name) throws Exception {
        var log = directory.resolve(name + ".log");
        var builder = new ProcessBuilder(command).directory(cwd.toFile()).redirectErrorStream(true)
                .redirectOutput(log.toFile());
        builder.environment().put("JAVA_HOME", System.getProperty("java.home"));
        var process = builder.start();
        if (!process.waitFor(8, TimeUnit.MINUTES)) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "test process did not stop");
            fail("Process timed out: " + command + "\n" + Files.readString(log));
        }
        var output = Files.readString(log);
        assertEquals(0, process.exitValue(), () -> command + "\n" + output);
        return output;
    }
}
