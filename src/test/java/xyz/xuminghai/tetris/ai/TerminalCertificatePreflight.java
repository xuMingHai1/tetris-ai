/*
 * Copyright (C) 2024-2026 xuMingHai
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ordinary-classpath integration probe only: never enumerates a HEART cover or starts a game.
 * Loaded-resource hashes are observations, not clean-build or source authentication.
 * A future external build verifier must bind these observations to an independently retained manifest.
 * Invocation validation requires observable ProcessHandle arguments (the supported execution host is
 * Linux). A platform such as Windows that omits those arguments fails closed.
 */
public final class TerminalCertificatePreflight {
    static final List<String> MAIN = List.of("ai.ActionPlanCandidates", "ai.ActionStateSearch",
            "ai.ActionPlanSimulator", "ai.BoardSimulator", "ai.BoardOccupancyMetrics",
            "ai.GameSnapshot", "ai.ShapeTarget", "ai.AiPlan", "core.BoardRules",
            "core.TetrisFactory", "core.TetrominoType");
    static final List<String> TEST = List.of("ai.TerminalCertificatePreflight", "ai.TerminalCertificateGeometry",
            "ai.TerminalCertificateOrder", "ai.TerminalCertificateStep", "ai.TerminalCertificateChecker",
            "ai.GoalWindowCandidate");

    private TerminalCertificatePreflight() { }

    /** The only accepted command cannot reach the solver; no implicit diagnostic/default mode exists. */
    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !args[0].equals("--verify-only")) {
            throw new IllegalArgumentException("usage: --verify-only <checkout-root>");
        }
        System.out.println(new ObjectMapper().writeValueAsString(inspect(Path.of(args[1]))));
    }

    static Map<String, Object> inspect(Path checkout) throws Exception {
        require(!TerminalCertificatePreflight.class.getModule().isNamed(), "ordinary classpath required");
        require(Runtime.version().feature() == 27, "JDK 27 required");
        var argv = List.of(ProcessHandle.current().info().arguments()
                .orElseThrow(() -> new IllegalStateException("JVM arguments unavailable")));
        require(argv.size() == 6 && argv.get(0).equals("-Xmx512m") && argv.get(1).equals("-cp")
                && argv.get(2).equals(System.getProperty("java.class.path"))
                && argv.get(3).equals(TerminalCertificatePreflight.class.getName())
                && argv.get(4).equals("--verify-only") && Path.of(argv.get(5)).equals(checkout),
                "only -Xmx512m and the explicit classpath verify-only command are supported");
        var flags = List.of("-Xmx512m");
        for (var name : List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "CLASSPATH")) {
            require(System.getenv(name) == null || System.getenv(name).isEmpty(), "injected environment: " + name);
        }
        var root = checkout.toRealPath();
        var loaded = new LinkedHashMap<String, Object>();
        observe(root, MAIN, "target/classes", loaded);
        observe(root, TEST, "target/test-classes", loaded);
        return Map.of("schema", "terminal-certificate-preflight-v1", "loaded_classes", loaded,
                "java_runtime", System.getProperty("java.runtime.version"), "jvm_args", flags,
                "diagnostic_started", false, "provenance_authenticated", false,
                "eligible_for_runtime_validation", false);
    }

    private static void observe(Path root, List<String> names, String directory,
                                Map<String, Object> loaded) throws Exception {
        var base = root.resolve(directory).toRealPath();
        require(base.equals(root.resolve(directory)), "linked class directory");
        var loader = TerminalCertificatePreflight.class.getClassLoader();
        for (var suffix : names) {
            var name = "xyz.xuminghai.tetris." + suffix;
            var type = loader.loadClass(name); // No initialization or private-member reflection.
            var origin = Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
            require(type.getClassLoader() == loader && origin.equals(base), "wrong class origin: " + name);
            var relative = name.replace('.', '/') + ".class";
            var resources = loader.getResources(relative).asIterator();
            require(resources.hasNext(), "missing class resource: " + name);
            var resource = resources.next();
            require(resource.getProtocol().equals("file") && !resources.hasNext(), "shadow class: " + name);
            var file = Path.of(resource.toURI());
            require(file.toRealPath().equals(base.resolve(relative)) && !Files.isSymbolicLink(file),
                    "wrong/linked class resource: " + name);
            var hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
            loaded.put(name, Map.of("origin", origin.toString(), "sha256", hash));
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
