/*
 * Copyright (C) 2024-2026 xuMingHai
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import xyz.xuminghai.tetris.core.BoardPosition;
import xyz.xuminghai.tetris.core.TetrominoType;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Versioned replay claims only, not a source/provenance or diagnostic-success envelope.
 * Reading never trusts stored outcome flags; the caller must independently replay the claims.
 */
final class TerminalCertificateRecords {
    static final String SCHEMA = "terminal-certificate-steps-v1";
    static final int MAX_BYTES = 1_048_576;
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    private TerminalCertificateRecords() { }

    /** Exclusive creation preserves an earlier attempt, including an incomplete file. */
    static void write(Path path, List<TerminalCertificateStep> steps) throws IOException {
        var text = JSON.writeValueAsString(value(steps)) + "\n";
        Files.writeString(path, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }

    /** Same validated wire claims embedded in a session attempt, without a second output file. */
    static Map<String, Object> value(List<TerminalCertificateStep> steps) {
        steps = List.copyOf(steps);
        require(!steps.isEmpty() && steps.size() <= 16, "step count");
        var records = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < steps.size(); i++) {
            var step = steps.get(i);
            records.add(Map.of("index", i, "type", step.type().name(),
                    "spawn_cells", cells(step.spawnCells()), "spawn_rotation", step.spawnRotation(),
                    "cells", cells(step.cells()), "actions", step.plan().actions().stream().map(Enum::name).toList(),
                    "post_board", step.postBoard().toString(16), "cleared_lines", step.clearedLines()));
        }
        var value = Map.<String, Object>of("schema", SCHEMA, "step_count", steps.size(), "steps", records);
        var text = JSON.writeValueAsString(value) + "\n";
        // Apply the same wire validation before creating any output.
        parse(text);
        require(text.getBytes(StandardCharsets.UTF_8).length <= MAX_BYTES, "record too large");
        return value;
    }

    static List<TerminalCertificateStep> read(Path path) throws IOException {
        byte[] bytes;
        try (var input = Files.newInputStream(path)) {
            bytes = input.readNBytes(MAX_BYTES + 1);
        }
        require(bytes.length <= MAX_BYTES, "record too large");
        return parse(new String(bytes, StandardCharsets.UTF_8));
    }

    private static List<List<Integer>> cells(List<BoardPosition> cells) {
        return cells.stream().map(c -> List.of(c.row(), c.col())).toList();
    }

    static List<TerminalCertificateStep> parse(String text) {
        var root = JSON.readTree(text);
        fields(root, "schema", "step_count", "steps");
        require(SCHEMA.equals(string(root.get("schema"))), "wrong schema");
        int count = integer(root.get("step_count"));
        var records = root.get("steps");
        require(count >= 1 && count <= 16 && records.isArray() && records.size() == count, "step count");
        var result = new ArrayList<TerminalCertificateStep>();
        for (int i = 0; i < count; i++) {
            var record = records.get(i);
            fields(record, "index", "type", "spawn_cells", "spawn_rotation", "cells", "actions",
                    "post_board", "cleared_lines");
            require(integer(record.get("index")) == i, "step index");
            var actions = record.get("actions");
            require(actions.isArray() && !actions.isEmpty(), "actions required");
            var plan = new ArrayList<AiAction>();
            for (var action : actions) plan.add(AiAction.valueOf(string(action)));
            var bits = string(record.get("post_board"));
            require(bits.matches("0|[1-9a-f][0-9a-f]{0,49}"), "noncanonical 200-bit board");
            int rotation = integer(record.get("spawn_rotation"));
            require(rotation >= 0 && rotation < 4, "spawn rotation");
            require(integer(record.get("cleared_lines")) == 0, "row clear outside certificate family");
            result.add(new TerminalCertificateStep(TetrominoType.valueOf(string(record.get("type"))),
                    positions(record.get("spawn_cells"), true), rotation,
                    positions(record.get("cells"), false), new AiPlan(plan), new BigInteger(bits, 16), 0));
        }
        return List.copyOf(result);
    }

    private static List<BoardPosition> positions(JsonNode node, boolean spawn) {
        require(node.isArray() && node.size() == 4, "four cells required");
        var result = new ArrayList<BoardPosition>();
        for (var cell : node) {
            require(cell.isArray() && cell.size() == 2, "row/col pair required");
            int row = integer(cell.get(0));
            int col = integer(cell.get(1));
            require(row >= (spawn ? -4 : 0) && row < 20 && col >= 0 && col < 10, "cell bounds");
            result.add(new BoardPosition(row, col));
        }
        require(new HashSet<>(result).size() == 4, "duplicate cell");
        return List.copyOf(result);
    }

    private static int integer(JsonNode node) {
        require(node != null && node.isIntegralNumber() && node.canConvertToInt(), "integer required");
        return node.intValue();
    }

    private static String string(JsonNode node) {
        require(node != null && node.isString(), "string required");
        return node.asString();
    }

    private static void fields(JsonNode node, String... expected) {
        require(node != null && node.isObject(), "object required");
        var actual = new HashSet<String>();
        node.propertyNames().forEach(actual::add);
        require(actual.equals(Set.of(expected)), "missing or unknown fields");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
