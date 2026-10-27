/*
 * Copyright (C) 2024-2026 xuMingHai
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigInteger;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Small artificial domains only; no registered HEART search. */
class TerminalCertificateJournalTest {
    @TempDir Path directory;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static TerminalCertificateGeometry.Domain domain() {
        var square = BigInteger.ZERO.setBit(0).setBit(1).setBit(8).setBit(9);
        var line = BigInteger.ZERO.setBit(11).setBit(12).setBit(13).setBit(14);
        return new TerminalCertificateGeometry.Domain(2, 8, square, square.or(line));
    }

    private static List<JsonNode> records(Path path) throws Exception {
        return Files.readAllLines(path).stream().map(JSON::readTree).toList();
    }

    @Test
    void persistsRealReplayClaimsFactsAndBudgetStopInOrder() throws Exception {
        var path = directory.resolve("session.jsonl");
        var result = TerminalCertificateJournal.run(path, domain(),
                new TerminalCertificateSession.Limits(20, 10, 2, 2));
        var records = records(path);
        assertEquals(List.of("start", "attempt", "attempt", "finish"), records.stream()
                .map(record -> record.get("event").asString()).toList());
        var first = records.get(1).get("data");
        var steps = TerminalCertificateRecords.parse(first.get("steps").toString());
        var checked = TerminalCertificateChecker.check(domain(), steps);
        assertEquals(checked.finalBoard().toString(16), first.get("checked").get("final_board").asString());
        assertEquals(checked.replays(), first.get("checked").get("replays").intValue());
        var fact = first.get("facts").get(0);
        assertEquals(0, fact.get("index").intValue());
        assertTrue(fact.get("survival_rank").intValue() > 0);
        assertTrue(fact.get("original_guard").has("budget_allowed"));
        var terminal = fact.get("resulting_facts");
        assertEquals(checked.terminal().rawHoles(), terminal.get("raw_holes").intValue());
        assertEquals(checked.terminal().accepted(), terminal.get("q").booleanValue());
        assertEquals(checked.terminal().rawHoles() - checked.terminal().requiredHoles()
                - checked.terminal().supportHoles() - checked.terminal().outsideHoles(),
                terminal.get("forbidden_holes").intValue());
        var second = records.get(2).get("data");
        assertTrue(second.get("steps").isNull());
        assertTrue(second.get("checked").isNull());
        assertTrue(second.get("facts").isEmpty());
        var finish = records.getLast().get("data");
        assertEquals(result.enumerations(), finish.get("enumerations").intValue());
        assertEquals(result.checkerReplays(), finish.get("checker_replays").intValue());
        assertEquals(result.geometry().stop().name(), finish.get("geometry").get("stop").asString());
        assertEquals("BOUNDED_MISS", finish.get("search_status").asString());
        assertEquals(0, finish.get("certificate_decomposition").intValue());
        for (var record : records) {
            assertEquals("terminal-certificate-session-v1", record.get("schema").asString());
            assertFalse(record.get("provenance_authenticated").booleanValue());
            assertFalse(record.get("eligible_for_runtime_validation").booleanValue());
        }
    }

    @Test
    void rootCountStopHasNoInventedAttemptOrReplay() throws Exception {
        var path = directory.resolve("root.jsonl");
        TerminalCertificateJournal.run(path, domain(), new TerminalCertificateSession.Limits(1, 10, 20, 2));
        var records = records(path);
        assertEquals(2, records.size());
        assertEquals("INCOMPLETE", records.getFirst().get("data").get("state").asString());
        var finish = records.getLast().get("data");
        assertEquals("NODE_LIMIT", finish.get("geometry").get("stop").asString());
        assertEquals(0, finish.get("enumerations").intValue());
        assertEquals(0, finish.get("checker_replays").intValue());
    }

    @Test
    void refusesExistingFailurePrefixWithoutChangingBytes() throws Exception {
        var path = directory.resolve("prior.jsonl");
        Files.writeString(path, "{\"event\":\"start\"}\n{\"torn");
        var before = Files.readAllBytes(path);
        assertThrows(FileAlreadyExistsException.class, () -> TerminalCertificateJournal.run(path,
                domain(), TerminalCertificateSession.Limits.registered()));
        assertArrayEquals(before, Files.readAllBytes(path));
    }

    @Test
    void setupFailurePropagatesWithErrorAndNeverNormalFinish() throws Exception {
        var path = directory.resolve("error.jsonl");
        assertThrows(NullPointerException.class, () -> TerminalCertificateJournal.run(path, domain(), null));
        var records = records(path);
        assertEquals(1, records.size());
        assertEquals("error", records.getFirst().get("event").asString());
        assertEquals("ERROR", records.getFirst().get("data").get("state").asString());
        assertEquals(NullPointerException.class.getName(),
                records.getFirst().get("data").get("exception_class").asString());
    }
}
