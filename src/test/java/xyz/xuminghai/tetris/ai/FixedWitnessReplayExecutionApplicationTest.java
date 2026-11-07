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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Cases 8, 10, 11, 12 and 14 of the accepted sixteen-case budget; no historical input. */
class FixedWitnessReplayExecutionApplicationTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    @TempDir Path output;

    @Test
    void refusesActualArgvThatIsNotTheExplicitExecutionCommand() {
        assertThrows(IllegalStateException.class, () -> FixedWitnessReplayExecutionApplication.validateCommand(
                new String[]{"--replay-fixed"}, Path.of("/private-home"), Path.of("/private-tmp")));
        assertFalse(Files.exists(output.resolve("journal.jsonl")));
    }

    @Test
    void legalVocabularyWithIllegalMovementProducesInvalidReport() throws Exception {
        var request = request("LEFT>".repeat(6) + "HARD_DROP", 0);
        FixedWitnessReplayExecutionApplication.replay(request, output, quota(100_000));
        var result = FixedWitnessReplayExecutionApplication.report(request, output, 100_000,
                quota(16_384), quota(100_000), 100_000);
        assertEquals("INVALID", result.get("journal_state"));
        var report = JSON.readTree(Files.readAllBytes(output.resolve("report.json")));
        assertEquals(1, report.get("attempted").intValue());
        assertEquals(0, report.get("completed").intValue());
        assertFalse(report.get("eligibleForRuntimeValidation").asBoolean());
    }

    @Test
    void terminalExpectationMismatchIsNotPromotedBySuccessfulTransport() throws Exception {
        var request = request("HARD_DROP", 1);
        FixedWitnessReplayExecutionApplication.replay(request, output, quota(100_000));
        var result = FixedWitnessReplayExecutionApplication.report(request, output, 100_000,
                quota(16_384), quota(100_000), 100_000);
        assertEquals("EVIDENCE_MISMATCH", result.get("journal_state"));
        var report = JSON.readTree(Files.readAllBytes(output.resolve("report.json")));
        assertEquals(1, report.get("attempted").intValue());
        assertEquals(1, report.get("completed").intValue());
        assertFalse(report.get("provenanceAuthenticated").asBoolean());
        assertFalse(report.get("qCertified").asBoolean());
    }

    @Test
    void journalExhaustionKeepsForcedStartAndNeverRepeatsActions() throws Exception {
        var request = request("HARD_DROP", 0);
        // Exact START length is known from its declaration, without executing a sizing replay.
        int startSize = (JSON.writeValueAsString(Map.of("event", "START", "schema", 1,
                "inputHash", request.inputHash(), "paths", List.of(Map.of("id", "71", "placements", 1)),
                "expected", FixedWitnessReportCodec.expected(request.expected()))) + "\n")
                .getBytes(StandardCharsets.UTF_8).length;
        var journalQuota = quota(startSize);
        assertThrows(IOException.class, () -> FixedWitnessReplayExecutionApplication.replay(request, output, journalQuota));
        String prefix = Files.readString(output.resolve("journal.jsonl"));
        assertEquals(1, prefix.lines().count());
        assertEquals("START", JSON.readTree(prefix).get("event").asString());
        assertThrows(IOException.class, () -> journalQuota.charge(1));
        assertFalse(Files.exists(output.resolve("report.json")));
        assertFalse(Files.exists(output.resolve("replayed.json")));
    }

    @Test
    void reportWriteFailurePreservesClosedJournalAndPermanentCharge() throws Exception {
        var request = request("HARD_DROP", 0);
        FixedWitnessReplayExecutionApplication.replay(request, output, quota(100_000));
        byte[] original = Files.readAllBytes(output.resolve("journal.jsonl"));
        // Block CREATE_NEW after complete serialization and charging, without a sizing replay.
        Files.writeString(output.resolve("report.json"), "existing bytes");
        var reportQuota = quota(100_000);
        assertThrows(java.nio.file.FileAlreadyExistsException.class, () ->
                FixedWitnessReplayExecutionApplication.report(request, output, 100_000,
                        quota(16_384), reportQuota, 100_000));
        assertArrayEquals(original, Files.readAllBytes(output.resolve("journal.jsonl")));
        assertEquals("existing bytes", Files.readString(output.resolve("report.json")));
        assertThrows(IOException.class, () -> reportQuota.charge(100_000));
        assertTrue(Files.isRegularFile(output.resolve("report-request.json")));
        assertFalse(Files.exists(output.resolve("replayed.json")));
        assertEquals(1, Files.readString(output.resolve("journal.jsonl")).lines()
                .filter(line -> line.contains("\"event\":\"ATTEMPT\"")).count());
    }

    private static FixedWitnessReplayRequest request(String actions, int required) {
        byte[] input = (FixedWitnessInput.HEADER + "\nshape_feasibility_witness,71,1,O," + actions + "\n")
                .getBytes(StandardCharsets.UTF_8);
        return new FixedWitnessReplayRequest(input, FixedWitnessInput.sha256(input),
                List.of(new FixedWitnessInput.PathSpec("71", 1)),
                Map.of("71", new FixedWitnessReportChecker.Expected(required, 0, 0, 0, 0, 0, 0)));
    }

    private static FixedWitnessReplay.JournalQuota quota(int limit) {
        return new FixedWitnessReplay.JournalQuota(limit);
    }
}
