/*
 * Copyright (C) 2024-2026 xuMingHai
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.nio.file.attribute.BasicFileAttributes;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Eleven artificial cases plus the separate Python vector; no actions are executed. */
class FixedWitnessReportDescriptorTest {
    private static final byte[] INPUT = bytes("shape_feasibility_witness,seed,step,piece,actions\n"
            + "shape_feasibility_witness,7,1,O,HARD_DROP\n");
    private static final String INPUT_HASH = "92292cb82f47ae608e16fb73e1d47800a022e599905f7dd41a1c20ab8a61ea3b";
    private static final String EMPTY_HASH = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    private static final String REQUEST_HASH = "80f7d79d0d8ef9d1c7ee659a48a167ba39696694e5d42335cd130f2881e31b3d";
    private static final String DECLARATION = "{\"schema\":\"fixed-witness-report-request-v1\","
            + "\"inputSha256\":\"" + INPUT_HASH + "\",\"journalSha256\":\"" + EMPTY_HASH + "\","
            + "\"paths\":[{\"id\":\"7\",\"placements\":1}],\"expected\":{\"7\":{\"required\":0,"
            + "\"forbidden\":0,\"rawHoles\":0,\"requiredHoles\":0,\"forbiddenHoles\":0,"
            + "\"supportHoles\":0,\"outsideHoles\":0}}}\n";
    @TempDir Path temporary;

    @Test
    void literalVectorAndOwnedCanonicalBytes() {
        byte[] descriptor = bytes(DECLARATION);
        byte[] input = INPUT.clone();
        var request = FixedWitnessReportDescriptor.read(descriptor, input, new byte[0], REQUEST_HASH);
        descriptor[0] = 0;
        input[0] = 0;
        assertEquals(INPUT_HASH, FixedWitnessInput.sha256(INPUT));
        assertEquals(EMPTY_HASH, FixedWitnessInput.sha256(new byte[0]));
        assertEquals(REQUEST_HASH, request.requestHash());
        assertArrayEquals(bytes(DECLARATION), request.canonicalBytes());
    }

    @Test
    void duplicateNestedKeyRejected() {
        rejects(DECLARATION.replace("\"placements\":1", "\"placements\":1,\"placements\":1"));
    }

    @Test
    void unknownKeyRejected() {
        rejects(DECLARATION.replace("\"placements\":1", "\"placements\":1,\"extra\":0"));
    }

    @Test
    void missingKeyRejected() {
        rejects(DECLARATION.replace(",\"outsideHoles\":0", ""));
    }

    @Test
    void nullRejected() { rejects(DECLARATION.replace("\"required\":0", "\"required\":null")); }

    @Test
    void floatRejected() { rejects(DECLARATION.replace("\"required\":0", "\"required\":0.0")); }

    @Test
    void overflowRejected() { rejects(DECLARATION.replace("\"required\":0", "\"required\":2147483648")); }

    @Test
    void independentDigestAndInputDigestAreBothRequired() {
        assertThrows(IllegalArgumentException.class, () -> FixedWitnessReportDescriptor.read(
                bytes(DECLARATION), INPUT, new byte[0], "0".repeat(64)));
        byte[] changed = INPUT.clone();
        changed[0] = 0;
        assertThrows(IllegalArgumentException.class, () -> FixedWitnessReportDescriptor.read(
                bytes(DECLARATION), changed, new byte[0], REQUEST_HASH));
    }

    @Test
    void directorySnapshotRemainsUsableAfterOriginalFilesChange() throws Exception {
        Path directory = fixture();
        var request = FixedWitnessReportDescriptor.read(directory, REQUEST_HASH);
        Files.writeString(directory.resolve("input.csv"), "changed");
        Files.writeString(directory.resolve("journal.jsonl"), "changed");
        Files.writeString(directory.resolve("request.json"), "changed");
        Path report = temporary.resolve("report.json");
        request.write(report, new FixedWitnessReplay.JournalQuota(100_000));
        assertArrayEquals(bytes(DECLARATION), request.canonicalBytes());
        String output = Files.readString(report);
        assertTrue(output.contains("INCOMPLETE"));
        assertTrue(output.contains(REQUEST_HASH));
        assertTrue(output.contains(INPUT_HASH));
    }

    @Test
    void symbolicLinkLeafRejectedWithoutChangingOriginal() throws Exception {
        Path directory = fixture();
        Path target = temporary.resolve("original.csv");
        Files.move(directory.resolve("input.csv"), target);
        try {
            Files.createSymbolicLink(directory.resolve("input.csv"), target);
        } catch (IOException | UnsupportedOperationException | SecurityException unavailable) {
            assumeTrue(false, "symbolic link creation unavailable: " + unavailable);
        }
        assertThrows(IllegalArgumentException.class, () -> FixedWitnessReportDescriptor.read(directory, REQUEST_HASH));
        assertArrayEquals(INPUT, Files.readAllBytes(target));
        assertTrue(Files.isSymbolicLink(directory.resolve("input.csv")));
    }

    @Test
    void oversizedJournalRejectedAndAllThreeOriginalsPreserved() throws Exception {
        Path directory = fixture();
        byte[] oversized = new byte[10 * 1024 * 1024 + 1];
        Files.write(directory.resolve("journal.jsonl"), oversized);
        assertThrows(IllegalArgumentException.class, () -> FixedWitnessReportDescriptor.read(directory, REQUEST_HASH));
        assertArrayEquals(bytes(DECLARATION), Files.readAllBytes(directory.resolve("request.json")));
        assertArrayEquals(INPUT, Files.readAllBytes(directory.resolve("input.csv")));
        assertArrayEquals(oversized, Files.readAllBytes(directory.resolve("journal.jsonl")));
        try (var files = Files.list(directory)) { assertEquals(3, files.count()); }
    }

    private Path fixture() throws IOException {
        Path directory = Files.createDirectory(temporary.resolve("private")).toRealPath();
        Files.write(directory.resolve("request.json"), bytes(DECLARATION));
        Files.write(directory.resolve("input.csv"), INPUT);
        Files.write(directory.resolve("journal.jsonl"), new byte[0]);
        assumeTrue(Files.readAttributes(directory, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS).fileKey() != null,
                "filesystem/JDK does not expose required stable directory identity");
        return directory;
    }

    private static void rejects(String declaration) {
        byte[] raw = bytes(declaration);
        assertThrows(IllegalArgumentException.class, () -> FixedWitnessReportDescriptor.read(
                raw, INPUT, new byte[0], FixedWitnessInput.sha256(raw)));
    }

    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
}
