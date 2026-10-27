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

/** Authenticates a real clean build and ordinary JVM observations; never invokes the solver. */
@EnabledOnOs(OS.LINUX)
class TerminalCertificateBuildTest {
    @TempDir Path directory;
    private final Path source = Path.of("").toAbsolutePath();

    @Test
    void cleanBuildAuthenticatesPreflightAndRejectsChangedCompiledBytes() throws Exception {
        var checkout = directory.resolve("checkout");
        var sha = run(source, List.of("git", "rev-parse", "HEAD"), "sha").trim();
        run(source, List.of("git", "clone", "--quiet", "--no-hardlinks", "--no-checkout",
                source.toString(), checkout.toString()), "clone");
        run(checkout, List.of("git", "checkout", "--quiet", "--detach", sha), "checkout");
        var mapper = new ObjectMapper();
        var protocol = mapper.readTree(Files.readString(checkout.resolve("docs/research/heart-acceptance-v1.json")));
        var base = protocol.get("production_base_sha").asText();
        var probe = new ProcessBuilder("git", "cat-file", "-e", base + "^{commit}")
                .directory(checkout.toFile()).redirectErrorStream(true)
                .redirectOutput(directory.resolve("base-probe.log").toFile()).start();
        assertTrue(probe.waitFor(10, TimeUnit.SECONDS), "base probe timed out");
        if (probe.exitValue() != 0) {
            run(checkout, List.of("git", "fetch", "--no-tags", "--depth=1",
                    "https://github.com/xuMingHai1/tetris-ai.git", base), "base-fetch");
        }
        var evidence = directory.resolve("evidence");
        var supervised = mapper.readTree(run(checkout, List.of("python3", "scripts/supervise_terminal_certificate.py",
                "--root", checkout.toString(), "--output", evidence.toString()), "supervised"));
        assertEquals("VERIFIED", supervised.get("status").asText());
        assertFalse(supervised.get("diagnostic_started").asBoolean());
        var supervision = mapper.readTree(Files.readString(evidence.resolve("report.json")));
        assertTrue(supervision.get("provenance_authenticated").asBoolean());
        assertEquals(2, supervision.get("processes").size());
        var prepared = supervision.get("build");
        assertEquals(sha, prepared.get("source_sha").asText());
        var manifest = evidence.resolve("build/build-manifest.json");
        var verify = List.of("python3", "scripts/prepare_terminal_certificate_build.py", "--verify",
                manifest.toString(), "--manifest-sha256", prepared.get("manifest_sha256").asText());
        assertFalse(supervision.get("eligible_for_runtime_validation").asBoolean());
        var observed = mapper.readTree(Files.readString(evidence.resolve("build/preflight.json")));
        assertEquals(23, observed.get("loaded_classes").size());
        assertTrue(observed.get("loaded_classes").has("xyz.xuminghai.tetris.ai.TerminalCertificateApplication"));
        assertTrue(observed.get("loaded_classes").has("xyz.xuminghai.tetris.ai.TerminalCertificateOrder$StepFacts"));
        Files.writeString(checkout.resolve("target/classes/injected.properties"), "changed");
        var log = directory.resolve("changed.log");
        var process = new ProcessBuilder(verify).directory(checkout.toFile()).redirectErrorStream(true)
                .redirectOutput(log.toFile()).start();
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "changed-build verification timed out");
        assertNotEquals(0, process.exitValue());
        assertTrue(Files.readString(log).contains("stale/changed/extra compiled files"));
    }

    @Test
    void artificialProvenanceCounterexamplesRunInRegularSuite() throws Exception {
        assertTrue(run(source, List.of("python3", "-m", "unittest", "discover", "-s", "scripts",
                "-p", "test_prepare_terminal_certificate_build.py"), "python-tests").contains("OK"));
    }

    @Test
    void verifyOnlySupervisorProcessBoundariesRunInRegularSuite() throws Exception {
        assertTrue(run(source, List.of("python3", "-m", "unittest", "discover", "-s", "scripts",
                "-p", "test_supervise_terminal_certificate.py"), "supervisor-tests").contains("OK"));
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
