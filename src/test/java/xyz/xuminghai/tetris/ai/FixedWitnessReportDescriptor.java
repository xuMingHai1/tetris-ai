/*
 * Copyright (C) 2024-2026 xuMingHai
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import tools.jackson.core.json.JsonFactory;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Set;

/**
 * Consumes a canonical declaration against an independently retained parent digest.
 * File input requires a caller-owned private directory with no concurrent writers. Metadata
 * checks detect ordinary drift; they do not establish an OS sandbox or defeat malicious swaps.
 * No output, allowance, command, action execution or historical-origin certification is added.
 */
final class FixedWitnessReportDescriptor {
    private static final int DESCRIPTOR_LIMIT = 16 * 1024;
    private static final int JOURNAL_LIMIT = 10 * 1024 * 1024;
    private static final Set<String> TOP = Set.of("schema", "inputSha256", "journalSha256", "paths", "expected");
    private static final Set<String> EXPECTED = Set.of("required", "forbidden", "rawHoles",
            "requiredHoles", "forbiddenHoles", "supportHoles", "outsideHoles");
    private static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder()
                    .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(8).build())
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    private FixedWitnessReportDescriptor() { }

    /** Copies before validation; the expected digest must not come from the consumed directory. */
    static FixedWitnessReportRequest read(byte[] descriptor, byte[] input, byte[] journal,
                                          String expectedRequestSha256) {
        require(expectedRequestSha256 != null && expectedRequestSha256.matches("[0-9a-f]{64}"),
                "independent request digest required");
        byte[] declaration = snapshot(descriptor, DESCRIPTOR_LIMIT);
        byte[] inputBytes = snapshot(input, FixedWitnessInput.MAX_BYTES);
        byte[] journalBytes = snapshot(journal, JOURNAL_LIMIT);
        require(FixedWitnessInput.sha256(declaration).equals(expectedRequestSha256), "request digest mismatch");
        JsonNode root;
        try {
            // REPORT decoding rejects invalid UTF-8 instead of silently replacing evidence bytes.
            String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(declaration)).toString();
            require(!text.startsWith("\uFEFF"), "descriptor BOM");
            root = JSON.readTree(text);
        } catch (CharacterCodingException | tools.jackson.core.JacksonException failure) {
            throw new IllegalArgumentException("invalid descriptor JSON/UTF-8", failure);
        }
        keys(root, TOP);
        require("fixed-witness-report-request-v1".equals(string(root, "schema")), "descriptor schema");
        JsonNode pathNodes = root.get("paths");
        require(pathNodes.isArray() && pathNodes.size() >= 1 && pathNodes.size() <= 2, "path count/type");
        var paths = new ArrayList<FixedWitnessInput.PathSpec>();
        for (JsonNode path : pathNodes) {
            keys(path, Set.of("id", "placements"));
            paths.add(new FixedWitnessInput.PathSpec(string(path, "id"), integer(path, "placements")));
        }
        var pathIds = new HashSet<String>();
        paths.forEach(path -> pathIds.add(path.id()));
        JsonNode expectations = root.get("expected");
        keys(expectations, pathIds);
        var expected = new LinkedHashMap<String, FixedWitnessReportChecker.Expected>();
        for (var path : paths) {
            JsonNode value = expectations.get(path.id());
            keys(value, EXPECTED);
            expected.put(path.id(), new FixedWitnessReportChecker.Expected(integer(value, "required"),
                    integer(value, "forbidden"), integer(value, "rawHoles"), integer(value, "requiredHoles"),
                    integer(value, "forbiddenHoles"), integer(value, "supportHoles"), integer(value, "outsideHoles")));
        }
        var request = new FixedWitnessReportRequest(inputBytes, string(root, "inputSha256"), paths,
                expected, journalBytes, string(root, "journalSha256"));
        // Existing Request is the only canonical serializer: ordering, numbers, escapes and LF
        // must match its exact bytes, even when a different spelling was valid JSON.
        require(Arrays.equals(declaration, request.canonicalBytes()), "noncanonical descriptor");
        return request;
    }

    /**
     * Reads fixed direct children once with bounded NOFOLLOW opens, retaining only owned bytes.
     * Caller must exclude all writers (including hard-link aliases) throughout this operation.
     * Later file changes cannot change the returned request; exit-time checks belong to the parent.
     */
    static FixedWitnessReportRequest read(Path directory, String expectedRequestSha256) throws IOException {
        Objects.requireNonNull(directory, "directory");
        require(directory.isAbsolute() && directory.normalize().equals(directory), "absolute normalized directory required");
        checkAncestors(directory);
        BasicFileAttributes before = attributes(directory, true);
        byte[] declaration = readFile(directory.resolve("request.json"), DESCRIPTOR_LIMIT);
        byte[] input = readFile(directory.resolve("input.csv"), FixedWitnessInput.MAX_BYTES);
        byte[] journal = readFile(directory.resolve("journal.jsonl"), JOURNAL_LIMIT);
        checkAncestors(directory);
        stable(before, attributes(directory, true));
        return read(declaration, input, journal, expectedRequestSha256);
    }

    private static byte[] readFile(Path file, int limit) throws IOException {
        BasicFileAttributes before = attributes(file, false);
        require(before.size() <= limit, "file size limit");
        byte[] bytes;
        try (var stream = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            stable(before, attributes(file, false));
            bytes = stream.readNBytes(limit + 1);
            require(bytes.length <= limit, "file size limit");
            stable(before, attributes(file, false));
        }
        require(bytes.length == before.size(), "read size drift");
        return bytes;
    }

    private static BasicFileAttributes attributes(Path path, boolean directory) throws IOException {
        var value = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        require(!value.isSymbolicLink() && (directory ? value.isDirectory() : value.isRegularFile())
                && value.fileKey() != null, "file type/identity unavailable");
        return value;
    }

    private static void checkAncestors(Path directory) throws IOException {
        for (Path ancestor = directory; ancestor != null; ancestor = ancestor.getParent()) {
            // Only the owned directory and leaves need stable file keys. Filesystem roots can
            // expose no key (notably Windows volumes); ancestors are checked for links/type.
            var value = Files.readAttributes(ancestor, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            require(value.isDirectory() && !value.isSymbolicLink(), "directory ancestor type/link");
        }
        require(directory.toRealPath().equals(directory), "noncanonical directory");
    }

    private static void stable(BasicFileAttributes before, BasicFileAttributes after) {
        require(before.fileKey().equals(after.fileKey()) && before.size() == after.size()
                && before.lastModifiedTime().equals(after.lastModifiedTime()), "file metadata drift");
    }

    private static byte[] snapshot(byte[] bytes, int limit) {
        require(bytes != null && bytes.length <= limit, "snapshot size/null");
        return bytes.clone();
    }

    private static void keys(JsonNode value, Set<String> expected) {
        require(value != null && value.isObject(), "object required");
        var actual = new HashSet<String>();
        value.propertyNames().forEach(actual::add);
        require(actual.equals(expected), "exact descriptor keys required");
    }

    private static String string(JsonNode object, String key) {
        JsonNode value = object.get(key);
        require(value != null && value.isString(), "string required: " + key);
        return value.asString();
    }

    private static int integer(JsonNode object, String key) {
        JsonNode value = object.get(key);
        require(value != null && value.isIntegralNumber() && value.canConvertToInt(), "int required: " + key);
        int number = value.intValue();
        require(number >= 0, "negative integer: " + key);
        return number;
    }

    private static void require(boolean condition, String reason) {
        if (!condition) throw new IllegalArgumentException(reason);
    }
}
