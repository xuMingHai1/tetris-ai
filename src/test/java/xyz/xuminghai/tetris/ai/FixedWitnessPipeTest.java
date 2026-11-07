/*
 * Copyright (C) 2024-2026 xuMingHai
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Actual JVM pipes and bounded artificial execution; no historical action replay. */
@EnabledOnOs(OS.LINUX)
class FixedWitnessPipeTest {
    @TempDir Path directory;

    @Test
    void artificialPipeFailuresRunInRegularSuite() throws Exception {
        var text = python(List.of("-m", "unittest", "discover", "-v", "-s", "scripts",
                "-p", "test_fixed_witness_replay_pipe.py"), "suite", 45);
        assertTrue(text.contains("Ran 19 tests"), text);
        assertTrue(text.contains("\nOK\n"), text);
        assertFalse(text.contains("skipped"), text);
        var report = python(List.of("-m", "unittest", "discover", "-v", "-s", "scripts",
                "-p", "test_fixed_witness_report_pipe.py"), "report-suite", 30);
        assertTrue(report.contains("Ran 8 tests") && report.contains("\nOK\n"), report);
        assertFalse(report.contains("skipped"), report);
        var execution = python(List.of("-m", "unittest", "discover", "-v", "-s", "scripts",
                "-p", "test_fixed_witness_replay_execution_evidence.py"), "execution-evidence-suite", 30);
        assertTrue(execution.contains("Ran 3 tests") && execution.contains("\nOK\n"), execution);
        assertFalse(execution.contains("skipped"), execution);
        var handshake = python(List.of("-m", "unittest", "discover", "-v", "-s", "scripts",
                "-p", "test_fixed_witness_replay_execution.py"), "execution-handshake-suite", 30);
        assertTrue(handshake.contains("Ran 2 tests") && handshake.contains("\nOK\n"), handshake);
        assertFalse(handshake.contains("skipped"), handshake);
        var registration = python(List.of("-m", "unittest", "discover", "-v", "-s", "scripts",
                "-p", "test_fixed_witness_replay_registration.py"), "registration-suite", 30);
        assertTrue(registration.contains("Ran 8 tests") && registration.contains("\nOK\n"), registration);
        assertFalse(registration.contains("skipped"), registration);
    }

    @Test
    void realVerifyOnlyJvmExchangesAuthenticatedAckAndEof() throws Exception {
        var root = Path.of("").toRealPath();
        var entries = new LinkedHashSet<String>();
        for (var key : List.of("jdk.module.path", "surefire.test.class.path")) {
            var value = System.getProperty(key);
            assertNotNull(value, key);
            entries.addAll(List.of(value.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))));
        }
        var dependencies = entries.stream().filter(value -> value.endsWith(".jar"))
                .map(Path::of).map(path -> {
                    try { return path.toRealPath().toString(); }
                    catch (java.io.IOException error) { throw new IllegalArgumentException(error); }
                }).distinct().toList();
        assertFalse(dependencies.isEmpty());
        var output = Files.createDirectory(directory.resolve("artificial-jvm"));
        var spec = directory.resolve("spec.json");
        Files.writeString(spec, JsonMapper.builder().build().writeValueAsString(Map.of(
                "root", root.toString(), "java", Path.of(System.getProperty("java.home")).toRealPath().toString(),
                "out", output.toString(), "dependencies", dependencies)));
        python(List.of("-c", "import json,sys; sys.path.insert(0,'scripts'); "
                + "from test_fixed_witness_replay_pipe import artificial_jvm; "
                + "from test_fixed_witness_report_pipe import artificial_report_jvm; "
                + "from test_fixed_witness_replay_execution import artificial_execution_jvm; "
                + "spec=json.load(open(sys.argv[1])); artificial_jvm(spec); artificial_report_jvm(spec); "
                + "artificial_execution_jvm(spec)",
                spec.toString()), "jvm", 120);
        assertTrue(Files.isRegularFile(output.resolve("artificial-result.json")));
        assertFalse(Files.exists(output.resolve("journal.jsonl")));
    }

    private String python(List<String> args, String name, int seconds) throws Exception {
        var command = new java.util.ArrayList<>(List.of("python3"));
        command.addAll(args);
        var log = directory.resolve(name + ".log");
        var process = new ProcessBuilder(command).directory(Path.of("").toAbsolutePath().toFile())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(seconds, TimeUnit.SECONDS), "pipe test timed out");
            var text = Files.readString(log);
            assertEquals(0, process.exitValue(), text);
            return text;
        } finally {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            if (process.isAlive()) {
                process.destroyForcibly();
                assertTrue(process.waitFor(5, TimeUnit.SECONDS), "pipe test did not stop");
            }
        }
    }
}
