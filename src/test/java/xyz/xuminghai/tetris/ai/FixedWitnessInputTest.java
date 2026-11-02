/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import org.junit.jupiter.api.Test;
import xyz.xuminghai.tetris.core.TetrominoType;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Artificial CSV only; never opens or replays the historical 22-step fixture. */
class FixedWitnessInputTest {
    private static final List<FixedWitnessInput.PathSpec> PATHS = List.of(
            new FixedWitnessInput.PathSpec("7", 2), new FixedWitnessInput.PathSpec("9", 1));
    private static final String CSV = FixedWitnessInput.HEADER + "\n"
            + "shape_feasibility_witness,7,1,O,RIGHT>HARD_DROP\n"
            + "shape_feasibility_witness,7,2,T,ROTATE_CLOCKWISE>HARD_DROP\n"
            + "shape_feasibility_witness,9,1,I,HARD_DROP\n";

    @Test
    void preservesPathBoundariesSourceLinesAndActionsWithoutExecution() {
        var steps = read(CSV);
        assertEquals(3, steps.size());
        assertEquals("7", steps.getFirst().pathId());
        assertEquals(2, steps.getFirst().sourceLine());
        assertEquals(TetrominoType.O, steps.getFirst().piece());
        assertEquals(List.of(AiAction.RIGHT, AiAction.HARD_DROP), steps.getFirst().plan().actions());
        assertEquals("9", steps.getLast().pathId());
        assertEquals(1, steps.getLast().step());
        assertThrows(UnsupportedOperationException.class, () -> steps.clear());
    }

    @Test
    void rejectsChangedBytesEvenWhenTheyRemainValidCsv() {
        byte[] bytes = bytes(CSV);
        String hash = FixedWitnessInput.sha256(bytes);
        assertThrows(IllegalArgumentException.class, () -> FixedWitnessInput.read(
                bytes(CSV.replace(",O,", ",S,")), hash, PATHS));
        assertThrows(IllegalArgumentException.class, () -> FixedWitnessInput.read(bytes, "0".repeat(64), PATHS));
        assertThrows(IllegalArgumentException.class, () -> FixedWitnessInput.read(bytes, hash.toUpperCase(), PATHS));
    }

    @Test
    void rejectsMissingDuplicatedReorderedAndExtraRows() {
        for (String bad : List.of(
                CSV.replace("shape_feasibility_witness,7,2,T,ROTATE_CLOCKWISE>HARD_DROP\n", ""),
                CSV.replace(",7,2,", ",7,1,"),
                CSV.replace(",7,1,", ",9,1,"),
                CSV + "shape_feasibility_witness,9,2,I,HARD_DROP\n",
                CSV.replace(",7,1,", ",7,01,"))) {
            assertThrows(IllegalArgumentException.class, () -> read(bad));
        }
    }

    @Test
    void rejectsNormalizationTruncationAndWrongSchema() {
        for (String bad : List.of(CSV.replace("\n", "\r\n"), "\ufeff" + CSV,
                CSV.substring(0, CSV.length() - 1), CSV + "\n", CSV.replace("seed,step", "path,step"),
                CSV.replace(",O,", ", O,"), CSV.replace(",O,", ",\uff2f,"))) {
            assertThrows(IllegalArgumentException.class, () -> read(bad));
        }
    }

    @Test
    void rejectsInvalidActionsAndNonTerminalOrEarlyHardDrop() {
        for (String actions : List.of("RIGHT", "HARD_DROP>LEFT>HARD_DROP", "BOGUS>HARD_DROP",
                "RIGHT>>HARD_DROP", "", "LEFT>".repeat(64) + "HARD_DROP")) {
            assertThrows(IllegalArgumentException.class,
                    () -> read(CSV.replace("RIGHT>HARD_DROP", actions)));
        }
    }

    @Test
    void rejectsOversizedInputAndInvalidManifest() {
        byte[] tooLarge = bytes(" ".repeat(FixedWitnessInput.MAX_BYTES + 1));
        assertThrows(IllegalArgumentException.class, () -> FixedWitnessInput.read(
                tooLarge, FixedWitnessInput.sha256(tooLarge), PATHS));
        assertThrows(IllegalArgumentException.class, () -> FixedWitnessInput.read(
                bytes(CSV), FixedWitnessInput.sha256(bytes(CSV)), List.of()));
        var repeated = List.of(new FixedWitnessInput.PathSpec("7", 1), new FixedWitnessInput.PathSpec("7", 1));
        assertThrows(IllegalArgumentException.class, () -> FixedWitnessInput.read(
                bytes(CSV), FixedWitnessInput.sha256(bytes(CSV)), repeated));
        var excessive = List.of(new FixedWitnessInput.PathSpec("7", 22), new FixedWitnessInput.PathSpec("9", 1));
        assertThrows(IllegalArgumentException.class, () -> FixedWitnessInput.read(
                bytes(CSV), FixedWitnessInput.sha256(bytes(CSV)), excessive));
        assertThrows(IllegalArgumentException.class, () -> new FixedWitnessInput.PathSpec("7", 0));
    }

    private static List<FixedWitnessInput.Step> read(String csv) {
        byte[] input = bytes(csv);
        return FixedWitnessInput.read(input, FixedWitnessInput.sha256(input), PATHS);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
