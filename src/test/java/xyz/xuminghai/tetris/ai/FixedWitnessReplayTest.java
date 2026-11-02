/*
 * Copyright (C) 2024-2026 xuMingHai
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import xyz.xuminghai.tetris.core.TetrominoType;

import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Small artificial paths only. Never opens the historical input or starts a historical session. */
class FixedWitnessReplayTest {
    @TempDir Path directory;
    private static final List<FixedWitnessInput.PathSpec> PATHS = List.of(
            new FixedWitnessInput.PathSpec("7", 2), new FixedWitnessInput.PathSpec("9", 1));
    private static final String CSV = FixedWitnessInput.HEADER + "\n"
            + "shape_feasibility_witness,7,1,O,HARD_DROP\n"
            + "shape_feasibility_witness,7,2,O,HARD_DROP\n"
            + "shape_feasibility_witness,9,1,O,HARD_DROP\n";
    private static final Map<String, FixedWitnessReportChecker.Expected> EXPECTED = Map.of(
            "7", expected(4), "9", expected(0));

    @Test
    void recordsProductionFactsResetsPathsAndReadsJournalWithoutReplay() throws Exception {
        var output = directory.resolve("journal.jsonl");
        var result = run(CSV, EXPECTED, output);
        assertEquals(FixedWitnessReplay.Status.COMPLETE, result.status());
        assertEquals(3, result.attempted());
        assertEquals(4, result.rows().get(0).after().occupied());
        assertEquals(result.rows().get(0).after(), result.rows().get(1).before());
        assertEquals(8, result.rows().get(1).after().occupied());
        assertEquals(result.rows().get(0).before(), result.rows().get(2).before());
        assertEquals(0, result.rows().getLast().progress().matchedRequiredCells());
        assertEquals(2, result.rows().getLast().maxHeight());
        assertEquals(18, result.rows().getLast().headroom());
        assertEquals(FixedWitnessJournalReader.State.COMPLETE, read(output).state());
        assertEquals(result.rows(), read(output).rows());
        assertThrows(UnsupportedOperationException.class, () -> result.rows().clear());
    }

    @Test
    void usesFirstGravityBeforeOriginalActionsAndRejectsSpawnOverlap() {
        boolean[][] board = new boolean[20][10];
        board[0][7] = true;
        // An I spawns at row -1 then gravity moves it to row 0. RIGHT immediately collides.
        var right = step(TetrominoType.I, AiAction.RIGHT, AiAction.HARD_DROP);
        assertThrows(IllegalStateException.class,
                () -> FixedWitnessReplay.replayOne(board, right, hash(CSV), 0));
        boolean[][] obstacle = new boolean[20][10];
        obstacle[0][3] = true;
        // Even though RIGHT could escape this overlap, the initial spawn itself is illegal.
        assertThrows(IllegalStateException.class,
                () -> FixedWitnessReplay.replayOne(obstacle, right, hash(CSV), 0));
    }

    @Test
    void rejectsIntermediateCollisionAndPreservesFailurePrefix() throws Exception {
        String invalid = CSV.replace("7,2,O,HARD_DROP", "7,2,O," + "LEFT>".repeat(6) + "HARD_DROP");
        var output = directory.resolve("invalid.jsonl");
        var result = run(invalid, EXPECTED, output);
        assertEquals(FixedWitnessReplay.Status.INVALID, result.status());
        assertEquals(2, result.attempted());
        assertEquals(1, result.rows().size());
        assertTrue(result.reason().contains("illegal"));
        var checked = FixedWitnessJournalReader.read(output, bytes(invalid), hash(invalid), PATHS, EXPECTED);
        assertEquals(FixedWitnessJournalReader.State.INVALID, checked.state());
        assertEquals(1, checked.rows().size());
    }

    @Test
    void reportsAbsoluteProductionClearAndOccupancyWithoutMutatingInput() {
        boolean[][] board = new boolean[20][10];
        Arrays.fill(board[19], true);
        for (int col = 3; col <= 6; col++) board[19][col] = false;
        var row = FixedWitnessReplay.replayOne(board,
                step(TetrominoType.I, AiAction.HARD_DROP), hash(CSV), 2);
        assertEquals(6, row.before().occupied());
        assertEquals(0, row.after().occupied());
        assertEquals(1, row.clearedLines());
        assertEquals(3, row.cumulativeClears());
        assertEquals(0, row.maxHeight());
        assertTrue(board[19][0]);
        assertFalse(board[19][3]);
    }

    @Test
    void reportsProductionHoleRolesAndShapeProgressOnArtificialBoard() {
        boolean[][] board = new boolean[20][10];
        board[18][4] = true;
        var row = FixedWitnessReplay.replayOne(board,
                step(TetrominoType.I, AiAction.HARD_DROP), hash(CSV), 0);
        assertEquals(7, row.holes().totalHoles());
        assertEquals(7, row.holes().supportAllowedHoles());
        assertEquals(0, row.holes().requiredHoles());
        assertEquals(0, row.holes().forbiddenHoles());
        assertEquals(0, row.holes().outsideTargetHoles());
        assertEquals(2, row.progress().matchedRequiredCells());
        assertEquals(2, row.progress().forbiddenOccupiedCells());
        assertEquals(3, row.maxHeight());
    }

