/*
 * Copyright (C) 2024-2026 xuMingHai
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Sixteen small saved-byte cases; no replay, action execution or historical resource access. */
class FixedWitnessReportRequestTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final List<FixedWitnessInput.PathSpec> PATHS = List.of(
            new FixedWitnessInput.PathSpec("71", 1), new FixedWitnessInput.PathSpec("93", 1));
    private static final Map<String, FixedWitnessReportChecker.Expected> EXPECTED = Map.of(
            "71", expected(0), "93", expected(0));
    private static final byte[] INPUT = bytes(FixedWitnessInput.HEADER + "\n"
            + "shape_feasibility_witness,71,1,O,HARD_DROP\n"
            + "shape_feasibility_witness,93,1,O,HARD_DROP\n");
    @TempDir Path directory;
    private int nextOutput;

    @Test
    void ownsArraysListsAndMapsAfterConstruction() throws Exception {
        byte[] input = INPUT.clone();
        byte[] journal = journal(EXPECTED, false, "COMPLETE");
        var paths = new ArrayList<>(PATHS);
        var expected = new LinkedHashMap<>(EXPECTED);
        var request = request(input, paths, expected, journal);
        var canonical = request.canonicalBytes();
        Arrays.fill(input, (byte) 0);
        Arrays.fill(journal, (byte) 0);
        paths.clear();
        expected.clear();
        byte[] exposed = request.canonicalBytes();
        exposed[0] = 0;
        assertArrayEquals(canonical, request.canonicalBytes());
        assertEquals("COMPLETE", report(request).get("journalState").asString());
    }

    @Test
    void canonicalIdentityMatchesLiteralOrderedVectorRegardlessOfMapOrder() {
        byte[] journal = journal(EXPECTED, false, "COMPLETE");
        var reverse = new LinkedHashMap<String, FixedWitnessReportChecker.Expected>();
        reverse.put("93", expected(0));
        reverse.put("71", expected(0));
        var request = request(INPUT, PATHS, reverse, journal);
        String fields = "{\"required\":0,\"forbidden\":0,\"rawHoles\":0,\"requiredHoles\":0,"
                + "\"forbiddenHoles\":0,\"supportHoles\":0,\"outsideHoles\":0}";
        String literal = "{\"schema\":\"fixed-witness-report-request-v1\",\"inputSha256\":\""
                + hash(INPUT) + "\",\"journalSha256\":\"" + hash(journal)
                + "\",\"paths\":[{\"id\":\"71\",\"placements\":1},{\"id\":\"93\",\"placements\":1}],"
                + "\"expected\":{\"71\":" + fields + ",\"93\":" + fields + "}}\n";
        assertArrayEquals(bytes(literal), request.canonicalBytes());
        assertEquals(hash(bytes(literal)), request.requestHash());
        assertEquals(request(INPUT, PATHS, EXPECTED, journal).requestHash(), request.requestHash());
    }

    @Test
    void eachChangedDeclarationChangesIdentity() {
        byte[] journal = journal(EXPECTED, false, "COMPLETE");
        var original = request(INPUT, PATHS, EXPECTED, journal);
        var changed = Map.of("71", expected(1), "93", expected(0));
        assertNotEquals(original.requestHash(), request(INPUT, PATHS, changed, journal).requestHash());
        assertNotEquals(original.requestHash(), request(INPUT, PATHS, EXPECTED,
                Arrays.copyOf(journal, journal.length - 1)).requestHash());
        byte[] input = bytes(new String(INPUT, StandardCharsets.UTF_8).replace(
                "71,1,O,HARD_DROP", "71,1,O,LEFT>HARD_DROP"));
        assertNotEquals(original.requestHash(), request(input, PATHS, EXPECTED, journal).requestHash());
    }

    @Test
    void pathOrderIsPartOfIdentity() {
        byte[] journal = journal(EXPECTED, false, "COMPLETE");
        byte[] reversedInput = bytes(FixedWitnessInput.HEADER + "\n"
                + "shape_feasibility_witness,93,1,O,HARD_DROP\n"
                + "shape_feasibility_witness,71,1,O,HARD_DROP\n");
        assertNotEquals(request(INPUT, PATHS, EXPECTED, journal).requestHash(),
                request(reversedInput, PATHS.reversed(), EXPECTED, journal).requestHash());
    }

    @Test
    void invalidDeclarationsFailBeforeAnyOutput() {
        byte[] journal = journal(EXPECTED, false, "COMPLETE");
        assertThrows(IllegalArgumentException.class, () -> request(INPUT, PATHS, Map.of("71", expected(0)), journal));
        var extra = new LinkedHashMap<>(EXPECTED);
        extra.put("5", expected(0));
        assertThrows(IllegalArgumentException.class, () -> request(INPUT, PATHS, extra, journal));
        extra.remove("5");
        extra.put("71", null);
        assertThrows(NullPointerException.class, () -> request(INPUT, PATHS, extra, journal));
        assertThrows(IllegalArgumentException.class, () -> request(bytes("bad\n"), PATHS, EXPECTED, journal));
        assertThrows(IllegalArgumentException.class, () -> new FixedWitnessReportRequest(INPUT,
                hash(INPUT).toUpperCase(java.util.Locale.ROOT), PATHS, EXPECTED, journal, hash(journal)));
        assertThrows(IllegalArgumentException.class, () -> request(INPUT, List.of(PATHS.getFirst(), PATHS.getFirst()),
                EXPECTED, journal));
    }

    @Test
    void inputHashMismatchIsRejected() {
        byte[] journal = journal(EXPECTED, false, "COMPLETE");
        assertThrows(IllegalArgumentException.class, () -> new FixedWitnessReportRequest(INPUT,
                "0".repeat(64), PATHS, EXPECTED, journal, hash(journal)));
    }

    @Test
    void journalHashMismatchIsRejected() {
        byte[] journal = journal(EXPECTED, false, "COMPLETE");
        assertThrows(IllegalArgumentException.class, () -> new FixedWitnessReportRequest(INPUT,
                hash(INPUT), PATHS, EXPECTED, journal, "0".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> new FixedWitnessReportRequest(INPUT,
                hash(INPUT), PATHS, EXPECTED, journal, "invalid"));
    }

    @Test
    void readerPathAndSnapshotAgreeAndRejectOversize() throws Exception {
        byte[] journal = journal(EXPECTED, false, "COMPLETE");
        Path file = directory.resolve("saved.jsonl");
        Files.write(file, journal);
        assertEquals(FixedWitnessJournalReader.read(file, INPUT, hash(INPUT), PATHS, EXPECTED),
                FixedWitnessJournalReader.read(journal, INPUT, hash(INPUT), PATHS, EXPECTED));
        byte[] oversized = new byte[10 * 1024 * 1024 + 1];
        assertThrows(IllegalArgumentException.class, () -> FixedWitnessJournalReader.read(
                oversized, INPUT, hash(INPUT), PATHS, EXPECTED));
        assertThrows(IllegalArgumentException.class, () -> request(INPUT, PATHS, EXPECTED, oversized));
    }

    @Test
    void completeSavedRowsRemainFactsWithoutPermissionPromotion() throws Exception {
        var result = report(request(INPUT, PATHS, EXPECTED, journal(EXPECTED, false, "COMPLETE")));
        assertEquals("COMPLETE", result.get("journalState").asString());
        assertEquals(2, result.get("recordedRows").size());
        assertEquals(2, result.get("terminals").size());
        assertEquals(32, result.get("observations").get(0).get("missingRequired").asInt());
        for (String field : List.of("provenanceAuthenticated", "qCertified", "eligibleForRuntimeValidation")) {
            assertFalse(result.get(field).asBoolean());
        }
    }

    @Test
    void tornUtf8TailIsBoundButNeverDecodedAsEvidence() throws Exception {
        byte[] prefix = journal(EXPECTED, false, null);
        byte[] torn = Arrays.copyOf(prefix, prefix.length + 2);
        torn[prefix.length] = (byte) 0xe2;
        torn[prefix.length + 1] = (byte) 0x82;
        var result = report(request(INPUT, PATHS, EXPECTED, torn));
        assertEquals("INCOMPLETE", result.get("journalState").asString());
        assertEquals(hash(torn), result.get("requestIdentity").get("journalSha256").asString());
    }

    @Test
    void malformedRecordsAndPostEndBytesFailWithoutReport() {
        var output = directory.resolve("rejected.json");
        for (byte[] journal : List.of(bytes("{}\n"), bytes(new String(
                journal(EXPECTED, false, "COMPLETE"), StandardCharsets.UTF_8) + "x"))) {
            var request = request(INPUT, PATHS, EXPECTED, journal);
            assertThrows(IllegalArgumentException.class, () -> request.write(output, quota(100_000)));
            assertFalse(Files.exists(output));
        }
    }

    @Test
    void invalidAndTerminalDisagreementKeepTheirReaderStates() throws Exception {
        byte[] invalid = bytes(start(EXPECTED) + event(Map.of("event", "END", "status", "INVALID",
                "attempted", 0, "completed", 0, "reason", "artificial stop")));
        assertEquals("INVALID", report(request(INPUT, PATHS, EXPECTED, invalid)).get("journalState").asString());
        var expected = Map.of("71", expected(1), "93", expected(0));
        var result = report(request(INPUT, PATHS, expected, journal(expected, false, "EVIDENCE_MISMATCH")));
        assertEquals("EVIDENCE_MISMATCH", result.get("journalState").asString());
        assertTrue(result.get("structuralPrefixValid").asBoolean());
        assertFalse(result.get("terminals").get(0).get("matchesExpected").asBoolean());
    }

    @Test
    void corruptRowPreservedAndGeometrySuppressedWithAndWithoutMismatchEnd() throws Exception {
        for (String end : new String[] {null, "EVIDENCE_MISMATCH"}) {
            var result = report(request(INPUT, PATHS, EXPECTED, journal(EXPECTED, true, end)));
            assertEquals(end == null ? "INCOMPLETE" : end, result.get("journalState").asString());
            assertFalse(result.get("structuralPrefixValid").asBoolean());
            assertEquals(1, result.get("recordedRows").size());
            assertTrue(result.get("observations").isEmpty());
            assertTrue(result.get("terminals").isEmpty());
        }
    }

    @Test
    void identityBytesCountAtExactLimitAndOneByteShort() throws Exception {
        var request = request(INPUT, PATHS, EXPECTED, journal(EXPECTED, false, "COMPLETE"));
        Path reference = write(request, quota(100_000));
        int size = Math.toIntExact(Files.size(reference));
        assertArrayEquals(Files.readAllBytes(reference), Files.readAllBytes(write(request, quota(size))));
        Path shortFile = directory.resolve("short.json");
        assertThrows(IOException.class, () -> request.write(shortFile, quota(size - 1)));
        assertFalse(Files.exists(shortFile));
    }

    @Test
    void sharedAllowanceAccumulatesAndExclusiveFailureNeverRefunds() throws Exception {
        var request = request(INPUT, PATHS, EXPECTED, journal(EXPECTED, false, "COMPLETE"));
        Path reference = write(request, quota(100_000));
        byte[] original = Files.readAllBytes(reference);
        int size = original.length;
        var shared = quota(size * 2);
        write(request, shared);
        write(request, shared);
        assertThrows(IOException.class, () -> shared.charge(1));
        var failed = quota(size);
        assertThrows(FileAlreadyExistsException.class, () -> request.write(reference, failed));
        assertThrows(IOException.class, () -> failed.charge(1));
        assertArrayEquals(original, Files.readAllBytes(reference));
    }

    @Test
    void outputDeclarationRecomputesIdentityAndLegacyOutputStaysUnextended() throws Exception {
        byte[] journal = journal(EXPECTED, false, "COMPLETE");
        var request = request(INPUT, PATHS, EXPECTED, journal);
        var identity = (ObjectNode) report(request).get("requestIdentity");
        String recordedHash = identity.remove("requestSha256").asString();
        assertEquals(recordedHash, hash(bytes(JSON.writeValueAsString(identity) + "\n")));
        assertEquals(request.requestHash(), recordedHash);
        Path saved = directory.resolve("legacy.jsonl");
        Files.write(saved, journal);
        Path legacy = directory.resolve("legacy.json");
        FixedWitnessReport.write(saved, INPUT, hash(INPUT), PATHS, EXPECTED, legacy, quota(100_000));
        assertNull(JSON.readTree(Files.readString(legacy)).get("requestIdentity"));
    }

    private JsonNode report(FixedWitnessReportRequest request) throws Exception {
        return JSON.readTree(Files.readString(write(request, quota(100_000))));
    }
    private Path write(FixedWitnessReportRequest request, FixedWitnessReplay.JournalQuota quota) throws IOException {
        Path output = directory.resolve("report-" + nextOutput++ + ".json");
        request.write(output, quota);
        return output;
    }
    private static FixedWitnessReportRequest request(byte[] input, List<FixedWitnessInput.PathSpec> paths,
            Map<String, FixedWitnessReportChecker.Expected> expected, byte[] journal) {
        return new FixedWitnessReportRequest(input, hash(input), paths, expected, journal, hash(journal));
    }
    private static byte[] journal(Map<String, FixedWitnessReportChecker.Expected> expected,
                                  boolean corrupt, String status) {
        var result = new StringBuilder(start(expected));
        int count = corrupt || "EVIDENCE_MISMATCH".equals(status) ? 1 : 2;
        for (int i = 0; i < count; i++) {
            String id = PATHS.get(i).id();
            result.append(event(Map.of("event", "ATTEMPT", "attempt", i + 1, "pathId", id, "step", 1)));
            var before = occupancy("0".repeat(200));
            var after = occupancy("0".repeat(196) + "1111");
            if (corrupt) after = new FixedWitnessReplay.Occupancy(after.bits(), "0".repeat(64), 4);
            var row = new FixedWitnessReplay.Row(id, 1, i + 2, "O", List.of("HARD_DROP"), hash(INPUT),
                    before, after, 0, 0, new ShapeProgress(32, 0, 16, 0, 0), 1, 19,
                    new ShapeWitnessConstraintAudit.HoleBreakdown(0, 0, 0, 0, 0));
            result.append(event(Map.of("event", "STEP", "row", FixedWitnessReportCodec.row(row))));
        }
        if (status != null) result.append(event(Map.of("event", "END", "status", status,
                "attempted", count, "completed", count, "reason", status.equals("COMPLETE") ? "" : "artificial mismatch")));
        return bytes(result.toString());
    }
    private static String start(Map<String, FixedWitnessReportChecker.Expected> expected) {
        return event(Map.of("event", "START", "schema", 1, "inputHash", hash(INPUT),
                "paths", PATHS.stream().map(p -> Map.of("id", p.id(), "placements", p.placements())).toList(),
                "expected", FixedWitnessReportCodec.expected(expected)));
    }
    private static FixedWitnessReplay.Occupancy occupancy(String bits) {
        return new FixedWitnessReplay.Occupancy(bits, hash(bytes(bits)), (int) bits.chars().filter(c -> c == '1').count());
    }
    private static String event(Object value) { return JSON.writeValueAsString(value) + "\n"; }
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static String hash(byte[] bytes) { return FixedWitnessInput.sha256(bytes); }
    private static FixedWitnessReportChecker.Expected expected(int required) {
        return new FixedWitnessReportChecker.Expected(required, 0, 0, 0, 0, 0, 0);
    }
    private static FixedWitnessReplay.JournalQuota quota(int bytes) {
        return new FixedWitnessReplay.JournalQuota(bytes);
    }
}
