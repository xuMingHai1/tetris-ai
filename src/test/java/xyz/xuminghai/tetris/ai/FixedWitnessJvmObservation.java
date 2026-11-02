/*
 * Copyright (C) 2024-2026 xuMingHai
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Observes relevant classes without initialization, replay, search or Toolkit startup.
 * Resource bytes and code sources are observations, not independently authenticated provenance.
 * The supervisor must compare them with its retained clean-build receipt before acknowledgement.
 */
final class FixedWitnessJvmObservation {
    private static final String PREFIX = "xyz.xuminghai.tetris.";
    private static final List<String> MAIN = List.of(
            "ai.ActionPlanSimulator", "ai.ActionStateSearch", "ai.AiAction", "ai.AiPlan",
            "ai.AiPlanValidator", "ai.BoardOccupancyMetrics", "ai.BoardSimulator",
            "ai.GameSnapshot", "ai.PlacementCandidate", "ai.ShapeProgress", "ai.ShapeTarget",
            "ai.ShapeWitnessConstraintAudit", "core.Abstract2StateBlock", "core.Abstract4StateBlock",
            "core.AbstractBlock", "core.BoardPosition", "core.BoardRules", "core.Cell",
            "core.IBlock", "core.JBlock", "core.LBlock", "core.OBlock", "core.SBlock",
            "core.TBlock", "core.Tetris", "core.TetrisFactory", "core.TetrominoType", "core.ZBlock");
    private static final List<String> TEST = List.of(
            "ai.FixedWitnessInput", "ai.FixedWitnessReplay", "ai.FixedWitnessReportChecker",
            "ai.FixedWitnessJournalReader", "ai.FixedWitnessReportCodec",
            "ai.FixedWitnessJvmObservation", "ai.FixedWitnessReplayApplication");

    private FixedWitnessJvmObservation() { }

    static Map<String, Object> inspect(Path root) throws Exception {
        require(!FixedWitnessJvmObservation.class.getModule().isNamed(), "ordinary classpath required");
        var loader = FixedWitnessJvmObservation.class.getClassLoader();
        var classes = new LinkedHashMap<String, Object>();
        for (var name : MAIN) observe(Class.forName(PREFIX + name, false, loader),
                root.resolve("target/classes"), loader, classes);
        for (var name : TEST) observe(Class.forName(PREFIX + name, false, loader),
                root.resolve("target/test-classes"), loader, classes);
        return Map.of("classes", classes, "java_runtime", System.getProperty("java.runtime.version"),
                "classpath", System.getProperty("java.class.path"), "pid", ProcessHandle.current().pid());
    }

    /** Includes nested records/enums actually used by the journal and production rule chain. */
    static void observe(Class<?> type, Path base, ClassLoader loader, Map<String, Object> result)
            throws Exception {
        if (result.containsKey(type.getName())) return;
        require(base.isAbsolute() && base.equals(base.toRealPath()), "canonical class directory required");
        var source = type.getProtectionDomain().getCodeSource();
        require(source != null && type.getClassLoader() == loader, "missing/wrong class loader");
        var origin = Path.of(source.getLocation().toURI());
        require(origin.equals(base) && origin.equals(origin.toRealPath()), "wrong class origin");
        var name = type.getName().replace('.', '/') + ".class";
        var resources = loader.getResources(name);
        require(resources.hasMoreElements(), "missing class resource");
        var resource = resources.nextElement();
        require(!resources.hasMoreElements() && resource.getProtocol().equals("file"), "shadow class resource");
        var file = Path.of(resource.toURI());
        require(file.equals(base.resolve(name)) && file.equals(file.toRealPath())
                && Files.isRegularFile(file), "linked/wrong class resource");
        // The independent preparation manifest binds all bytes, including dependencies. These
        // per-class observations cannot prove in-memory bytecode without the no-agent launch gate.
        require(Files.size(file) <= 1024 * 1024, "oversized class resource");
        byte[] bytes;
        try (var input = Files.newInputStream(file)) {
            bytes = input.readNBytes(1024 * 1024 + 1);
        }
        require(bytes.length <= 1024 * 1024, "class resource grew beyond limit");
        result.put(type.getName(), Map.of("origin", origin.toString(),
                "resource", file.toString(), "sha256", FixedWitnessInput.sha256(bytes)));
        for (var nested : type.getDeclaredClasses()) observe(nested, base, loader, result);
    }

    static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
