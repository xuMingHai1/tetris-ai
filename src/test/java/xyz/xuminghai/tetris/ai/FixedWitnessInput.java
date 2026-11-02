/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import xyz.xuminghai.tetris.core.TetrominoType;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Reads fixed, already recorded actions without creating pieces or executing any game logic.
 *
 * <p>Hash the original bytes before parsing: newline normalization, trimming or accepting a partial
 * path would silently change the registered evidence. Historical input is deliberately not loaded
 * by a test or static initializer. A later independently accepted supervisor must supply it.</p>
 */
final class FixedWitnessInput {
    static final String HEADER = "shape_feasibility_witness,seed,step,piece,actions";
    static final int MAX_BYTES = 64 * 1024;
    static final int MAX_PLACEMENTS = 22;

    private FixedWitnessInput() {
    }

    /**
     * Validates complete paths in the specified order. Path IDs are opaque historical labels;
     * they are never passed to a generator. Synthetic tests use different, small path manifests.
     */
    static List<Step> read(byte[] bytes, String expectedSha256, List<PathSpec> paths) {
        Objects.requireNonNull(bytes, "bytes");
        Objects.requireNonNull(expectedSha256, "expectedSha256");
        paths = List.copyOf(paths);
        if (paths.isEmpty() || paths.size() > 2
                || paths.stream().map(PathSpec::id).distinct().count() != paths.size()
                || paths.stream().mapToInt(PathSpec::placements).sum() > MAX_PLACEMENTS) {
            throw new IllegalArgumentException("invalid fixed path manifest");
        }
        if (bytes.length == 0 || bytes.length > MAX_BYTES
                || !expectedSha256.matches("[0-9a-f]{64}")
                || !sha256(bytes).equals(expectedSha256)) {
            throw new IllegalArgumentException("input size or SHA-256 mismatch");
        }
        // This CSV vocabulary is ASCII, which is a strict subset of UTF-8. Reject BOM, CR,
        // non-ASCII and controls instead of repairing decoded evidence or accepting replacement.
        for (byte value : bytes) {
            if (value != '\n' && (value < 32 || value > 126)) {
                throw new IllegalArgumentException("input must be UTF-8 ASCII vocabulary with LF only");
            }
        }
        String text = new String(bytes, StandardCharsets.UTF_8);
        String[] lines = text.split("\n", -1);
        int expectedRows = paths.stream().mapToInt(PathSpec::placements).sum();
        if (lines.length != expectedRows + 2 || !lines[0].equals(HEADER)
                || !lines[lines.length - 1].isEmpty()) {
            throw new IllegalArgumentException("missing header, row, or final LF; extra data is forbidden");
        }
        List<Step> result = new ArrayList<>(expectedRows);
        int line = 1;
        for (PathSpec path : paths) {
            for (int step = 1; step <= path.placements(); step++, line++) {
                String[] fields = lines[line].split(",", -1);
                if (fields.length != 5 || !fields[0].equals("shape_feasibility_witness")
                        || !fields[1].equals(path.id()) || !fields[2].equals(Integer.toString(step))) {
                    throw new IllegalArgumentException("unexpected path/step/schema at line " + (line + 1));
                }
                TetrominoType piece = TetrominoType.valueOf(fields[3]);
                String[] tokens = fields[4].split(">", -1);
                if (tokens.length == 0 || tokens.length > 64) {
                    throw new IllegalArgumentException("action count outside 1..64 at line " + (line + 1));
                }
                List<AiAction> actions = new ArrayList<>(tokens.length);
                for (String token : tokens) {
                    actions.add(AiAction.valueOf(token));
                }
                AiPlan plan = AiPlanValidator.requireValid(new AiPlan(actions));
                if (actions.getLast() != AiAction.HARD_DROP) {
                    throw new IllegalArgumentException("fixed step must end with HARD_DROP");
                }
                result.add(new Step(path.id(), step, line + 1, piece, plan));
            }
        }
        return List.copyOf(result);
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK must provide SHA-256", exception);
        }
    }

    record PathSpec(String id, int placements) {
        PathSpec {
            if (id == null || !id.matches("[0-9]{1,20}") || placements < 1
                    || placements > MAX_PLACEMENTS) {
                throw new IllegalArgumentException("invalid fixed path specification");
            }
        }
    }

    record Step(String pathId, int step, int sourceLine, TetrominoType piece, AiPlan plan) {
    }
}
