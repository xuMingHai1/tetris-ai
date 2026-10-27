/*
 * Copyright (C) 2024-2026 xuMingHai
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigInteger;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Small artificial covers only; no registered HEART search or successful-diagnostic claim. */
class TerminalCertificateRecordsTest {
    @TempDir Path directory;

    private static TerminalCertificateGeometry.Domain domain() {
        var cells = BigInteger.ZERO.setBit(0).setBit(1).setBit(8).setBit(9)
                .setBit(11).setBit(12).setBit(13).setBit(14);
        return new TerminalCertificateGeometry.Domain(2, 8, cells, cells);
    }

    private static List<TerminalCertificateStep> fixture() {
        return new TerminalCertificateOrder().search(domain(), TerminalCertificateGeometry.tiles(domain()))
                .order().orElseThrow();
    }

    private String wire() throws Exception {
        var path = directory.resolve("steps.json");
        TerminalCertificateRecords.write(path, fixture());
        return Files.readString(path);
    }

    @Test
    void fullClaimsRoundTripAndIndependentlyReplayWithExclusiveOutput() throws Exception {
        var steps = fixture();
        var path = directory.resolve("steps.json");
        TerminalCertificateRecords.write(path, steps);
        var original = Files.readString(path);
        var restored = TerminalCertificateRecords.read(path);
        assertEquals(steps, restored);
        var checked = TerminalCertificateChecker.check(domain(), restored);
        assertEquals(2, checked.replays());
        assertEquals(8, checked.finalBoard().bitCount());
        assertFalse(checked.terminal().accepted());
        assertThrows(UnsupportedOperationException.class, restored::clear);
        assertThrows(FileAlreadyExistsException.class, () -> TerminalCertificateRecords.write(path, steps));
        assertEquals(original, Files.readString(path));
    }

    @Test
    void truncatedDuplicateUnknownTrailingAndCoercedRecordsAreRejected() throws Exception {
        var text = wire();
        var invalid = List.of(text.substring(0, text.length() / 2), text + "{}",
                text.replace("\"schema\":", "\"extra\":true,\"schema\":"),
                text.replace("\"step_count\":2", "\"step_count\":1"),
                text.replace("\"step_count\":2", "\"step_count\":\"2\""),
                text.replace("\"step_count\":2", "\"step_count\":2.0"),
                text.replace("\"step_count\":2", "\"step_count\":2,\"step_count\":2"),
                text.replace(TerminalCertificateRecords.SCHEMA, "future-version"),
                text.replace("\"index\":1", "\"index\":0"),
                text.replace("\"cleared_lines\":0", "\"cleared_lines\":1"),
                text.replace("HARD_DROP", "UNKNOWN_ACTION"));
        for (var changed : invalid) {
            assertNotEquals(text, changed);
            assertThrows(RuntimeException.class, () -> TerminalCertificateRecords.parse(changed));
        }
    }

    @Test
    void semanticTamperingMustFailProductionReplayRatherThanTrustTheWire() throws Exception {
        var text = wire();
        var changedBoard = text.replaceFirst("\"post_board\":\"[0-9a-f]+\"", "\"post_board\":\"0\"");
        var restored = TerminalCertificateRecords.parse(changedBoard);
        assertThrows(IllegalArgumentException.class, () -> TerminalCertificateChecker.check(domain(), restored));
        var changedActions = text.replace("HARD_DROP", "LEFT");
        assertThrows(IllegalArgumentException.class, () -> TerminalCertificateChecker.check(domain(),
                TerminalCertificateRecords.parse(changedActions)));
        var noncanonical = text.replaceFirst("\"post_board\":\"[0-9a-f]+\"", "\"post_board\":\"00\"");
        assertThrows(IllegalArgumentException.class, () -> TerminalCertificateRecords.parse(noncanonical));
        var tooWide = text.replaceFirst("\"post_board\":\"[0-9a-f]+\"",
                "\"post_board\":\"1" + "0".repeat(50) + "\"");
        assertThrows(IllegalArgumentException.class, () -> TerminalCertificateRecords.parse(tooWide));
    }

    @Test
    void oversizedFilesAreRejectedAndInvalidClaimsDoNotCreateAnOutput() throws Exception {
        var path = directory.resolve("large.json");
        Files.writeString(path, " ".repeat(TerminalCertificateRecords.MAX_BYTES + 1));
        assertThrows(IllegalArgumentException.class, () -> TerminalCertificateRecords.read(path));
        var output = directory.resolve("empty.json");
        assertThrows(IllegalArgumentException.class, () -> TerminalCertificateRecords.write(output, List.of()));
        assertFalse(Files.exists(output));
    }

    @Test
    void explicitReplayCliRejectsImplicitCollectAndArtificialNonHeartClaims() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> TerminalCertificateReplay.main(new String[0]));
        assertThrows(IllegalArgumentException.class, () -> TerminalCertificateReplay.main(new String[]{"--collect", "unused"}));
        var path = directory.resolve("steps.json");
        TerminalCertificateRecords.write(path, fixture());
        assertThrows(IllegalArgumentException.class, () -> TerminalCertificateReplay.main(new String[]{"--check", path.toString()}));
    }
}
