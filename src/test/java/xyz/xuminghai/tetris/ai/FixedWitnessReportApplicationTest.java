/*
 * Copyright (C) 2024-2026 xuMingHai
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Seven of the accepted sixteen artificial cases. Saved rows never execute actions. */
class FixedWitnessReportApplicationTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final byte[] INPUT = bytes(FixedWitnessInput.HEADER + "\n"
            + "shape_feasibility_witness,71,1,O,HARD_DROP\n");
    private static final List<FixedWitnessInput.PathSpec> PATHS = List.of(new FixedWitnessInput.PathSpec("71", 1));
    @TempDir Path directory;

    @Test
    void rejectsZeroExplicitReportAllowance() {
        assertThrows(IllegalArgumentException.class, () -> FixedWitnessReportApplication.requireArguments(
                new String[]{"--report-only", "/checkout", "/output", "a".repeat(64), "1024", "0",
                        "/snapshot", "b".repeat(64)}));
    }

    @Test
    void eofWithoutAckCreatesNoReport() {
        assertThrows(IllegalStateException.class, () -> FixedWitnessReplayApplication.acknowledge(
                new ByteArrayInputStream(new byte[0]), "a".repeat(64), "b".repeat(64), Duration.ofSeconds(1)));
        assertFalse(Files.exists(directory.resolve("report.json")));
    }

    @Test
    void savedPrefixRemainsIncomplete() throws Exception { assertState("INCOMPLETE"); }

    @Test
    void savedInvalidEndRemainsInvalid() throws Exception { assertState("INVALID"); }

    @Test
    void savedMismatchEndRemainsMismatch() throws Exception { assertState("EVIDENCE_MISMATCH"); }

    @Test
    void savedCompleteRemainsUncertified() throws Exception { assertState("COMPLETE"); }

    @Test
    void failedExclusiveWriteKeepsChargeAndExistingBytes() throws Exception {
        var request = request("INCOMPLETE");
        Path existing = directory.resolve("report.json");
        Files.writeString(existing, "original");
        Path sizing = directory.resolve("sizing.json");
        request.write(sizing, new FixedWitnessReplay.JournalQuota(100_000));
        var quota = new FixedWitnessReplay.JournalQuota((int) Files.size(sizing));
        assertThrows(java.nio.file.FileAlreadyExistsException.class, () ->
                FixedWitnessReportApplication.writeReport(request, existing, quota, 100_000));
        assertEquals("original", Files.readString(existing));
        assertThrows(java.io.IOException.class, () -> quota.charge(1));
    }

    private void assertState(String state) throws Exception {
        Path output = directory.resolve("report.json");
        var result = FixedWitnessReportApplication.writeReport(request(state), output,
                new FixedWitnessReplay.JournalQuota(100_000), 100_000);
        byte[] raw = Files.readAllBytes(output);
        assertEquals(state, result.get("state"));
        assertEquals(raw.length, result.get("bytes"));
        assertEquals(FixedWitnessInput.sha256(raw), result.get("sha256"));
        var report = JSON.readTree(raw);
        assertFalse(report.get("qCertified").asBoolean());
        assertFalse(report.get("provenanceAuthenticated").asBoolean());
        assertFalse(report.get("eligibleForRuntimeValidation").asBoolean());
    }

    private static FixedWitnessReportRequest request(String state) {
        var expected = Map.of("71", new FixedWitnessReportChecker.Expected(
                state.equals("EVIDENCE_MISMATCH") ? 1 : 0, 0, 0, 0, 0, 0, 0));
        String hash = FixedWitnessInput.sha256(INPUT);
        var journal = new StringBuilder(event(Map.of("event", "START", "schema", 1, "inputHash", hash,
                "paths", List.of(Map.of("id", "71", "placements", 1)),
                "expected", FixedWitnessReportCodec.expected(expected))));
        int count = state.equals("COMPLETE") || state.equals("EVIDENCE_MISMATCH") ? 1 : 0;
        if (count == 1) {
            journal.append(event(Map.of("event", "ATTEMPT", "attempt", 1, "pathId", "71", "step", 1)));
            var row = new FixedWitnessReplay.Row("71", 1, 2, "O", List.of("HARD_DROP"), hash,
                    occupancy("0".repeat(200)), occupancy("0".repeat(196) + "1111"), 0, 0,
                    new ShapeProgress(32, 0, 16, 0, 0), 1, 19,
                    new ShapeWitnessConstraintAudit.HoleBreakdown(0, 0, 0, 0, 0));
            journal.append(event(Map.of("event", "STEP", "row", FixedWitnessReportCodec.row(row))));
        }
        if (!state.equals("INCOMPLETE")) journal.append(event(Map.of("event", "END", "status", state,
                "attempted", count, "completed", count, "reason", state.equals("COMPLETE") ? "" : "artificial reason")));
        byte[] raw = bytes(journal.toString());
        var owned = new FixedWitnessReportRequest(INPUT, hash, PATHS, expected, raw, FixedWitnessInput.sha256(raw));
        // Exercise the strict descriptor and the same owned-byte consumption as the JVM entry.
        return FixedWitnessReportDescriptor.read(owned.canonicalBytes(), INPUT, raw, owned.requestHash());
    }

    private static FixedWitnessReplay.Occupancy occupancy(String bits) {
        return new FixedWitnessReplay.Occupancy(bits, FixedWitnessInput.sha256(bytes(bits)),
                (int) bits.chars().filter(c -> c == '1').count());
    }

    private static String event(Object value) { return JSON.writeValueAsString(value) + "\n"; }
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
}
