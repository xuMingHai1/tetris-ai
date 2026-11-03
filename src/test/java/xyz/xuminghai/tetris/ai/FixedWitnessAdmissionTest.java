/*
 * Copyright (C) 2024-2026 xuMingHai
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import tools.jackson.databind.json.JsonMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Runs artificial parent admission counterexamples, without launching the diagnostic. */
@EnabledOnOs(OS.LINUX)
class FixedWitnessAdmissionTest {
    @TempDir Path directory;

    @Test
    void retainedClassSetMatchesRealJvmObservation() throws Exception {
        var root = Path.of("").toRealPath();
        var compiled = new LinkedHashMap<String, String>();
        for (var base : java.util.List.of("target/classes", "target/test-classes")) {
            try (var files = Files.walk(root.resolve(base))) {
                for (var file : files.filter(Files::isRegularFile).toList()) {
                    compiled.put(root.relativize(file).toString().replace('\\', '/'),
                            FixedWitnessInput.sha256(Files.readAllBytes(file)));
                }
            }
        }
        var evidence = directory.resolve("classes.json");
        assertTrue(compiled.containsKey("target/classes/xyz/xuminghai/tetris/ai/ActionStateSearch$1.class"));
        var entries = new java.util.LinkedHashSet<String>();
        for (var key : java.util.List.of("jdk.module.path", "surefire.test.class.path")) {
            var value = System.getProperty(key);
            assertNotNull(value, key);
            entries.addAll(java.util.List.of(value.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))));
        }
        var urls = entries.stream().map(Path::of).map(Path::toUri).map(uri -> {
            try {
                return uri.toURL();
            } catch (java.net.MalformedURLException error) {
                throw new IllegalArgumentException(error);
            }
        }).toArray(java.net.URL[]::new);
        // Surefire uses a named module; observe the entrypoint's ordinary loader topology.
        try (var loader = new java.net.URLClassLoader(urls, null)) {
            var type = Class.forName(FixedWitnessJvmObservation.class.getName(), false, loader);
            var inspect = type.getDeclaredMethod("inspect", Path.class);
            inspect.setAccessible(true);
            var observed = (Map<?, ?>) ((Map<?, ?>) inspect.invoke(null, root)).get("classes");
            assertTrue(observed.size() > 35);
            assertFalse(observed.containsKey("xyz.xuminghai.tetris.ai.ActionStateSearch$1"));
            Files.writeString(evidence, JsonMapper.builder().build().writeValueAsString(Map.of(
                    "root", root.toString(), "classpath", Map.of("compiled_files", compiled), "observed", observed)));
        }
        var log = directory.resolve("class-set.log");
        var process = new ProcessBuilder("python3", "-c",
                "import json,sys; from pathlib import Path; from fixed_witness_replay_admission import _classes; "
                        + "v=json.load(open(sys.argv[1])); assert _classes(v,Path(v['root']))==v['observed']",
                evidence.toString()).directory(root.resolve("scripts").toFile())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "class set comparison timed out");
            assertEquals(0, process.exitValue(), Files.readString(log));
        } finally {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            if (process.isAlive()) {
                process.destroyForcibly();
                assertTrue(process.waitFor(5, TimeUnit.SECONDS), "class set comparison did not stop");
            }
        }
    }

    @Test
    void artificialAdmissionRunsInTheRegularSuite() throws Exception {
        var log = directory.resolve("admission-tests.log");
        var process = new ProcessBuilder("python3", "-m", "unittest", "discover", "-v",
                "-s", "scripts", "-p", "test_fixed_witness_replay_admission.py")
                .directory(Path.of("").toAbsolutePath().toFile())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(45, TimeUnit.SECONDS), "artificial admission tests timed out");
            var output = Files.readString(log);
            assertEquals(0, process.exitValue(), output);
            assertTrue(output.contains("Ran 14 tests"), output);
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
