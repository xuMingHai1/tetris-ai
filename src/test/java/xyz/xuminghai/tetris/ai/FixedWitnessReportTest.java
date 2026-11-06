/*
 * Copyright (C) 2024-2026 xuMingHai
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Artificial inputs only. Reports never execute a second action replay or open historical data. */
class FixedWitnessReportTest {
    @TempDir Path directory;
    private int nextJournal;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final List<FixedWitnessInput.PathSpec> PATHS = List.of(
            new FixedWitnessInput.PathSpec("7", 2), new FixedWitnessInput.PathSpec("9", 1));
    private static final String CSV = FixedWitnessInput.HEADER + "\n"
            + "shape_feasibility_witness,7,1,O,HARD_DROP\n"
            + "shape_feasibility_witness,7,2,O,HARD_DROP\n"
            + "shape_feasibility_witness,9,1,O,HARD_DROP\n";
    private static final Map<String, FixedWitnessReportChecker.Expected> EXPECTED = Map.of(
            "7", expected(4), "9", expected(0));

    @Test
    void reportsSavedFactsAndIndependentPathTerminalsWithoutPromotingPermissions() throws Exception {
        var journal = journal(CSV, EXPECTED);
        byte[] original = Files.readAllBytes(journal);
        var output = directory.resolve("report.json");
        write(journal, CSV, EXPECTED, output, quota(100_000));
        var report = JSON.readTree(Files.readString(output));
        assertEquals("COMPLETE", report.get("journalState").asString());
        assertTrue(report.get("structuralPrefixValid").asBoolean());
        assertEquals(3, report.get("attempted").asInt());
        assertEquals(3, report.get("completed").asInt());
        var rows = report.get("observations");
        assertEquals(3, rows.size());
        assertEquals(28, rows.get(1).get("missingRequired").asInt());
        assertEquals(32, rows.get(2).get("missingRequired").asInt());
        assertEquals(0, rows.get(2).get("absoluteClearedLines").asInt());
        assertEquals(0, rows.get(2).get("nonForbiddenHoles").asInt());
        assertEquals(0, rows.get(2).get("heightAboveTarget").asInt());
        assertEquals(2, report.get("terminals").size());
        for (var terminal : report.get("terminals")) {
            assertTrue(terminal.get("matchesExpected").asBoolean());
            assertFalse(terminal.get("clean").asBoolean());
            assertFalse(terminal.get("hasClearedLines").asBoolean());
            assertEquals("NECESSARY_CONDITION_FAILED", terminal.get("q").asString());
        }
        for (String permission : List.of("provenanceAuthenticated", "qCertified", "eligibleForRuntimeValidation")) {
            assertFalse(report.get(permission).asBoolean());
        }
        assertArrayEquals(original, Files.readAllBytes(journal));
    }

    @Test
    void partialAndInvalidPrefixesDoNotInventMissingTerminalFacts() throws Exception {
        var journal = journal(CSV, EXPECTED);
        var lines = Files.readAllLines(journal);
        Files.writeString(journal, String.join("\n", lines.subList(0, 3)) + "\n");
        var output = directory.resolve("partial.json");
        write(journal, CSV, EXPECTED, output, quota(100_000));
        var report = JSON.readTree(Files.readString(output));
        assertEquals("INCOMPLETE", report.get("journalState").asString());
        assertEquals(1, report.get("observations").size());
        for (var terminal : report.get("terminals")) {
            assertEquals("NOT_RECORDED", terminal.get("assessment").asString());
            assertNull(terminal.get("clean"));
            assertNull(terminal.get("absoluteClearedLines"));
        }
        String invalid = CSV.replace("7,2,O,HARD_DROP", "7,2,O," + "LEFT>".repeat(6) + "HARD_DROP");
        var invalidJournal = journal(invalid, EXPECTED);
        var invalidOutput = directory.resolve("invalid.json");
        write(invalidJournal, invalid, EXPECTED, invalidOutput, quota(100_000));
        assertEquals("INVALID", JSON.readTree(Files.readString(invalidOutput)).get("journalState").asString());
    }

    @Test
    void distinguishesTerminalDisagreementFromCorruptStructuralFacts() throws Exception {
        var expectations = Map.of("7", expected(0), "9", expected(0));
        var journal = journal(CSV, expectations);
        var output = directory.resolve("mismatch.json");
        write(journal, CSV, expectations, output, quota(100_000));
        var report = JSON.readTree(Files.readString(output));
        assertEquals("EVIDENCE_MISMATCH", report.get("journalState").asString());
        assertTrue(report.get("structuralPrefixValid").asBoolean());
        assertFalse(report.get("terminals").get(0).get("matchesExpected").asBoolean());
        assertEquals("NOT_RECORDED", report.get("terminals").get(1).get("assessment").asString());

        // Preserve a syntactically valid, correctly counted mismatch END, but corrupt the row hash.
        var lines = Files.readAllLines(journal);
        var firstRow = JSON.readTree(lines.get(2));
        ((tools.jackson.databind.node.ObjectNode) firstRow.get("row").get("after"))
                .put("sha256", "0".repeat(64));
        String corrupt = lines.get(0) + "\n" + lines.get(1) + "\n"
                + JSON.writeValueAsString(firstRow) + "\n"
                + JSON.writeValueAsString(Map.of("event", "END", "status", "EVIDENCE_MISMATCH",
                        "attempted", 1, "completed", 1, "reason", "corrupt board hash")) + "\n";
        Files.writeString(journal, corrupt);
        var corruptOutput = directory.resolve("corrupt.json");
        write(journal, CSV, expectations, corruptOutput, quota(100_000));
        var corruptReport = JSON.readTree(Files.readString(corruptOutput));
        assertFalse(corruptReport.get("structuralPrefixValid").asBoolean());
        assertEquals(1, corruptReport.get("recordedRows").size());
        assertTrue(corruptReport.get("observations").isEmpty());
        assertTrue(corruptReport.get("terminals").isEmpty());
    }

