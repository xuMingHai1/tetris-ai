/*
 * Copyright (C) 2024-2026 xuMingHai
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** One artificial case: item four of the accepted sixteen-case execution design budget. */
class FixedWitnessReplayDescriptorTest {
    @TempDir Path temporary;

    @Test
    void ownedRequestSurvivesParentFileDriftButReceiptRejectsIt() throws Exception {
        byte[] input = ("shape_feasibility_witness,seed,step,piece,actions\n"
                + "shape_feasibility_witness,71,1,O,HARD_DROP\n").getBytes(StandardCharsets.UTF_8);
        var request = new FixedWitnessReplayRequest(input, FixedWitnessInput.sha256(input),
                List.of(new FixedWitnessInput.PathSpec("71", 1)),
                Map.of("71", new FixedWitnessReportChecker.Expected(0, 0, 0, 0, 0, 0, 0)));
        Path directory = Files.createDirectory(temporary.resolve("private")).toRealPath();
        Files.write(directory.resolve("request.json"), request.canonicalBytes());
        Files.write(directory.resolve("input.csv"), input);
        assumeTrue(Files.readAttributes(directory, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS).fileKey() != null,
                "filesystem/JDK does not expose required stable directory identity");
        var receipt = FixedWitnessReplayDescriptor.inspect(directory);
        var owned = receipt.read(request.requestHash());
        receipt.verify();
        Files.writeString(directory.resolve("input.csv"), "changed parent bytes");
        assertArrayEquals(input, owned.inputBytes());
        assertArrayEquals(request.canonicalBytes(), owned.canonicalBytes());
        assertThrows(IllegalArgumentException.class, receipt::verify);
        assertEquals("changed parent bytes", Files.readString(directory.resolve("input.csv")));
        try (var files = Files.list(directory)) { assertEquals(2, files.count()); }
    }
}
