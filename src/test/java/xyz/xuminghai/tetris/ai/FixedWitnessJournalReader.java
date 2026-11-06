/*
 * Copyright (C) 2024-2026 xuMingHai
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Bounded, strict journal consumption. Only checks recorded facts; never replays actions. */
final class FixedWitnessJournalReader {
    private static final int MAX_BYTES = 10 * 1024 * 1024;
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES).build();

    private FixedWitnessJournalReader() { }

    enum State { INCOMPLETE, COMPLETE, INVALID, EVIDENCE_MISMATCH }
    record Summary(State state, int attempted, List<FixedWitnessReplay.Row> rows) {
        Summary { rows = List.copyOf(rows); }
    }

    /**
     * The caller supplies the authoritative input and expectations, not values recovered from an
     * untrusted START event. An unterminated final line remains INCOMPLETE, even if it spells END.
     */
    static Summary read(Path path, byte[] input, String hash, List<FixedWitnessInput.PathSpec> paths,
                        Map<String, FixedWitnessReportChecker.Expected> expected) throws IOException {
        var steps = FixedWitnessInput.read(input, hash, paths);
        byte[] bytes;
        try (var stream = Files.newInputStream(path)) {
            bytes = stream.readNBytes(MAX_BYTES + 1);
        }
        require(bytes.length <= MAX_BYTES, "journal size limit");
        // Only LF-terminated records are evidence. A torn tail can end within a UTF-8
        // character, so exclude its bytes before strict decoding of the committed prefix.
        int terminatedBytes = bytes.length;
        while (terminatedBytes > 0 && bytes[terminatedBytes - 1] != '\n') terminatedBytes--;
        String text = StandardCharsets.UTF_8.newDecoder()
                .decode(ByteBuffer.wrap(bytes, 0, terminatedBytes)).toString();
        String[] lines = text.split("\n", -1);
        require(lines.length <= 48, "journal event count limit");
        var rows = new ArrayList<FixedWitnessReplay.Row>();
        boolean started = false;
        boolean pending = false;
        boolean mismatch = false;
        int attempts = 0;
        State state = State.INCOMPLETE;
        for (int index = 0; index < lines.length - 1; index++) {
            require(state == State.INCOMPLETE && lines[index].length() <= 64 * 1024,
                    "events after END or line size limit");
            JsonNode event = JSON.readTree(lines[index]);
            String kind = string(event.get("event"));
            switch (kind) {
                case "START" -> {
                    fields(event, "event", "schema", "inputHash", "paths", "expected");
                    require(!started && index == 0 && integer(event.get("schema")) == 1
                            && string(event.get("inputHash")).equals(hash)
                            && event.get("paths").equals(tree(paths.stream().map(pathSpec ->
                                    Map.of("id", pathSpec.id(), "placements", pathSpec.placements())).toList()))
                            && event.get("expected").equals(tree(FixedWitnessReportCodec.expected(expected))),
                            "START identity");
                    started = true;
                }
                case "ATTEMPT" -> {
                    fields(event, "event", "attempt", "pathId", "step");
                    require(started && !pending && !mismatch && rows.size() < steps.size(), "attempt order");
                    var step = steps.get(rows.size());
                    require(integer(event.get("attempt")) == attempts + 1
                            && string(event.get("pathId")).equals(step.pathId())
                            && integer(event.get("step")) == step.step(), "attempt identity");
                    attempts++;
                    pending = true;
                }
                case "STEP" -> {
                    fields(event, "event", "row");
                    require(started && pending && !mismatch, "step without attempt");
                    var row = FixedWitnessReportCodec.row(event.get("row"));
                    rows.add(row);
                    pending = false;
                    try {
                        FixedWitnessReportChecker.checkPrefix(rows, steps, hash);
                        if (rows.size() == steps.size()
                                || !steps.get(rows.size()).pathId().equals(row.pathId())) {
                            FixedWitnessReportChecker.checkTerminal(row, expected.get(row.pathId()));
                        }
                    } catch (FixedWitnessReportChecker.Mismatch failure) {
                        mismatch = true;
                    }
                }
                case "END" -> {
                    fields(event, "event", "status", "attempted", "completed", "reason");
                    require(started && integer(event.get("attempted")) == attempts
                            && integer(event.get("completed")) == rows.size(), "END counts");
                    state = State.valueOf(string(event.get("status")));
                    String reason = string(event.get("reason"));
                    switch (state) {
                        case COMPLETE -> require(!pending && !mismatch && rows.size() == steps.size()
                                && reason.isEmpty(), "false COMPLETE");
                        case EVIDENCE_MISMATCH -> require(mismatch && !reason.isEmpty(), "false mismatch");
                        case INVALID -> require(!mismatch && !reason.isEmpty()
                                && rows.size() < steps.size(), "false INVALID");
                        case INCOMPLETE -> throw new IllegalArgumentException("END cannot claim INCOMPLETE");
                    }
                }
                default -> throw new IllegalArgumentException("unknown journal event");
            }
        }
        require(state == State.INCOMPLETE || terminatedBytes == bytes.length, "bytes after END");
        return new Summary(state, attempts, rows);
    }

    private static JsonNode tree(Object value) {
        return JSON.readTree(JSON.writeValueAsString(value));
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
        require(node.isObject() && node.properties().stream().map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toSet()).equals(Set.of(names)), "event fields");
    }

    private static void require(boolean condition, String reason) {
        if (!condition) throw new IllegalArgumentException(reason);
    }
}
