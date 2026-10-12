/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai.benchmark;

import xyz.xuminghai.tetris.ai.ShapeProgress;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Evaluation-only first-clean and continuation facts from validated post-row-clear placements. */
final class ShapeCompletionTelemetry {
    static final String HEADER = "build_shape_outcome,strategy,target,seed,decisions,first_clean_piece,"
            + "first_clean_elapsed_ms,post_clean_pieces,clean_turns,max_clean_streak,clean_at_end,elapsed_game_ms";

    private int decisions;
    private int firstCleanPiece = -1;
    private long firstCleanElapsedNanos = -1;
    private long elapsedGameNanos;
    private int cleanTurns;
    private int cleanStreak;
    private int maxCleanStreak;
    private boolean cleanAtEnd;
    private final List<Long> decisionNanos = new ArrayList<>();

    void record(int decision, ShapeProgress progress, long fullDecisionNanos, long elapsedNanos) {
        if (decision != decisions + 1 || fullDecisionNanos < 0 || elapsedNanos < elapsedGameNanos) {
            throw new IllegalArgumentException("shape observations must be sequential with nonnegative monotonic costs");
        }
        decisions = decision;
        decisionNanos.add(fullDecisionNanos);
        elapsedGameNanos = elapsedNanos;
        cleanAtEnd = progress.cleanCompletion();
        if (cleanAtEnd) {
            if (firstCleanPiece == -1) {
                firstCleanPiece = decision;
                firstCleanElapsedNanos = elapsedNanos;
            }
            cleanTurns++;
            cleanStreak++;
            maxCleanStreak = Math.max(maxCleanStreak, cleanStreak);
        }
        else {
            cleanStreak = 0;
        }
    }

    String csv(String strategy, String target, long seed) {
        return String.format(Locale.ROOT, "build_shape_outcome,%s,%s,%d,%d,%d,%.6f,%d,%d,%d,%s,%.6f",
                strategy, target, seed, decisions, firstCleanPiece,
                firstCleanPiece == -1 ? -1.0 : firstCleanElapsedNanos / 1_000_000.0,
                firstCleanPiece == -1 ? 0 : decisions - firstCleanPiece,
                cleanTurns, maxCleanStreak, cleanAtEnd, elapsedGameNanos / 1_000_000.0);
    }

    List<Long> decisionNanos() {
        return List.copyOf(decisionNanos);
    }
}
