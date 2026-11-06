/*
 * Copyright (C) 2024-2026 xuMingHai
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Bounded two-file input boundary for the fixed-action request, with no action execution.
 * The caller must own the directory and exclude every writer, including hard-link aliases.
 * Stable metadata detects ordinary drift; it does not establish isolation or provenance.
 */
final class FixedWitnessReplayDescriptor {
    private static final Map<String, Integer> LIMITS = Map.of(
            "request.json", 16 * 1024, "input.csv", FixedWitnessInput.MAX_BYTES);

    private FixedWitnessReplayDescriptor() { }

    /** Metadata only; safe before ACK. No request or input content is consumed here. */
    static Snapshot inspect(Path directory) throws IOException {
        Objects.requireNonNull(directory, "directory");
        require(directory.isAbsolute() && directory.normalize().equals(directory),
                "absolute normalized directory required");
        return new Snapshot(directory, metadata(directory));
    }

    /**
     * Retains the initial identities for ACK-time and exit-time comparisons. Reading is a
     * separate operation: the eventual command must authorize it only after parent ACK.
     */
    static final class Snapshot {
        private final Path directory;
        private final Map<String, Identity> identities;

        private Snapshot(Path directory, Map<String, Identity> identities) {
            this.directory = directory;
            this.identities = Map.copyOf(identities);
        }

        void verify() throws IOException {
            require(identities.equals(metadata(directory)), "request snapshot drift");
        }

        /** The expected digest must be independently retained by the parent. */
        FixedWitnessReplayRequest read(String expectedRequestSha256) throws IOException {
            verify();
            byte[] declaration = readFile("request.json");
            byte[] input = readFile("input.csv");
            verify();
            return FixedWitnessReplayRequest.read(declaration, input, expectedRequestSha256);
        }

        private byte[] readFile(String name) throws IOException {
            Path file = directory.resolve(name);
            Identity expected = identities.get(name);
            require(expected.equals(identity(file, false)), "request file drift");
            byte[] raw;
            try (var stream = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                require(expected.equals(identity(file, false)), "request file drift");
                raw = stream.readNBytes(LIMITS.get(name) + 1);
                require(raw.length <= LIMITS.get(name) && raw.length == expected.size(),
                        "request file size drift/limit");
                require(expected.equals(identity(file, false)), "request file drift");
            }
            return raw;
        }
    }

    private static Map<String, Identity> metadata(Path directory) throws IOException {
        for (Path ancestor = directory; ancestor != null; ancestor = ancestor.getParent()) {
            var attributes = Files.readAttributes(ancestor, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            require(attributes.isDirectory() && !attributes.isSymbolicLink(), "directory ancestor type/link");
        }
        require(directory.toRealPath().equals(directory), "canonical directory required");
        var root = identity(directory, true);
        exactFiles(directory);
        var result = new LinkedHashMap<String, Identity>();
        result.put("", root);
        for (var entry : LIMITS.entrySet()) {
            var value = identity(directory.resolve(entry.getKey()), false);
            require(value.size() <= entry.getValue(), "request file size limit");
            result.put(entry.getKey(), value);
        }
        exactFiles(directory);
        require(root.equals(identity(directory, true)), "request directory drift");
        return Map.copyOf(result);
    }

    private static void exactFiles(Path directory) throws IOException {
        try (var files = Files.list(directory)) {
            Set<String> names = files.map(path -> path.getFileName().toString()).collect(Collectors.toSet());
            require(names.equals(LIMITS.keySet()), "exact request file set required");
        }
    }

    private static Identity identity(Path path, boolean directory) throws IOException {
        var attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        require(!attributes.isSymbolicLink() && attributes.fileKey() != null
                && (directory ? attributes.isDirectory() : attributes.isRegularFile()),
                "file type/identity unavailable");
        return new Identity(attributes.fileKey(), attributes.size(), attributes.lastModifiedTime());
    }

    private record Identity(Object fileKey, long size, FileTime modified) { }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
