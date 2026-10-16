/*
 * Copyright (C) 2024-2026 xuMingHai
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai.benchmark;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Checks disk provenance before games, then checks the ordinary JVM's actual loaded classes. */
final class GoalWindowBuildProvenance {
    static final List<String> CRITICAL = List.of(
            "ai.GoalWindowCandidate", "ai.ActionPlanCandidates", "ai.ActionStateSearch",
            "ai.BoardOccupancyMetrics", "ai.ActionPlanSimulator", "ai.BoardSimulator",
            "ai.BuildShapeActionPlanningAgent", "ai.ObjectiveRiskController", "ai.ObjectiveSafetyBudget",
            "ai.ShapeTarget", "ai.GameSnapshot", "core.BoardRules", "core.BagPieceGenerator",
            "ai.benchmark.HeadlessGameRunner", "ai.benchmark.ShapeCompletionTelemetry",
            "ai.benchmark.GoalWindowGameEvidence", "ai.benchmark.GoalWindowPairedRunner",
            "ai.benchmark.GoalWindowPairedApplication", "ai.benchmark.GoalWindowBuildProvenance");

    private GoalWindowBuildProvenance() { }

    static Verified verify(Path manifestPath, String expectedHash, String[] applicationArgs) throws Exception {
        require(expectedHash.matches("[0-9a-f]{64}") && sha256(Files.readAllBytes(manifestPath)).equals(expectedHash),
                "manifest digest mismatch");
        var manifest = new ObjectMapper().readTree(Files.readString(manifestPath));
        var root = Path.of(manifest.get("root").asText()).toRealPath();
        // The verifier script must come from the checkout containing this actual test entry class.
        var ownRoot = Path.of(GoalWindowPairedApplication.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI()).toRealPath().getParent().getParent();
        require(root.equals(ownRoot), "entry class is outside the declared checkout");
        var process = new ProcessBuilder("python3", root.resolve("scripts/prepare_goal_window_build.py").toString(),
                "--verify", manifestPath.toAbsolutePath().toString(), "--manifest-sha256", expectedHash)
                .directory(root.toFile()).redirectErrorStream(true).start();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("build verification timed out");
        }
        var verification = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        require(process.exitValue() == 0, "build verification failed: " + verification);
        var expectedClasspath = new ArrayList<String>();
        for (var entry : manifest.get("classpath")) expectedClasspath.add(entry.asText());
        require(System.getProperty("java.class.path").equals(String.join(java.io.File.pathSeparator, expectedClasspath)),
                "actual classpath mismatch");
        require(!GoalWindowPairedApplication.class.getModule().isNamed(), "ordinary classpath required");
        // ProcessHandle is in java.base: keep the production module descriptor frozen.
        var expectedArguments = new ArrayList<>(List.of("-cp", String.join(java.io.File.pathSeparator,
                expectedClasspath), GoalWindowPairedApplication.class.getName()));
        expectedArguments.addAll(List.of(applicationArgs));
        require(List.of(ProcessHandle.current().info().arguments().orElseThrow()).equals(expectedArguments),
                "unexpected JVM/launcher arguments");
        require(Path.of(ProcessHandle.current().info().command().orElseThrow()).toRealPath().toString()
                .equals(manifest.get("jdk").get("executable").asText()), "actual java executable mismatch");
        require(Runtime.version().feature() == 27
                && Path.of(System.getProperty("java.home")).toRealPath().toString()
                .equals(manifest.get("jdk").get("java.home").asText())
                && System.getProperty("java.runtime.version")
                .equals(manifest.get("jdk").get("java.runtime.version").asText()), "actual JDK mismatch");
        return new Verified(expectedHash, manifest, verifyLoaded(root, manifest));
    }

    /** Class loading/source inspection only; never invokes or opens package-private members. */
    static Map<String, Map<String, String>> verifyLoaded(Path root, JsonNode manifest) throws Exception {
        var loaded = new LinkedHashMap<String, Map<String, String>>();
        var loader = GoalWindowPairedApplication.class.getClassLoader();
        for (var suffix : CRITICAL) {
            var name = "xyz.xuminghai.tetris." + suffix;
            var type = loader.loadClass(name); // No initialization or private-member reflection.
            var relative = name.replace('.', '/') + ".class";
            var base = root.resolve(suffix.contains("GoalWindow") ? "target/test-classes" : "target/classes").toRealPath();
            require(type.getClassLoader() == loader, "different application classloader: " + name);
            var origin = Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
            require(origin.equals(base), "wrong loaded class origin: " + name);
            var resources = loader.getResources(relative).asIterator();
            require(resources.hasNext(), "missing loaded class resource: " + name);
            var resource = resources.next();
            require(resource.getProtocol().equals("file") && Path.of(resource.toURI()).toRealPath()
                    .equals(base.resolve(relative).toRealPath()) && !resources.hasNext(),
                    "duplicate/wrong loaded class resource: " + name);
            var hash = sha256(Files.readAllBytes(Path.of(resource.toURI())));
            var recorded = manifest.get("class_files").get(root.relativize(base.resolve(relative)).toString()
                    .replace(java.io.File.separatorChar, '/'));
            require(recorded != null && recorded.asText().equals(hash), "loaded class hash mismatch: " + name);
            loaded.put(name, Map.of("origin", origin.toString(), "sha256", hash));
        }
        return Map.copyOf(loaded);
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        }
        catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    record Verified(String manifestSha256, JsonNode manifest, Map<String, Map<String, String>> loadedClasses) { }
}
