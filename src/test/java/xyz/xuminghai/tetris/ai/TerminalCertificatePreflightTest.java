/*
 * Copyright (C) 2024-2026 xuMingHai
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import tools.jackson.databind.ObjectMapper;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Real ordinary-JVM probes and artificial shadow classes; no construction search. */
class TerminalCertificatePreflightTest {
    @TempDir Path directory;

    @Test
    @EnabledOnOs(OS.LINUX)
    void ordinaryJvmReportsActualOriginsAndHashesWithoutStartingDiagnostic() throws Exception {
        var result = probe(List.of("-Xmx512m"), null, "--verify-only", root().toString());
        assertEquals(0, result.code(), result.output());
        var json = new ObjectMapper().readTree(result.output());
        assertFalse(json.get("diagnostic_started").asBoolean());
        assertFalse(json.get("provenance_authenticated").asBoolean());
        assertFalse(json.get("eligible_for_runtime_validation").asBoolean());
        assertEquals(TerminalCertificatePreflight.MAIN.size() + TerminalCertificatePreflight.TEST.size(),
                json.get("loaded_classes").size());
        for (var value : json.get("loaded_classes")) {
            assertTrue(value.get("sha256").asText().matches("[a-f0-9]{64}"));
        }
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void rejectsShadowEvenWhenCorrectClassLoadsFirst() throws Exception {
        var shadow = directory.resolve("shadow/xyz/xuminghai/tetris/ai/BoardSimulator.class");
        Files.createDirectories(shadow.getParent());
        Files.copy(root().resolve("target/classes/xyz/xuminghai/tetris/ai/BoardSimulator.class"), shadow);
        var result = probe(List.of("-Xmx512m"), directory.resolve("shadow"), "--verify-only", root().toString());
        assertNotEquals(0, result.code());
        assertTrue(result.output().contains("shadow class"), result.output());
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void rejectsShadowOfEveryNewSessionClassWithoutStartingSearch() throws Exception {
        for (var suffix : List.of("TerminalCertificateApplication", "TerminalCertificateSession",
                "TerminalCertificateOrder$StepFacts", "TerminalCertificateRecords",
                "TerminalCertificateJournal", "TerminalCertificateJournalReader")) {
            var extra = Files.createDirectory(directory.resolve(suffix));
            var relative = "xyz/xuminghai/tetris/ai/" + suffix + ".class";
            var shadow = extra.resolve(relative);
            Files.createDirectories(shadow.getParent());
            Files.copy(root().resolve("target/test-classes").resolve(relative), shadow);
            var result = probe(List.of("-Xmx512m"), extra, "--verify-only", root().toString());
            assertNotEquals(0, result.code());
            assertTrue(result.output().contains("shadow class: xyz.xuminghai.tetris.ai." + suffix), result.output());
        }
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void rejectsOtherCheckoutAndUnsupportedJvmFlags() throws Exception {
        var other = directory.resolve("other");
        Files.createDirectories(other.resolve("target/classes"));
        Files.createDirectories(other.resolve("target/test-classes"));
        var wrong = probe(List.of("-Xmx512m"), null, "--verify-only", other.toString());
        assertNotEquals(0, wrong.code());
        assertTrue(wrong.output().contains("wrong class origin"), wrong.output());
        var flags = probe(List.of("-Xmx256m"), null, "--verify-only", root().toString());
        assertNotEquals(0, flags.code());
        assertTrue(flags.output().contains("only -Xmx512m"), flags.output());
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void windowsWithoutProcessArgumentsFailsClosed() throws Exception {
        // Windows ProcessHandle.Info does not expose arguments on the supported local JDK.
        // A command without observable arguments must never authenticate an invocation.
        var result = probe(List.of("-Xmx512m"), null, "--verify-only", root().toString());
        assertNotEquals(0, result.code(), result.output());
        assertTrue(result.output().contains("JVM arguments unavailable"), result.output());
    }

    @Test
    void refusesImplicitOrDiagnosticMode() throws Exception {
        for (var args : List.of(new String[0], new String[]{"--collect", root().toString()})) {
            var result = probe(List.of("-Xmx512m"), null, args);
            assertNotEquals(0, result.code());
            assertTrue(result.output().contains("usage: --verify-only"), result.output());
        }
    }

    private static Path root() {
        return Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
    }

    private record Result(int code, String output) { }

    private Result probe(List<String> flags, Path extraClasspath, String... args) throws Exception {
        var classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        var entries = new LinkedHashSet<>(List.of(classpath.split(File.pathSeparator)));
        // Modular Surefire omits module-path dependencies from its classpath property.
        // Use their actual resolved locations when exercising the ordinary JVM boundary.
        for (var module : ModuleLayer.boot().configuration().modules()) {
            var location = module.reference().location();
            if (location.isPresent() && location.orElseThrow().getScheme().equals("file")) {
                entries.add(Path.of(location.orElseThrow()).toRealPath().toString());
            }
        }
        classpath = String.join(File.pathSeparator, entries);
        if (extraClasspath != null) classpath += File.pathSeparator + extraClasspath;
        var command = new ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.addAll(flags);
        command.addAll(List.of("-cp", classpath, TerminalCertificatePreflight.class.getName()));
        command.addAll(List.of(args));
        var log = Files.createTempFile(directory, "probe-", ".log");
        var builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile());
        // The child must test its own contract independently of Maven's environment injections.
        for (var name : List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "CLASSPATH")) {
            builder.environment().remove(name);
        }
        var process = builder.start();
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "preflight child timed out");
            return new Result(process.exitValue(), Files.readString(log));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }
}
