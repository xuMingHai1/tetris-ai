/*
 * Copyright (C) 2024-2026 xuMingHai
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai.benchmark;

import xyz.xuminghai.tetris.ai.GoalWindowCandidate;
import xyz.xuminghai.tetris.ai.ShapeProgress;
import xyz.xuminghai.tetris.ai.ShapeTarget;
import xyz.xuminghai.tetris.core.TetrominoType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Test-classpath adapter: only validated, actual post-clear boards count as completion. */
final class GoalWindowGameEvidence {
    private final ShapeCompletionTelemetry completion = new ShapeCompletionTelemetry();
    private final List<Turn> turns = new ArrayList<>();
    private final List<Prediction> predictions = new ArrayList<>();
    private Pending pending;

    Update record(HeadlessGameRunner.TurnObservation turn,
                GoalWindowCandidate.Observation choice, long elapsedGameNanos) {
        var board = turn.selected().resultingBoard();
        var actual = ShapeTarget.HEART.progress(board);
        var actualQ = GoalWindowCandidate.terminal(board);
        var nextPending = choice != null && choice.choice() == GoalWindowCandidate.Choice.PREVIEW_SETUP
                ? new Pending(turn.decision(), board, turn.snapshot().nextType().orElseThrow()) : null;
        // Reuse frozen completion/streak/timing semantics, including sequential validation.
        completion.record(turn.decision(), actual, turn.decisionNanos(), elapsedGameNanos);
        var resolved = new ArrayList<Prediction>();
        if (pending != null) {
            boolean same = Arrays.deepEquals(pending.board(), turn.snapshot().occupied())
                    && pending.preview() == turn.snapshot().currentType();
            Resolution resolution = !same ? Resolution.BOARD_OR_TYPE_CHANGED
                    : actualQ.accepted() ? Resolution.ACTUAL_Q
                    : actual.cleanCompletion() ? Resolution.ACTUAL_CLEAN_NON_Q
                    : Resolution.ACTUAL_NOT_CLEAN;
            var prediction = new Prediction(pending.decision(), turn.decision(), resolution);
            predictions.add(prediction);
            resolved.add(prediction);
            pending = null;
        }
        var fact = new Turn(turn.decision(), turn.decisionNanos(), elapsedGameNanos,
                actual, actualQ, choice);
        turns.add(fact);
        pending = nextPending;
        return new Update(fact, List.copyOf(resolved));
    }

    Snapshot snapshot(String strategy, long seed) {
        var allPredictions = new ArrayList<>(predictions);
        if (pending != null) {
            allPredictions.add(new Prediction(pending.decision(), -1,
                    Resolution.NO_NEXT_VALIDATED_TURN));
        }
        // CSV is the existing frozen adapter's interface; do not duplicate its first-clean logic.
        return new Snapshot(List.copyOf(turns), List.copyOf(allPredictions),
                completion.csv(strategy, "heart", seed), completion.decisionNanos());
    }

    void requireMatches(GameBenchmarkResult result) {
        long total = 0;
        long maximum = 0;
        for (var turn : turns) {
            total = Math.addExact(total, turn.decisionNanos());
            maximum = Math.max(maximum, turn.decisionNanos());
        }
        if (result.decisions() != turns.size() || result.piecesPlaced() != turns.size()
                || result.totalDecisionNanos() != total || result.maxDecisionNanos() != maximum
                || result.primaryFailures() != 0 || result.fallbackDecisions() != 0) {
            throw new IllegalStateException("actual turn evidence disagrees with production result");
        }
    }

    record Turn(int decision, long decisionNanos, long elapsedGameNanos,
                ShapeProgress actual, GoalWindowCandidate.Terminal actualQ,
                GoalWindowCandidate.Observation candidateChoice) { }

    /** Comparison covers board and current type, not a claim of full snapshot equality. */
    enum Resolution {
        ACTUAL_Q, ACTUAL_CLEAN_NON_Q, ACTUAL_NOT_CLEAN,
        BOARD_OR_TYPE_CHANGED, NO_NEXT_VALIDATED_TURN
    }

    record Prediction(int setupDecision, int nextValidatedDecision, Resolution resolution) { }

    /** New facts only: streaming never recopies the whole prefix on every decision. */
    record Update(Turn turn, List<Prediction> resolvedPredictions) { }

    record Snapshot(List<Turn> turns, List<Prediction> predictions,
                    String completionCsv, List<Long> decisionNanos) { }

    private record Pending(int decision, boolean[][] board, TetrominoType preview) { }
}
