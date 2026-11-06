/*
 * Copyright (C) 2024-2026 xuMingHai
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Presents saved journal facts without replaying actions or authenticating a session. Derived
 * geometry is withheld on structural mismatch; passing necessary conditions never certifies Q.
 * There is no main/collect entry point and no automatic historical input or output allowance.
 */
final class FixedWitnessReport {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private FixedWitnessReport() { }

    /**
     * Reads with caller-owned identities, then charges the complete UTF-8 report before exclusive
     * creation. The caller must supply the same permanently reserved allowance as its journal,
     * or a distinct allowance actually reserved from the same parent root. Failed I/O never refunds.
     */
    static void write(Path journal, byte[] input, String hash, List<FixedWitnessInput.PathSpec> paths,
                      Map<String, FixedWitnessReportChecker.Expected> expected, Path output,
                      FixedWitnessReplay.JournalQuota quota) throws IOException {
        var steps = FixedWitnessInput.read(input, hash, paths);
        var summary = FixedWitnessJournalReader.read(journal, input, hash, paths, expected);
        boolean structural = true;
        try {
            if (!summary.rows().isEmpty()) {
                FixedWitnessReportChecker.checkPrefix(summary.rows(), steps, hash);
            }
        } catch (FixedWitnessReportChecker.Mismatch failure) {
            structural = false;
        }
        var observations = new ArrayList<Map<String, Object>>();
        var terminals = new ArrayList<Map<String, Object>>();
        if (structural) {
            for (var row : summary.rows()) observations.add(observation(row));
            for (var path : paths) {
                var terminal = summary.rows().stream().filter(row -> row.pathId().equals(path.id())
                        && row.step() == path.placements()).findFirst();
                if (terminal.isEmpty()) {
                    terminals.add(Map.of("pathId", path.id(), "assessment", "NOT_RECORDED"));
                    continue;
                }
                var row = terminal.orElseThrow();
                var violations = violations(row);
                boolean matches = true;
                try {
                    FixedWitnessReportChecker.checkTerminal(row, expected.get(path.id()));
                } catch (FixedWitnessReportChecker.Mismatch failure) {
                    matches = false;
                }
                terminals.add(Map.of("pathId", path.id(), "assessment", "RECORDED_GEOMETRY",
                        "matchesExpected", matches, "clean", row.progress().cleanCompletion(),
                        "q", violations.isEmpty() ? "NOT_CERTIFIED" : "NECESSARY_CONDITION_FAILED",
                        "violations", violations, "absoluteClearedLines", row.cumulativeClears(),
                        "hasClearedLines", row.cumulativeClears() > 0));
            }
        }
        var report = Map.ofEntries(
                Map.entry("schema", "fixed-witness-report-v1"), Map.entry("inputHash", hash),
                Map.entry("journalState", summary.state().name()),
                Map.entry("attempted", summary.attempted()), Map.entry("completed", summary.rows().size()),
                Map.entry("structuralPrefixValid", structural),
                Map.entry("recordedRows", summary.rows().stream().map(FixedWitnessReportCodec::row).toList()),
                Map.entry("observations", observations), Map.entry("terminals", terminals),
                Map.entry("provenanceAuthenticated", false), Map.entry("qCertified", false),
                Map.entry("eligibleForRuntimeValidation", false));
        byte[] bytes = (JSON.writeValueAsString(report) + "\n").getBytes(StandardCharsets.UTF_8);
        quota.charge(bytes.length);
        try (var channel = FileChannel.open(output, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            var buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
    }

    private static Map<String, Object> observation(FixedWitnessReplay.Row row) {
        return Map.of("pathId", row.pathId(), "step", row.step(),
                "missingRequired", row.progress().requiredCells() - row.progress().matchedRequiredCells(),
                "forbiddenOccupied", row.progress().forbiddenOccupiedCells(),
                "nonForbiddenHoles", row.holes().totalHoles() - row.holes().forbiddenHoles(),
                "heightAboveTarget", Math.max(0, row.maxHeight() - ShapeTarget.HEART.height()),
                "absoluteClearedLines", row.cumulativeClears(), "clearedLines", row.clearedLines(),
                "violations", violations(row));
    }

    private static List<String> violations(FixedWitnessReplay.Row row) {
        var result = new ArrayList<String>();
        if (row.progress().matchedRequiredCells() < row.progress().requiredCells()) result.add("MISSING_REQUIRED");
        if (row.progress().forbiddenOccupiedCells() > 0) result.add("FORBIDDEN_OCCUPIED");
        if (row.holes().totalHoles() > row.holes().forbiddenHoles()) result.add("NON_FORBIDDEN_HOLES");
        if (row.maxHeight() > ShapeTarget.HEART.height()) result.add("HEIGHT_ABOVE_TARGET");
        return List.copyOf(result);
    }
}
