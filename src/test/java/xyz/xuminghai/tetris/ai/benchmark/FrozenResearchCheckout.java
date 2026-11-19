/*
 * Copyright (C) 2024-2026 xuMingHai
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai.benchmark;

import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Builds test data for frozen preflights, not a certificate for the current product.
 * Only the registered product paths may be restored; all measurement inputs retain
 * their current Git blobs and modes. The fixture has its own real commit identity.
 */
final class FrozenResearchCheckout {
    private static final String BENCHMARK = "src/main/java/xyz/xuminghai/tetris/ai/benchmark/";
    private static final String PRODUCT_TEST = "src/test/java/xyz/xuminghai/tetris/ai/ActionPlanSimulatorTest.java";
    private static final Set<String> RESTORABLE = Set.of(
            "src/main/java/xyz/xuminghai/tetris/UiInteractionApplication.java",
            "src/main/java/xyz/xuminghai/tetris/ai/ActionPlanSimulator.java",
            "src/main/java/xyz/xuminghai/tetris/game/GameWorld.java",
            "src/main/java/xyz/xuminghai/tetris/view/DashboardView.java",
            "src/main/java/xyz/xuminghai/tetris/view/GhostLandingView.java",
            "src/main/resources/css/dashboard.css");
    private final Path logs;
    private int sequence;

    FrozenResearchCheckout(Path logs) {
        this.logs = logs;
    }

    record Fixture(Path checkout, String originSha, String baseSha, String sha, String tree) {}

    Fixture create(Path source, Path checkout) throws Exception {
        var origin = git(source, "rev-parse", "HEAD").trim();
        git(source, "clone", "--quiet", "--no-hardlinks", "--no-checkout", source.toString(), checkout.toString());
        git(checkout, "checkout", "--quiet", "--detach", origin);
        var mapper = new ObjectMapper();
        var base = mapper.readTree(Files.readString(checkout.resolve("docs/research/heart-acceptance-v1.json")))
                .get("production_base_sha").asText();
        if (command(checkout, List.of("git", "cat-file", "-e", base + "^{commit}"), false).exit() != 0) {
            git(checkout, "fetch", "--no-tags", "--depth=1", "https://github.com/xuMingHai1/tetris-ai.git", base);
        }
        var original = inventory(checkout, origin);
        var frozen = inventory(checkout, base);
        var drift = new ArrayList<String>();
        var paths = new java.util.TreeSet<>(original.keySet());
        paths.addAll(frozen.keySet());
        for (var path : paths) {
            if (protectedInput(path) && !java.util.Objects.equals(original.get(path), frozen.get(path))) {
                drift.add(path);
            }
        }
        // Always exercise the unchanged guard against the real product first.
        assertGuard(checkout, drift);
        assertTrue(RESTORABLE.containsAll(drift), "Unregistered protected drift: " + drift);
        var replaced = new ArrayList<>(drift);
        if (!drift.isEmpty() && !java.util.Objects.equals(original.get(PRODUCT_TEST), frozen.get(PRODUCT_TEST))) {
            // This sole companion test calls terminalCells(), absent in the frozen production API.
            replaced.add(PRODUCT_TEST);
        }
        for (var path : replaced) {
            git(checkout, "restore", "--source=" + base, "--staged", "--worktree", "--", path);
        }
        git(checkout, "-c", "user.name=Frozen research fixture", "-c", "user.email=fixture@example.invalid",
                "-c", "commit.gpgsign=false", "commit", "--quiet", "--allow-empty", "-m",
                "Test-only frozen production fixture; origin " + origin);
        var sha = git(checkout, "rev-parse", "HEAD").trim();
        var tree = git(checkout, "rev-parse", "HEAD^{tree}").trim();
        var actual = inventory(checkout, sha);
        paths.addAll(actual.keySet());
        var replacements = new LinkedHashMap<String, Object>();
        for (var path : paths) {
            var expected = replaced.contains(path) ? frozen.get(path) : original.get(path);
            assertEquals(expected, actual.get(path), "Unexpected fixture blob/mode/path change: " + path);
            if (protectedInput(path)) {
                assertEquals(frozen.get(path), actual.get(path), "Frozen input mismatch: " + path);
            }
            if (replaced.contains(path)) {
                replacements.put(path, Map.of("origin", original.getOrDefault(path, "ABSENT"),
                        "fixture", actual.getOrDefault(path, "ABSENT")));
            }
        }
        assertEquals("", git(checkout, "status", "--porcelain").trim());
        assertGuard(checkout, List.of());
        Files.writeString(logs.resolve("fixture-origin.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(
                Map.of("origin_product_sha", origin, "production_base_sha", base, "fixture_sha", sha,
                        "fixture_tree", tree, "replacements", replacements, "product_certified", false)));
        return new Fixture(checkout, origin, base, sha, tree);
    }

    /** Exercise source, resource and build drift against the original guard on the fixture. */
    void rejectsDrift(Fixture fixture) throws Exception {
        var checkout = fixture.checkout();
        for (var path : List.of("src/main/java/xyz/xuminghai/tetris/ai/ActionPlanSimulator.java",
                "src/main/resources/frozen-fixture-negative.txt", "pom.xml")) {
            var file = checkout.resolve(path);
            var existed = Files.exists(file);
            var bytes = existed ? Files.readAllBytes(file) : null;
            try {
                Files.writeString(file, "\nfixture guard negative\n", java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.APPEND);
                git(checkout, "add", "--", path);
                assertGuard(checkout, List.of(path));
            } finally {
                git(checkout, "restore", "--source=HEAD", "--staged", "--", path);
                if (existed) Files.write(file, bytes);
                else Files.delete(file);
            }
        }
        assertEquals("", git(checkout, "status", "--porcelain").trim());
    }

    private static boolean protectedInput(String path) {
        return (path.startsWith("src/main/") && !path.startsWith(BENCHMARK))
                || path.equals("pom.xml") || path.startsWith(".mvn/")
                || path.equals("mvnw") || path.equals("mvnw.cmd");
    }

    private Map<String, String> inventory(Path checkout, String revision) throws Exception {
        var result = new LinkedHashMap<String, String>();
        for (var entry : git(checkout, "ls-tree", "-r", "-z", revision).split("\u0000")) {
            if (!entry.isEmpty()) {
                var tab = entry.indexOf('\t');
                result.put(entry.substring(tab + 1), entry.substring(0, tab));
            }
        }
        return result;
    }

    private void assertGuard(Path checkout, List<String> drift) throws Exception {
        var result = command(checkout, List.of("python3", "scripts/verify_heart_production_base.py"), false);
        if (drift.isEmpty()) {
            assertEquals(0, result.exit(), result.output());
        } else {
            assertNotEquals(0, result.exit(), result.output());
            assertTrue(result.output().contains("frozen HEART production differs:"), result.output());
            for (var path : drift) assertTrue(result.output().contains(path), result.output());
        }
    }

    String git(Path checkout, String... args) throws Exception {
        var command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        return command(checkout, command, true).output();
    }

    private record Result(int exit, String output) {}

    private Result command(Path checkout, List<String> command, boolean success) throws Exception {
        var log = logs.resolve("fixture-command-" + sequence++ + ".log");
        var process = new ProcessBuilder(command).directory(checkout.toFile()).redirectErrorStream(true)
                .redirectOutput(log.toFile()).start();
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Fixture command did not stop");
            fail("Fixture command timed out: " + command);
        }
        var output = Files.readString(log);
        if (success) assertEquals(0, process.exitValue(), () -> command + "\n" + output);
        return new Result(process.exitValue(), output);
    }
}