    @Test
    void derivesNonzeroGeometryFromRecordedMetricsWithoutClaimingMetricAuthentication() throws Exception {
        var expectations = Map.of("7", expected(0), "9", expected(0));
        var journal = journal(CSV, expectations);
        var lines = Files.readAllLines(journal);
        var event = JSON.readTree(lines.get(4));
        var row = (tools.jackson.databind.node.ObjectNode) event.get("row");
        row.put("maxHeight", 10).put("headroom", 10);
        var progress = (tools.jackson.databind.node.ObjectNode) row.get("progress");
        progress.put("forbiddenOccupiedCells", 1).put("supportOccupiedCells", 3);
        var holes = (tools.jackson.databind.node.ObjectNode) row.get("holes");
        holes.put("totalHoles", 6).put("requiredHoles", 1).put("forbiddenHoles", 2)
                .put("supportAllowedHoles", 2).put("outsideTargetHoles", 1);
        lines.set(4, JSON.writeValueAsString(event));
        Files.writeString(journal, String.join("\n", lines) + "\n");
        var output = directory.resolve("geometry.json");
        write(journal, CSV, expectations, output, quota(100_000));
        var report = JSON.readTree(Files.readString(output));
        var observed = report.get("observations").get(1);
        assertEquals(28, observed.get("missingRequired").asInt());
        assertEquals(1, observed.get("forbiddenOccupied").asInt());
        assertEquals(4, observed.get("nonForbiddenHoles").asInt());
        assertEquals(2, observed.get("heightAboveTarget").asInt());
        assertEquals(4, observed.get("violations").size());
        assertFalse(report.get("provenanceAuthenticated").asBoolean());
    }

    @Test
    void chargesReportFromSameJournalAllowanceAndPreservesExistingArtifacts() throws Exception {
        var journal = journal(CSV, EXPECTED);
        var reference = directory.resolve("reference.json");
        write(journal, CSV, EXPECTED, reference, quota(100_000));
        byte[] bytes = Files.readAllBytes(reference);
        var shortOutput = directory.resolve("too-small.json");
        assertThrows(IOException.class, () -> write(journal, CSV, EXPECTED, shortOutput, quota(bytes.length - 1)));
        assertFalse(Files.exists(shortOutput));

        int journalBytes = Math.toIntExact(Files.size(journal));
        var shared = quota(journalBytes + bytes.length);
        var sharedJournal = directory.resolve("shared.jsonl");
        FixedWitnessReplay.run(input(CSV), hash(CSV), PATHS, EXPECTED, sharedJournal, shared);
        var exact = directory.resolve("exact.json");
        write(sharedJournal, CSV, EXPECTED, exact, shared);
        assertArrayEquals(bytes, Files.readAllBytes(exact));
        assertThrows(IOException.class, () -> shared.charge(1));

        var failed = quota(bytes.length);
        assertThrows(FileAlreadyExistsException.class, () -> write(journal, CSV, EXPECTED, reference, failed));
        assertArrayEquals(bytes, Files.readAllBytes(reference));
        assertThrows(IOException.class, () -> failed.charge(1));
    }

    @Test
    void rejectsUntrustedJournalBeforeCreatingReport() throws Exception {
        var journal = journal(CSV, EXPECTED);
        Files.writeString(journal, Files.readString(journal) + "{}\n");
        var output = directory.resolve("rejected.json");
        assertThrows(IllegalArgumentException.class, () -> write(journal, CSV, EXPECTED, output, quota(100_000)));
        assertFalse(Files.exists(output));
    }

    private Path journal(String csv, Map<String, FixedWitnessReportChecker.Expected> expected) throws Exception {
        var journal = directory.resolve("journal-" + nextJournal++ + ".jsonl");
        FixedWitnessReplay.run(input(csv), hash(csv), PATHS, expected, journal, quota(100_000));
        return journal;
    }

    private static void write(Path journal, String csv, Map<String, FixedWitnessReportChecker.Expected> expected,
                              Path output, FixedWitnessReplay.JournalQuota quota) throws IOException {
        FixedWitnessReport.write(journal, input(csv), hash(csv), PATHS, expected, output, quota);
    }

    private static byte[] input(String csv) { return csv.getBytes(StandardCharsets.UTF_8); }
    private static String hash(String csv) { return FixedWitnessInput.sha256(input(csv)); }
    private static FixedWitnessReplay.JournalQuota quota(int bytes) { return new FixedWitnessReplay.JournalQuota(bytes); }
    private static FixedWitnessReportChecker.Expected expected(int required) {
        return new FixedWitnessReportChecker.Expected(required, 0, 0, 0, 0, 0, 0);
    }
}
