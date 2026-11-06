/*
 * Copyright (C) 2024-2026 xuMingHai
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Owns caller-declared report evidence and its canonical identity, without executing actions.
 * Identity binds bytes and declarations only; it authenticates neither caller nor historical origin.
 * No filesystem input, default allowance, JVM command or session permission is provided here.
 */
final class FixedWitnessReportRequest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String SCHEMA = "fixed-witness-report-request-v1";
    private final byte[] input;
    private final byte[] journal;
    private final String inputHash;
    private final List<FixedWitnessInput.PathSpec> paths;
    private final Map<String, FixedWitnessReportChecker.Expected> expected;
    private final Map<String, Object> declaration;
    private final String requestHash;

    FixedWitnessReportRequest(byte[] input, String inputHash, List<FixedWitnessInput.PathSpec> paths,
                              Map<String, FixedWitnessReportChecker.Expected> expected,
                              byte[] journal, String journalHash) {
        // Bound before copying; after copying all checks and consumption use owned snapshots.
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(journal, "journal");
        if (input.length > FixedWitnessInput.MAX_BYTES || journal.length > 10 * 1024 * 1024) {
            throw new IllegalArgumentException("report request size limit");
        }
        this.input = input.clone();
        this.journal = journal.clone();
        this.paths = List.copyOf(paths);
        this.expected = Map.copyOf(expected);
        this.inputHash = Objects.requireNonNull(inputHash, "inputHash");
        FixedWitnessInput.read(this.input, inputHash, this.paths);
        if (journalHash == null || !journalHash.matches("[0-9a-f]{64}")
                || !FixedWitnessInput.sha256(this.journal).equals(journalHash)) {
            throw new IllegalArgumentException("journal SHA-256 mismatch");
        }
        if (!this.expected.keySet().equals(this.paths.stream().map(FixedWitnessInput.PathSpec::id)
                .collect(java.util.stream.Collectors.toSet()))) {
            throw new IllegalArgumentException("expected keys must equal path IDs");
        }
        var canonical = new LinkedHashMap<String, Object>();
        canonical.put("schema", SCHEMA);
        canonical.put("inputSha256", inputHash);
        canonical.put("journalSha256", journalHash);
        canonical.put("paths", this.paths.stream().map(path -> {
            var value = new LinkedHashMap<String, Object>();
            value.put("id", path.id());
            value.put("placements", path.placements());
            return Collections.unmodifiableMap(value);
        }).toList());
        var expectations = new LinkedHashMap<String, Object>();
        for (var path : this.paths) {
            var value = this.expected.get(path.id());
            var fields = new LinkedHashMap<String, Object>();
            fields.put("required", value.required());
            fields.put("forbidden", value.forbidden());
            fields.put("rawHoles", value.rawHoles());
            fields.put("requiredHoles", value.requiredHoles());
            fields.put("forbiddenHoles", value.forbiddenHoles());
            fields.put("supportHoles", value.supportHoles());
            fields.put("outsideHoles", value.outsideHoles());
            expectations.put(path.id(), Collections.unmodifiableMap(fields));
        }
        canonical.put("expected", Collections.unmodifiableMap(expectations));
        declaration = Collections.unmodifiableMap(canonical);
        requestHash = FixedWitnessInput.sha256(canonicalBytes());
    }

    /** Returns a fresh canonical UTF-8 declaration including its single terminal LF. */
    byte[] canonicalBytes() {
        return (JSON.writeValueAsString(declaration) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    String requestHash() { return requestHash; }

    /**
     * Charges the complete report before CREATE_NEW; failed opens/writes never refund.
     * The caller must supply the journal allowance or a child permanently reserved from its root.
     * This component cannot establish ancestry of independently supplied allowance instances.
     */
    void write(Path output, FixedWitnessReplay.JournalQuota quota) throws IOException {
        Objects.requireNonNull(quota, "quota");
        var identity = new LinkedHashMap<String, Object>(declaration);
        identity.put("requestSha256", requestHash);
        FixedWitnessReport.write(journal, input, inputHash, paths, expected, output, quota, identity);
    }
}
