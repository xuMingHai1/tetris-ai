/*
 * Copyright (C) 2024-2026 xuMingHai
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** Actual small-domain journal/replay, plus corrupted external-file counterexamples. */
class TerminalCertificateJournalReaderTest {
    @TempDir Path directory;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private Path journal() throws Exception {
        var square = BigInteger.ZERO.setBit(0).setBit(1).setBit(8).setBit(9);
        var line = BigInteger.ZERO.setBit(11).setBit(12).setBit(13).setBit(14);
        var path = directory.resolve("journal-" + System.nanoTime() + ".jsonl");
        TerminalCertificateJournal.run(path, new TerminalCertificateGeometry.Domain(2, 8, square, square.or(line)),
                new TerminalCertificateSession.Limits(20, 10, 2, 2));
        return path;
    }

    private Path changed(Path original, int record, Consumer<ObjectNode> mutate) throws Exception {
        var lines = new ArrayList<>(Files.readAllLines(original));
        var node = (ObjectNode) JSON.readTree(lines.get(record));
        mutate.accept(node);
        lines.set(record, node.toString());
        var path = directory.resolve("changed-" + System.nanoTime() + ".jsonl");
        Files.writeString(path, String.join("\n", lines) + "\n");
        return path;
    }

    private void rejected(Path original, int record, Consumer<ObjectNode> mutate) throws Exception {
        var path = changed(original, record, mutate);
        assertThrows(IllegalArgumentException.class, () -> TerminalCertificateJournalReader.read(path));
    }

    @Test
    void readsForcedJournalAndIndependentlyReplaysCompletedArtificialOrder() throws Exception {
        var result = TerminalCertificateJournalReader.read(journal());
        assertEquals(TerminalCertificateJournalReader.State.COMPLETE, result.state());
        assertEquals(2, result.attempts());
        assertEquals(2, result.enumerations());
        assertEquals(1, result.checkerReplays());
        // COMPLETE describes file consistency only; this artificial-domain search is a miss.
        assertTrue(result.solverReplays() >= result.checkerReplays());
    }

    @Test
    void everyUnfinishedPrefixIncludingTornFinishRemainsIncomplete() throws Exception {
        var original = journal();
        var text = Files.readString(original);
        var path = directory.resolve("prefix.jsonl");
        var lines = Files.readAllLines(original);
        int length = 0;
        for (int i = 0; i < lines.size(); i++) {
            Files.writeString(path, text.substring(0, length));
            assertEquals(TerminalCertificateJournalReader.State.INCOMPLETE,
                    TerminalCertificateJournalReader.read(path).state());
            Files.writeString(path, text.substring(0, length + lines.get(i).length() / 2));
            assertEquals(TerminalCertificateJournalReader.State.INCOMPLETE,
                    TerminalCertificateJournalReader.read(path).state());
            length += lines.get(i).length() + 1;
        }
        Files.writeString(path, text.substring(0, text.length() - 1));
        assertEquals(TerminalCertificateJournalReader.State.INCOMPLETE,
                TerminalCertificateJournalReader.read(path).state());
    }

    @Test
    void explicitSetupErrorIsNeverCompleteAndBytesAfterTerminalAreRejected() throws Exception {
        var path = directory.resolve("error.jsonl");
        assertThrows(NullPointerException.class, () -> TerminalCertificateJournal.run(path,
                TerminalCertificateGeometry.Domain.heart(), null));
        assertEquals(TerminalCertificateJournalReader.State.ERROR, TerminalCertificateJournalReader.read(path).state());
        Files.writeString(path, Files.readString(path) + "{\"torn");
        assertThrows(IllegalArgumentException.class, () -> TerminalCertificateJournalReader.read(path));
        var normal = journal();
        Files.writeString(normal, Files.readString(normal) + "\n");
        assertThrows(IllegalArgumentException.class, () -> TerminalCertificateJournalReader.read(normal));
    }

    @Test
    void rejectsSequenceCountersStopsAndInventedCertificate() throws Exception {
        var path = journal();
        rejected(path, 1, n -> ((ObjectNode) n.get("data")).put("decomposition", 2));
        rejected(path, 2, n -> ((ObjectNode) n.get("data")).put("enumerations", 1));
        rejected(path, 3, n -> ((ObjectNode) n.get("data")).put("checker_replays", 0));
        rejected(path, 3, n -> ((ObjectNode) n.get("data")).put("certificate_decomposition", 1));
        rejected(path, 3, n -> ((ObjectNode) n.get("data")).put("search_status", "Q_FOUND"));
        rejected(path, 3, n -> ((ObjectNode) n.get("data").get("geometry")).put("stop", "EXHAUSTED"));
        rejected(path, 2, n -> ((ObjectNode) n.get("data")).put("order_stop", "EXHAUSTED"));
    }

