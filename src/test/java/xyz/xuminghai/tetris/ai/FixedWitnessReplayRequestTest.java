/*
 * Copyright (C) 2024-2026 xuMingHai
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** First three of PR183's sixteen artificial cases; no action or filesystem input. */
class FixedWitnessReplayRequestTest {
    private static final byte[] INPUT = bytes(FixedWitnessInput.HEADER + "\n"
            + "shape_feasibility_witness,71,1,O,HARD_DROP\n");
    private static final String VECTOR = "{\"schema\":\"fixed-witness-replay-request-v1\",\"inputSha256\":\""
            + "a".repeat(64) + "\",\"paths\":[{\"id\":\"71\",\"placements\":1}],\"expected\":{\"71\":"
            + "{\"required\":0,\"forbidden\":0,\"rawHoles\":0,\"requiredHoles\":0,\"forbiddenHoles\":0,"
            + "\"supportHoles\":0,\"outsideHoles\":0}}}\n";

    @Test
    void canonicalDeclarationMatchesIndependentLiteralVector() {
        // Published vector tests declaration bytes only; its artificial hash is not valid input.
        assertEquals("943d2579419f808834f2e05b6b10542c09083cb26487b9e49e64ad0969bec971",
                FixedWitnessInput.sha256(bytes(VECTOR)));
        var request = new FixedWitnessReplayRequest(INPUT, FixedWitnessInput.sha256(INPUT),
                List.of(new FixedWitnessInput.PathSpec("71", 1)),
                Map.of("71", new FixedWitnessReportChecker.Expected(0, 0, 0, 0, 0, 0, 0)));
        byte[] literal = bytes(VECTOR.replace("a".repeat(64), FixedWitnessInput.sha256(INPUT)));
        assertArrayEquals(literal, request.canonicalBytes());
        assertEquals(FixedWitnessInput.sha256(literal), request.requestHash());
        var parsed = FixedWitnessReplayRequest.read(literal, INPUT, request.requestHash());
        assertArrayEquals(INPUT, parsed.inputBytes());
        assertArrayEquals(literal, parsed.canonicalBytes());
        assertEquals(request.inputHash(), parsed.inputHash());
        assertEquals(request.paths(), parsed.paths());
        assertEquals(request.expected(), parsed.expected());
    }

    @Test
    void rejectsDuplicateSchemaBeforeConstructingRequest() {
        String declaration = validLiteral().replace("\"schema\":", "\"schema\":\"duplicate\",\"schema\":");
        var failure = assertThrows(IllegalArgumentException.class, () -> read(declaration));
        assertEquals("invalid request JSON/UTF-8", failure.getMessage());
    }

    @Test
    void rejectsPlacementsOutsideIntegerRange() {
        String declaration = validLiteral().replace("\"placements\":1", "\"placements\":2147483648");
        var failure = assertThrows(IllegalArgumentException.class, () -> read(declaration));
        assertEquals("int required: placements", failure.getMessage());
    }

    private static FixedWitnessReplayRequest read(String declaration) {
        byte[] raw = bytes(declaration);
        return FixedWitnessReplayRequest.read(raw, INPUT, FixedWitnessInput.sha256(raw));
    }

    private static String validLiteral() {
        return VECTOR.replace("a".repeat(64), FixedWitnessInput.sha256(INPUT));
    }

    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
}