    @Test
    void terminalMismatchStopsBeforeNextPathAndIsRecheckedFromJournal() throws Exception {
        var expectations = Map.of("7", expected(0), "9", expected(0));
        var output = directory.resolve("mismatch.jsonl");
        var result = run(CSV, expectations, output);
        assertEquals(FixedWitnessReplay.Status.EVIDENCE_MISMATCH, result.status());
        assertEquals(2, result.attempted());
        assertEquals(2, result.rows().size());
        assertEquals(FixedWitnessJournalReader.State.EVIDENCE_MISMATCH,
                FixedWitnessJournalReader.read(output, bytes(CSV), hash(CSV), PATHS, expectations).state());
    }

    @Test
    void rejectsWrongInputAndMissingExpectationsBeforeAnyReplayAndPreservesOldJournal() throws Exception {
        var output = directory.resolve("bad-input.jsonl");
        var result = FixedWitnessReplay.run(bytes(CSV), "0".repeat(64), PATHS, EXPECTED, output);
        assertEquals(FixedWitnessReplay.Status.INVALID, result.status());
        assertEquals(0, result.attempted());
        byte[] original = Files.readAllBytes(output);
        assertThrows(FileAlreadyExistsException.class, () -> run(CSV, EXPECTED, output));
        assertArrayEquals(original, Files.readAllBytes(output));
        var missing = run(CSV, Map.of(), directory.resolve("no-expectations.jsonl"));
        assertEquals(FixedWitnessReplay.Status.INVALID, missing.status());
        assertEquals(0, missing.attempted());
    }

    @Test
    void partialOrTornJournalCannotBecomeComplete() throws Exception {
        var output = directory.resolve("complete.jsonl");
        run(CSV, EXPECTED, output);
        String journal = Files.readString(output);
        // Key order is not a wire contract: locate END via parsed event, preserving original lines.
        var json = JsonMapper.builder().build();
        String prefix = "";
        for (String line : journal.split("\n")) {
            if (json.readTree(line).get("event").asString().equals("END")) break;
            prefix += line + "\n";
        }
        for (String partial : List.of("", prefix, journal.substring(0, journal.length() - 1))) {
            Files.writeString(directory.resolve("partial.jsonl"), partial);
            assertEquals(FixedWitnessJournalReader.State.INCOMPLETE,
                    read(directory.resolve("partial.jsonl")).state());
        }
    }

    @Test
    void rejectsFalseCompletionTamperedHashMissingFieldsAndTrailingData() throws Exception {
        var output = directory.resolve("complete.jsonl");
        run(CSV, EXPECTED, output);
        String journal = Files.readString(output);
        for (String bad : List.of(journal + "x", journal + "{}\n",
                journal.replaceFirst("\"schema\":1", "\"schema\":1,\"schema\":1"),
                journal.replaceFirst("\"occupied\":4", "\"occupied\":5"),
                journal.replaceFirst("\"sha256\":\"[a-f0-9]{64}\"", "\"sha256\":\"" + "0".repeat(64) + "\""),
                journal.replaceFirst("\"clearedLines\":0", "\"missingClears\":0"),
                journal.replaceFirst("\"cumulativeClears\":0", "\"cumulativeClears\":1"))) {
            assertNotEquals(journal, bad);
            Files.writeString(directory.resolve("tampered.jsonl"), bad);
            assertThrows(RuntimeException.class, () -> read(directory.resolve("tampered.jsonl")));
        }
        List<String> lines = Files.readAllLines(output);
        Files.writeString(directory.resolve("false.jsonl"), lines.getFirst() + "\n" + lines.getLast() + "\n");
        assertThrows(IllegalArgumentException.class, () -> read(directory.resolve("false.jsonl")));
    }

    private static FixedWitnessInput.Step step(TetrominoType type, AiAction... actions) {
        return new FixedWitnessInput.Step("7", 1, 2, type, new AiPlan(List.of(actions)));
    }

    private static FixedWitnessReportChecker.Expected expected(int required) {
        return new FixedWitnessReportChecker.Expected(required, 0, 0, 0, 0, 0, 0);
    }

    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static String hash(String text) { return FixedWitnessInput.sha256(bytes(text)); }

    private static FixedWitnessReplay.Result run(String csv,
            Map<String, FixedWitnessReportChecker.Expected> expected, Path output) throws Exception {
        return FixedWitnessReplay.run(bytes(csv), hash(csv), PATHS, expected, output);
    }

    private static FixedWitnessJournalReader.Summary read(Path path) throws Exception {
        return FixedWitnessJournalReader.read(path, bytes(CSV), hash(CSV), PATHS, EXPECTED);
    }
}
