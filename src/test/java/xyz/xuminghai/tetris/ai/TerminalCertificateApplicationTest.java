/*
 * Copyright (C) 2024-2026 xuMingHai
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigInteger;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Artificial domains only; no test invokes the registered HEART CLI command. */
class TerminalCertificateApplicationTest {
    @TempDir Path directory;

    private static TerminalCertificateGeometry.Domain domain() {
        var square = BigInteger.ZERO.setBit(0).setBit(1).setBit(8).setBit(9);
        return new TerminalCertificateGeometry.Domain(2, 8, square, square);
    }

    @Test
    void completesArtificialSessionWithStrictRawReplayAndNoProvenanceClaim() throws Exception {
        var output = directory.resolve("session");
        var report = TerminalCertificateApplication.run(output, Map.of("fixture", true), domain(),
                new TerminalCertificateSession.Limits(20, 10, 20, 2));
        assertEquals("SESSION_COMPLETE", report.get("status"));
        assertEquals("BOUNDED_MISS", report.get("search_status"));
        assertEquals("COMPLETE", report.get("journal_state"));
        assertEquals(false, report.get("provenance_authenticated"));
        assertEquals(false, report.get("eligible_for_runtime_validation"));
        var checked = TerminalCertificateJournalReader.read(output.resolve("session.jsonl"));
        assertTrue(checked.checkerReplays() > 0);
        assertEquals(checked.enumerations(), report.get("enumerations"));
        var json = JsonMapper.builder().build();
        assertEquals("INCOMPLETE", json.readTree(Files.readString(output.resolve("started.json")))
                .get("status").asString());
        assertEquals("SESSION_COMPLETE", json.readTree(Files.readString(output.resolve("completion.json")))
                .get("status").asString());
        assertFalse(Files.exists(output.resolve("error.json")));
    }

    @Test
    void preservesExclusivePriorOutputAndFailurePrefix() throws Exception {
        var output = directory.resolve("failed");
        assertThrows(NullPointerException.class, () -> TerminalCertificateApplication.run(output,
                Map.of("fixture", true), domain(), null));
        assertTrue(Files.exists(output.resolve("started.json")));
        assertTrue(Files.exists(output.resolve("error.json")));
        assertFalse(Files.exists(output.resolve("completion.json")));
        assertEquals(TerminalCertificateJournalReader.State.ERROR,
                TerminalCertificateJournalReader.read(output.resolve("session.jsonl")).state());
        var before = Files.readAllBytes(output.resolve("error.json"));
        assertThrows(FileAlreadyExistsException.class, () -> TerminalCertificateApplication.run(output,
                Map.of("fixture", false), domain(), TerminalCertificateSession.Limits.registered()));
        assertArrayEquals(before, Files.readAllBytes(output.resolve("error.json")));
    }

    @Test
    void outputMustBeCanonicalAndOutsideCheckout() throws Exception {
        var root = Files.createDirectory(directory.resolve("checkout")).toRealPath();
        TerminalCertificateApplication.validateOutput(root, directory.toRealPath().resolve("fresh"));
        assertThrows(IllegalArgumentException.class, () ->
                TerminalCertificateApplication.validateOutput(root, root.resolve("evidence")));
        assertThrows(IllegalArgumentException.class, () ->
                TerminalCertificateApplication.validateOutput(root, directory.resolve("other/../fresh")));
    }

    @Test
    void refusesImplicitVerifyAndBudgetOverrideCommandsBeforeCreatingOutput() {
        for (var args : new String[][]{ {}, {"--verify-only", "root"},
                {"--collect", "root", "output", "--nodes", "1"}, {"--check", "root", "output"} }) {
            var failure = assertThrows(IllegalArgumentException.class, () -> TerminalCertificateApplication.main(args));
            assertTrue(failure.getMessage().startsWith("usage: --collect"));
        }
        assertFalse(Files.exists(directory.resolve("output")));
    }
}
