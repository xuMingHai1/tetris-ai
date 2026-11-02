/*
 * Copyright (C) 2024-2026 xuMingHai
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static java.util.Map.entry;

/**
 * Explicit JSON wire vocabulary for package-local facts. Jackson handles JSON, while named fields
 * avoid reflection into non-exported test packages and reject missing primitive fields.
 */
final class FixedWitnessReportCodec {
    private FixedWitnessReportCodec() { }

    static Map<String, Object> expected(Map<String, FixedWitnessReportChecker.Expected> values) {
        Map<String, Object> result = new LinkedHashMap<>();
        values.forEach((id, value) -> result.put(id, Map.of(
                "required", value.required(), "forbidden", value.forbidden(), "rawHoles", value.rawHoles(),
                "requiredHoles", value.requiredHoles(), "forbiddenHoles", value.forbiddenHoles(),
                "supportHoles", value.supportHoles(), "outsideHoles", value.outsideHoles())));
        return result;
    }

    static Map<String, Object> row(FixedWitnessReplay.Row row) {
        var progress = row.progress();
        var holes = row.holes();
        return Map.ofEntries(entry("pathId", row.pathId()), entry("step", row.step()),
                entry("sourceLine", row.sourceLine()), entry("piece", row.piece()),
                entry("actions", row.actions()), entry("inputHash", row.inputHash()),
                entry("before", occupancy(row.before())), entry("after", occupancy(row.after())),
                entry("clearedLines", row.clearedLines()), entry("cumulativeClears", row.cumulativeClears()),
                entry("progress", Map.of("requiredCells", progress.requiredCells(),
                        "matchedRequiredCells", progress.matchedRequiredCells(),
                        "forbiddenCells", progress.forbiddenCells(),
                        "forbiddenOccupiedCells", progress.forbiddenOccupiedCells(),
                        "supportOccupiedCells", progress.supportOccupiedCells())),
                entry("maxHeight", row.maxHeight()), entry("headroom", row.headroom()),
                entry("holes", Map.of("totalHoles", holes.totalHoles(), "requiredHoles", holes.requiredHoles(),
                        "forbiddenHoles", holes.forbiddenHoles(), "supportAllowedHoles", holes.supportAllowedHoles(),
                        "outsideTargetHoles", holes.outsideTargetHoles())));
    }

    static FixedWitnessReplay.Row row(JsonNode node) {
        fields(node, "pathId", "step", "sourceLine", "piece", "actions", "inputHash", "before", "after",
                "clearedLines", "cumulativeClears", "progress", "maxHeight", "headroom", "holes");
        var actionNodes = node.get("actions");
        require(actionNodes.isArray() && actionNodes.size() >= 1 && actionNodes.size() <= 64, "actions");
        var actions = new ArrayList<String>();
        for (int index = 0; index < actionNodes.size(); index++) actions.add(string(actionNodes.get(index)));
        var progress = node.get("progress");
        fields(progress, "requiredCells", "matchedRequiredCells", "forbiddenCells", "forbiddenOccupiedCells",
                "supportOccupiedCells");
        var holes = node.get("holes");
        fields(holes, "totalHoles", "requiredHoles", "forbiddenHoles", "supportAllowedHoles", "outsideTargetHoles");
        return new FixedWitnessReplay.Row(string(node.get("pathId")), integer(node.get("step")),
                integer(node.get("sourceLine")), string(node.get("piece")), actions, string(node.get("inputHash")),
                occupancy(node.get("before")), occupancy(node.get("after")), integer(node.get("clearedLines")),
                integer(node.get("cumulativeClears")),
                new ShapeProgress(integer(progress.get("requiredCells")), integer(progress.get("matchedRequiredCells")),
                        integer(progress.get("forbiddenCells")), integer(progress.get("forbiddenOccupiedCells")),
                        integer(progress.get("supportOccupiedCells"))),
                integer(node.get("maxHeight")), integer(node.get("headroom")),
                new ShapeWitnessConstraintAudit.HoleBreakdown(integer(holes.get("totalHoles")),
                        integer(holes.get("requiredHoles")), integer(holes.get("forbiddenHoles")),
                        integer(holes.get("supportAllowedHoles")), integer(holes.get("outsideTargetHoles"))));
    }

    private static Map<String, Object> occupancy(FixedWitnessReplay.Occupancy value) {
        return Map.of("bits", value.bits(), "sha256", value.sha256(), "occupied", value.occupied());
    }

    private static FixedWitnessReplay.Occupancy occupancy(JsonNode node) {
        fields(node, "bits", "sha256", "occupied");
        return new FixedWitnessReplay.Occupancy(string(node.get("bits")), string(node.get("sha256")),
                integer(node.get("occupied")));
    }

    private static String string(JsonNode node) {
        require(node != null && node.isString(), "expected string");
        return node.asString();
    }

    private static int integer(JsonNode node) {
        require(node != null && node.isIntegralNumber() && node.canConvertToInt(), "expected integer");
        return node.asInt();
    }

    private static void fields(JsonNode node, String... names) {
        require(node != null && node.isObject() && node.properties().stream().map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toSet()).equals(Set.of(names)), "row fields");
    }

    private static void require(boolean condition, String reason) {
        if (!condition) throw new IllegalArgumentException(reason);
    }
}
