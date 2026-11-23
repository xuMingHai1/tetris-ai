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
        var fixtures = new FrozenResearchCheckout(directory);
        var fixture = fixtures.create(source, directory.resolve("checkout"));
        var checkout = fixture.checkout();
        var sha = fixture.sha();
        fixtures.rejectsDrift(fixture);
        var mapper = new ObjectMapper();
        var output = directory.resolve("preflight");
        run(checkout, List.of("python3", "scripts/drive_goal_window.py", "--root", checkout.toString(),
                "--output", output.toString()), "preflight");
        var report = mapper.readTree(Files.readString(output.resolve("report.json")));
        assertEquals("VERIFIED", report.get("status").asText());
        assertFalse(report.get("population_started").asBoolean());
        assertFalse(report.get("runtime_permission").asBoolean());
        assertEquals(sha, report.get("build").get("source_sha").asText());
        assertNotEquals(fixture.originSha(), sha, "A fixture must not certify the product SHA");
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
    void fixturePreservesCurrentMeasurementAdditionsAndDeletionsWithKnownProductDrift() throws Exception {
        var product = directory.resolve("product");
        run(source, List.of("git", "clone", "--quiet", "--no-hardlinks", source.toString(), product.toString()),
                "product-clone");
        var fixtures = new FrozenResearchCheckout(directory);
        var production = "src/main/java/xyz/xuminghai/tetris/ai/ActionPlanSimulator.java";
        var companion = "src/test/java/xyz/xuminghai/tetris/ai/ActionPlanSimulatorTest.java";
        for (var path : List.of(production, companion)) {
            Files.writeString(product.resolve(path), "\n// Known product API evolution fixture\n",
                    java.nio.file.StandardOpenOption.APPEND);
        }
        var measurement = "src/main/java/xyz/xuminghai/tetris/ai/benchmark/";
        Files.writeString(product.resolve(measurement + "FixtureMeasurement.java"), "// Current measurement addition\n");
        Files.delete(product.resolve(measurement + "BenchmarkApplication.java"));
        fixtures.git(product, "add", "--all");
        fixtures.git(product, "-c", "user.name=Fixture test", "-c", "user.email=fixture@example.invalid",
                "-c", "commit.gpgsign=false", "commit", "--quiet", "-m", "Synthetic product evolution");
        var fixture = fixtures.create(product, directory.resolve("fixture"));
        assertNotEquals(fixture.originSha(), fixture.sha());
        assertTrue(Files.exists(fixture.checkout().resolve(measurement + "FixtureMeasurement.java")));
        assertFalse(Files.exists(fixture.checkout().resolve(measurement + "BenchmarkApplication.java")));
        var manifest = new ObjectMapper().readTree(Files.readString(directory.resolve("fixture-origin.json")));
        assertEquals(fixture.originSha(), manifest.get("origin_product_sha").asText());
        assertEquals(fixture.sha(), manifest.get("fixture_sha").asText());
        assertEquals(fixture.tree(), manifest.get("fixture_tree").asText());
        assertTrue(manifest.get("replacements").has(production));
        assertTrue(manifest.get("replacements").has(companion));
        assertFalse(manifest.get("product_certified").asBoolean());
        fixtures.rejectsDrift(fixture);
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