    @Test
    void rejectsReplayBoardBagAndFactDisagreements() throws Exception {
        var path = journal();
        rejected(path, 1, n -> ((ObjectNode) n.get("data").get("checked")).put("final_board", "0"));
        rejected(path, 1, n -> ((ObjectNode) n.get("data").get("facts").get(0)).put("bag_after", 0));
        rejected(path, 1, n -> ((ObjectNode) n.get("data").get("facts").get(0)
                .get("resulting_facts")).put("raw_holes", 99));
        rejected(path, 1, n -> ((ObjectNode) n.get("data").get("steps").get("steps").get(0))
                .put("post_board", "0"));
        rejected(path, 1, n -> ((ObjectNode) n.get("data").get("facts").get(0))
                .put("ordinary_allowed", false));
    }

    @Test
    void rejectsUnknownMissingCoercedAndPermissionFields() throws Exception {
        var path = journal();
        rejected(path, 0, n -> n.put("extra", false));
        rejected(path, 0, n -> n.remove("schema"));
        rejected(path, 0, n -> n.put("provenance_authenticated", true));
        rejected(path, 0, n -> n.put("eligible_for_runtime_validation", "false"));
        rejected(path, 0, n -> ((ObjectNode) n.get("data").get("limits")).put("nodes", "20"));
        rejected(path, 0, n -> ((ObjectNode) n.get("data").get("domain")).put("required", "01"));
        rejected(path, 1, n -> ((ObjectNode) n.get("data").get("facts").get(0).get("original_guard"))
                .put("danger", "false"));
        rejected(path, 1, n -> ((ObjectNode) n.get("data").get("facts").get(0).get("original_guard"))
                .put("risk", "UNKNOWN"));
        rejected(path, 2, n -> ((ObjectNode) n.get("data")).put("checked", false));
    }

    @Test
    void rejectsDuplicateTrailingMalformedUtf8AndOversizedLines() throws Exception {
        var path = directory.resolve("invalid.jsonl");
        for (var text : new String[]{"{\"event\":\"start\",\"event\":\"start\"}\n", "{} {}\n", "\n"}) {
            Files.writeString(path, text);
            assertThrows(RuntimeException.class, () -> TerminalCertificateJournalReader.read(path));
        }
        Files.write(path, new byte[]{(byte) 0xc3, 0x28, '\n'});
        assertThrows(IllegalArgumentException.class, () -> TerminalCertificateJournalReader.read(path));
        Files.writeString(path, " ".repeat(TerminalCertificateJournalReader.MAX_LINE_BYTES + 1));
        assertThrows(IllegalArgumentException.class, () -> TerminalCertificateJournalReader.read(path));
    }

    @Test
    void embeddedStepsKeepTheirOwnOneMibLimitWithinTwoMibJournalLine() throws Exception {
        var original = journal();
        var actions = JSON.createArrayNode();
        for (int i = 0; i < 160_000; i++) actions.add("LEFT");
        var path = changed(original, 1, n -> ((ObjectNode) n.get("data").get("steps").get("steps").get(0))
                .set("actions", actions));
        assertTrue(Files.readAllLines(path).get(1).length() < TerminalCertificateJournalReader.MAX_LINE_BYTES);
        var failure = assertThrows(IllegalArgumentException.class, () -> TerminalCertificateJournalReader.read(path));
        assertEquals("embedded steps too large", failure.getMessage());
    }

    @Test
    void rootNodeCapDoesNotNeedAnInventedAttempt() throws Exception {
        var path = directory.resolve("root.jsonl");
        TerminalCertificateJournal.run(path, TerminalCertificateGeometry.Domain.heart(),
                new TerminalCertificateSession.Limits(1, 1, 1, 1));
        var summary = TerminalCertificateJournalReader.read(path);
        assertEquals(TerminalCertificateJournalReader.State.COMPLETE, summary.state());
        assertEquals(0, summary.attempts());
        assertEquals(0, summary.enumerations());
    }
}
