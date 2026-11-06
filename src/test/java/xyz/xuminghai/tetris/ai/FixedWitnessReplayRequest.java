/*
 * Copyright (C) 2024-2026 xuMingHai
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Owns a caller-declared fixed-action input before any journal exists.
 * Parsing validates vocabulary and declarations only; it never executes actions, reads files,
 * grants session authority or infers expectations from a child-produced journal.
 */
final class FixedWitnessReplayRequest {
    private static final String SCHEMA = "fixed-witness-replay-request-v1";
    private static final int DECLARATION_LIMIT = 16 * 1024;
    private static final Set<String> TOP = Set.of("schema", "inputSha256", "paths", "expected");
    private static final Set<String> EXPECTED = Set.of("required", "forbidden", "rawHoles",
            "requiredHoles", "forbiddenHoles", "supportHoles", "outsideHoles");
    private static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder()
                    .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(8).build())
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    private final byte[] input;
    private final String inputHash;
    private final List<FixedWitnessInput.PathSpec> paths;
    private final Map<String, FixedWitnessReportChecker.Expected> expected;
    private final byte[] canonical;
    private final String requestHash;

    FixedWitnessReplayRequest(byte[] input, String inputHash, List<FixedWitnessInput.PathSpec> paths,
                              Map<String, FixedWitnessReportChecker.Expected> expected) {
        this.input = snapshot(input, FixedWitnessInput.MAX_BYTES);
        this.inputHash = inputHash;
        this.paths = List.copyOf(paths);
        this.expected = Map.copyOf(expected);
        FixedWitnessInput.read(this.input, inputHash, this.paths);
        var ids = new HashSet<String>();
        this.paths.forEach(path -> ids.add(path.id()));
        require(this.expected.keySet().equals(ids), "expected keys must equal path IDs");
        var declaration = new LinkedHashMap<String, Object>();
        declaration.put("schema", SCHEMA);
        declaration.put("inputSha256", inputHash);
        declaration.put("paths", this.paths.stream().map(path -> {
            var fields = new LinkedHashMap<String, Object>();
            fields.put("id", path.id());
            fields.put("placements", path.placements());
            return fields;
        }).toList());
        var expectations = new LinkedHashMap<String, Object>();
        for (var path : this.paths) {
            var value = this.expected.get(path.id());
            var fields = new LinkedHashMap<String, Object>();
            fields.put("required", value.required());
            fields.put("forbidden", value.forbidden());
            fields.put("rawHoles", value.rawHoles());
            fields.put("requiredHoles", value.requiredHoles());
            fields.put("forbiddenHoles", value.forbiddenHoles());
            fields.put("supportHoles", value.supportHoles());
            fields.put("outsideHoles", value.outsideHoles());
            expectations.put(path.id(), fields);
        }
        declaration.put("expected", expectations);
        canonical = (JSON.writeValueAsString(declaration) + "\n").getBytes(StandardCharsets.UTF_8);
        require(canonical.length <= DECLARATION_LIMIT, "declaration size limit");
        requestHash = FixedWitnessInput.sha256(canonical);
    }

    /** The parent-supplied digest must be independent of the consumed declaration. */
    static FixedWitnessReplayRequest read(byte[] declaration, byte[] input, String expectedRequestSha256) {
        require(expectedRequestSha256 != null && expectedRequestSha256.matches("[0-9a-f]{64}"),
                "independent request digest required");
        byte[] raw = snapshot(declaration, DECLARATION_LIMIT);
        byte[] ownedInput = snapshot(input, FixedWitnessInput.MAX_BYTES);
        require(FixedWitnessInput.sha256(raw).equals(expectedRequestSha256), "request digest mismatch");
        JsonNode root;
        try {
            String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(raw)).toString();
            require(!text.startsWith("\uFEFF"), "request BOM");
            root = JSON.readTree(text);
        } catch (CharacterCodingException | tools.jackson.core.JacksonException failure) {
            throw new IllegalArgumentException("invalid request JSON/UTF-8", failure);
        }
        keys(root, TOP);
        require(SCHEMA.equals(string(root, "schema")), "request schema");
        JsonNode nodes = root.get("paths");
        require(nodes.isArray() && nodes.size() >= 1 && nodes.size() <= 2, "path count/type");
        var paths = new ArrayList<FixedWitnessInput.PathSpec>();
        var ids = new HashSet<String>();
        for (JsonNode path : nodes) {
            keys(path, Set.of("id", "placements"));
            var spec = new FixedWitnessInput.PathSpec(string(path, "id"), integer(path, "placements"));
            require(ids.add(spec.id()), "duplicate path ID");
            paths.add(spec);
        }
        var expected = new LinkedHashMap<String, FixedWitnessReportChecker.Expected>();
        JsonNode expectations = root.get("expected");
        keys(expectations, ids);
        for (var path : paths) {
            JsonNode value = expectations.get(path.id());
            keys(value, EXPECTED);
            expected.put(path.id(), new FixedWitnessReportChecker.Expected(integer(value, "required"),
                    integer(value, "forbidden"), integer(value, "rawHoles"), integer(value, "requiredHoles"),
                    integer(value, "forbiddenHoles"), integer(value, "supportHoles"), integer(value, "outsideHoles")));
        }
        var request = new FixedWitnessReplayRequest(ownedInput, string(root, "inputSha256"), paths, expected);
        // The constructor is the only serializer: equivalent JSON spellings cannot change identity.
        require(Arrays.equals(raw, request.canonical), "noncanonical request");
        return request;
    }

    byte[] inputBytes() { return input.clone(); }
    byte[] canonicalBytes() { return canonical.clone(); }
    String inputHash() { return inputHash; }
    String requestHash() { return requestHash; }
    List<FixedWitnessInput.PathSpec> paths() { return paths; }
    Map<String, FixedWitnessReportChecker.Expected> expected() { return expected; }

    private static byte[] snapshot(byte[] bytes, int limit) {
        require(bytes != null && bytes.length <= limit, "request size/null");
        return bytes.clone();
    }

    private static void keys(JsonNode node, Set<String> expected) {
        require(node != null && node.isObject(), "object required");
        var actual = new HashSet<String>();
        node.propertyNames().forEach(actual::add);
        require(actual.equals(expected), "exact request keys required");
    }

    private static String string(JsonNode node, String key) {
        JsonNode value = node.get(key);
        require(value != null && value.isString(), "string required: " + key);
        return value.asString();
    }

    private static int integer(JsonNode node, String key) {
        JsonNode value = node.get(key);
        require(value != null && value.isIntegralNumber() && value.canConvertToInt(), "int required: " + key);
        int number = value.intValue();
        require(number >= 0, "negative integer: " + key);
        return number;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }

}
