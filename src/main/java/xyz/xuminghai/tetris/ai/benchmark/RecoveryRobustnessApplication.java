/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai.benchmark;

import xyz.xuminghai.tetris.ai.BuildShapeConstructionGuardPlanningAgent;
import xyz.xuminghai.tetris.ai.ConstructionSafetyGuard;
import xyz.xuminghai.tetris.ai.RecoveryRobustnessBenchmark;
import xyz.xuminghai.tetris.ai.ShapeConstructionFeasibilityBenchmark;
import xyz.xuminghai.tetris.ai.ShapeTarget;
import xyz.xuminghai.tetris.core.BagPieceGenerator;
import xyz.xuminghai.tetris.core.TetrominoType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Compares recovery-robustness facts from known clean HEART construction states with states
 * observed shortly before game-over in the rejected full-reachable construction-guard experiment.
 *
 * <p>The positive set is intentionally anchored to the two established clean witness seeds.
 * Negative samples come from the last five successful placements of each failed
 * {@code preserve-baseline} guard game. This benchmark does not choose a threshold or alter a
 * planner; it only asks whether recovery facts separate the two classes strongly enough to justify
 * a later safety-policy experiment.</p>
 */
public final class RecoveryRobustnessApplication {

    static final String GAMES_ENV = "TETRIS_BENCHMARK_GAMES";
    static final String MAX_PIECES_ENV = "TETRIS_BENCHMARK_MAX_PIECES";
    static final String SEED_ENV = "TETRIS_BENCHMARK_SEED";
    static final String FEASIBILITY_BEAM_WIDTH_ENV =
            "TETRIS_BENCHMARK_FEASIBILITY_BEAM_WIDTH";

    private static final int DEFAULT_GAMES = 10;
    private static final int DEFAULT_MAX_PIECES = 250;
    private static final long DEFAULT_SEED = 1000L;
    private static final int DEFAULT_FEASIBILITY_BEAM_WIDTH = 128;
    private static final int WITNESS_SEARCH_DEPTH = 24;
    private static final int FAILURE_TAIL_STATES = 5;
    private static final long[] CLEAN_WITNESS_SEEDS = {1001L, 1008L};

    private RecoveryRobustnessApplication() {
    }

    public static void main(String[] args) {
        Configuration configuration = Configuration.fromEnvironment();
        List<Sample> samples = new ArrayList<>();

        collectCleanWitnessSamples(configuration, samples);
        collectFailedGuardTailSamples(configuration, samples);

        System.out.println(
                "recovery_robustness,source,seed,position,distance_to_failure,preview_piece,"
                        + "preview_reachable_outcomes,preview_recoverable,recovery_headroom,"
                        + "recovery_aggregate_height,recovery_holes,recovery_bumpiness,"
                        + "unknown_piece_types,unplayable_unknown_types,"
                        + "min_unknown_reachable_outcomes,avg_unknown_reachable_outcomes,"
                        + "max_unknown_reachable_outcomes,min_post_unknown_headroom,"
                        + "max_post_unknown_holes,max_post_unknown_aggregate_height,"
                        + "fully_recoverable_across_tetrominoes");
        for (Sample sample : samples) {
            System.out.println(formatLine(sample));
        }

        printSummary(samples);
        printSeparationSummary(samples);
        printFailureDistanceSummary(samples);
    }

    private static void collectCleanWitnessSamples(
            Configuration configuration,
            List<Sample> samples) {
        for (long seed : CLEAN_WITNESS_SEEDS) {
            List<TetrominoType> fullSequence =
                    pieceSequence(seed, WITNESS_SEARCH_DEPTH + 1);
            ShapeConstructionFeasibilityBenchmark.Result result =
                    ShapeConstructionFeasibilityBenchmark.search(
                            ShapeTarget.HEART,
                            fullSequence.subList(0, WITNESS_SEARCH_DEPTH),
                            HeadlessGameRunner.DEFAULT_ROWS,
                            HeadlessGameRunner.DEFAULT_COLS,
                            configuration.feasibilityBeamWidth());
            if (!result.cleanCompletionFound()) {
                throw new IllegalStateException(
                        "known clean witness seed no longer completes: " + seed);
            }

            boolean[][] board =
                    new boolean[HeadlessGameRunner.DEFAULT_ROWS][HeadlessGameRunner.DEFAULT_COLS];
            List<ShapeConstructionFeasibilityBenchmark.WitnessStep> witness =
                    result.witness();
            for (int index = 0; index < witness.size(); index++) {
                ShapeConstructionFeasibilityBenchmark.WitnessStep step =
                        witness.get(index);
                TetrominoType preview = fullSequence.get(index + 1);
                RecoveryRobustnessBenchmark.ReplayedPlacement replay =
                        RecoveryRobustnessBenchmark.replayAndProbe(
                                board,
                                step.pieceType(),
                                step.plan(),
                                preview);
                board = replay.placement().resultingBoard();
                samples.add(new Sample(
                        Source.CLEAN_WITNESS,
                        seed,
                        index + 1,
                        -1,
                        replay.probe()));
            }
        }
    }

    private static void collectFailedGuardTailSamples(
            Configuration configuration,
            List<Sample> samples) {
        HeadlessGameRunner runner = new HeadlessGameRunner();

        for (int game = 0; game < configuration.games(); game++) {
            long seed = configuration.seed() + game;
            List<HeadlessGameRunner.TurnObservation> turns = new ArrayList<>();
            GameBenchmarkResult result = runner.runPlanningObserved(
                    seed,
                    configuration.maxPieces(),
                    new BuildShapeConstructionGuardPlanningAgent(
                            ConstructionSafetyGuard.Profile.PRESERVE_BASELINE),
                    null,
                    turns::add);

            if (result.reachedPieceLimit()) {
                continue;
            }

            int first = Math.max(0, turns.size() - FAILURE_TAIL_STATES);
            for (int index = first; index < turns.size(); index++) {
                HeadlessGameRunner.TurnObservation turn = turns.get(index);
                TetrominoType preview =
                        turn.snapshot().nextType().orElseThrow();
                int distanceToFailure = turns.size() - 1 - index;
                samples.add(new Sample(
                        Source.FAILED_GUARD_TAIL,
                        seed,
                        turn.decision(),
                        distanceToFailure,
                        RecoveryRobustnessBenchmark.probe(
                                turn.selected().resultingBoard(),
                                preview)));
            }
        }
    }

    static String formatLine(Sample sample) {
        Objects.requireNonNull(sample, "sample");
        RecoveryRobustnessBenchmark.Probe probe = sample.probe();
        return String.format(
                Locale.ROOT,
                "recovery_robustness,%s,%d,%d,%d,%s,%d,%s,%d,%d,%d,%d,%d,%d,%d,%.3f,"
                        + "%d,%d,%d,%d,%s",
                sample.source().configValue,
                sample.seed(),
                sample.position(),
                sample.distanceToFailure(),
                probe.previewType().name(),
                probe.previewReachableOutcomes(),
                probe.previewRecoverable(),
                probe.recoveryHeadroom(),
                probe.recoveryAggregateHeight(),
                probe.recoveryHoles(),
                probe.recoveryBumpiness(),
                probe.unknownPieceTypes(),
                probe.unplayableUnknownTypes(),
                probe.minUnknownReachableOutcomes(),
                probe.averageUnknownReachableOutcomes(),
                probe.maxUnknownReachableOutcomes(),
                probe.minPostUnknownHeadroom(),
                probe.maxPostUnknownHoles(),
                probe.maxPostUnknownAggregateHeight(),
                probe.fullyRecoverableAcrossTetrominoes());
    }

    private static void printSummary(List<Sample> samples) {
        for (Source source : Source.values()) {
            List<Sample> sourceSamples = samples.stream()
                    .filter(sample -> sample.source() == source)
                    .toList();
            if (sourceSamples.isEmpty()) {
                continue;
            }

            long previewUnrecoverable = sourceSamples.stream()
                    .filter(sample -> !sample.probe().previewRecoverable())
                    .count();
            long fullyRecoverable = sourceSamples.stream()
                    .filter(sample -> sample.probe().fullyRecoverableAcrossTetrominoes())
                    .count();
            double averageMinReachable = sourceSamples.stream()
                    .mapToInt(sample -> sample.probe().minUnknownReachableOutcomes())
                    .average()
                    .orElse(0.0);
            int minimumMinReachable = sourceSamples.stream()
                    .mapToInt(sample -> sample.probe().minUnknownReachableOutcomes())
                    .min()
                    .orElse(0);
            double averageRecoveryHeadroom = sourceSamples.stream()
                    .mapToInt(sample -> Math.max(0, sample.probe().recoveryHeadroom()))
                    .average()
                    .orElse(0.0);
            int minimumRecoveryHeadroom = sourceSamples.stream()
                    .mapToInt(sample -> sample.probe().recoveryHeadroom())
                    .filter(value -> value >= 0)
                    .min()
                    .orElse(-1);
            int maximumRecoveryHoles = sourceSamples.stream()
                    .mapToInt(sample -> sample.probe().recoveryHoles())
                    .max()
                    .orElse(-1);
            int minimumPostUnknownHeadroom = sourceSamples.stream()
                    .mapToInt(sample -> sample.probe().minPostUnknownHeadroom())
                    .filter(value -> value >= 0)
                    .min()
                    .orElse(-1);
            int maximumUnplayable = sourceSamples.stream()
                    .mapToInt(sample -> sample.probe().unplayableUnknownTypes())
                    .max()
                    .orElse(0);

            System.out.printf(
                    Locale.ROOT,
                    "# recovery_robustness source=%s samples=%d preview_unrecoverable=%d "
                            + "fully_recoverable=%d fully_recoverable_rate=%.4f "
                            + "avg_min_unknown_reachable=%.3f min_unknown_reachable=%d "
                            + "avg_recovery_headroom=%.3f min_recovery_headroom=%d "
                            + "max_recovery_holes=%d min_post_unknown_headroom=%d "
                            + "max_unplayable_unknown_types=%d%n",
                    source.configValue,
                    sourceSamples.size(),
                    previewUnrecoverable,
                    fullyRecoverable,
                    (double) fullyRecoverable / sourceSamples.size(),
                    averageMinReachable,
                    minimumMinReachable,
                    averageRecoveryHeadroom,
                    minimumRecoveryHeadroom,
                    maximumRecoveryHoles,
                    minimumPostUnknownHeadroom,
                    maximumUnplayable);
        }
    }

    private static void printSeparationSummary(List<Sample> samples) {
        List<Sample> positive = samples.stream()
                .filter(sample -> sample.source() == Source.CLEAN_WITNESS)
                .toList();
        List<Sample> negative = samples.stream()
                .filter(sample -> sample.source() == Source.FAILED_GUARD_TAIL)
                .toList();
        if (positive.isEmpty() || negative.isEmpty()) {
            return;
        }

        int positiveMinRecoveryHeadroom = positive.stream()
                .mapToInt(sample -> sample.probe().recoveryHeadroom())
                .filter(value -> value >= 0)
                .min()
                .orElse(-1);
        int negativeMaxRecoveryHeadroom = negative.stream()
                .mapToInt(sample -> sample.probe().recoveryHeadroom())
                .max()
                .orElse(-1);
        int positiveMinPostUnknownHeadroom = positive.stream()
                .mapToInt(sample -> sample.probe().minPostUnknownHeadroom())
                .filter(value -> value >= 0)
                .min()
                .orElse(-1);
        int negativeMaxPostUnknownHeadroom = negative.stream()
                .mapToInt(sample -> sample.probe().minPostUnknownHeadroom())
                .max()
                .orElse(-1);
        int positiveMinUnknownReachable = positive.stream()
                .mapToInt(sample -> sample.probe().minUnknownReachableOutcomes())
                .min()
                .orElse(-1);
        int negativeMaxMinUnknownReachable = negative.stream()
                .mapToInt(sample -> sample.probe().minUnknownReachableOutcomes())
                .max()
                .orElse(-1);
        int positiveMaxRecoveryHoles = positive.stream()
                .mapToInt(sample -> sample.probe().recoveryHoles())
                .max()
                .orElse(-1);
        int negativeMinRecoveryHoles = negative.stream()
                .mapToInt(sample -> sample.probe().recoveryHoles())
                .filter(value -> value >= 0)
                .min()
                .orElse(-1);

        System.out.printf(
                Locale.ROOT,
                "# recovery_robustness_separation positive_samples=%d negative_samples=%d "
                        + "positive_min_recovery_headroom=%d negative_max_recovery_headroom=%d "
                        + "recovery_headroom_gap=%d "
                        + "positive_min_post_unknown_headroom=%d "
                        + "negative_max_post_unknown_headroom=%d post_unknown_headroom_gap=%d "
                        + "positive_min_unknown_reachable=%d "
                        + "negative_max_min_unknown_reachable=%d unknown_reachable_gap=%d "
                        + "positive_max_recovery_holes=%d negative_min_recovery_holes=%d%n",
                positive.size(),
                negative.size(),
                positiveMinRecoveryHeadroom,
                negativeMaxRecoveryHeadroom,
                positiveMinRecoveryHeadroom - negativeMaxRecoveryHeadroom,
                positiveMinPostUnknownHeadroom,
                negativeMaxPostUnknownHeadroom,
                positiveMinPostUnknownHeadroom - negativeMaxPostUnknownHeadroom,
                positiveMinUnknownReachable,
                negativeMaxMinUnknownReachable,
                positiveMinUnknownReachable - negativeMaxMinUnknownReachable,
                positiveMaxRecoveryHoles,
                negativeMinRecoveryHoles);
    }

    private static void printFailureDistanceSummary(List<Sample> samples) {
        samples.stream()
                .filter(sample -> sample.source() == Source.FAILED_GUARD_TAIL)
                .mapToInt(Sample::distanceToFailure)
                .distinct()
                .sorted()
                .forEach(distance -> {
                    List<Sample> atDistance = samples.stream()
                            .filter(sample ->
                                    sample.source() == Source.FAILED_GUARD_TAIL
                                            && sample.distanceToFailure() == distance)
                            .toList();
                    double averageMinReachable = atDistance.stream()
                            .mapToInt(sample -> sample.probe().minUnknownReachableOutcomes())
                            .average()
                            .orElse(0.0);
                    double averageHeadroom = atDistance.stream()
                            .mapToInt(sample -> Math.max(0, sample.probe().recoveryHeadroom()))
                            .average()
                            .orElse(0.0);
                    long fullyRecoverable = atDistance.stream()
                            .filter(sample ->
                                    sample.probe().fullyRecoverableAcrossTetrominoes())
                            .count();
                    System.out.printf(
                            Locale.ROOT,
                            "# recovery_robustness_distance distance_to_failure=%d samples=%d "
                                    + "fully_recoverable=%d avg_min_unknown_reachable=%.3f "
                                    + "avg_recovery_headroom=%.3f%n",
                            distance,
                            atDistance.size(),
                            fullyRecoverable,
                            averageMinReachable,
                            averageHeadroom);
                });
    }

    private static List<TetrominoType> pieceSequence(long seed, int count) {
        BagPieceGenerator generator = new BagPieceGenerator(seed);
        List<TetrominoType> pieces = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            pieces.add(TetrominoType.from(generator.next()));
        }
        return List.copyOf(pieces);
    }

    enum Source {
        CLEAN_WITNESS("clean-witness"),
        FAILED_GUARD_TAIL("failed-guard-tail");

        private final String configValue;

        Source(String configValue) {
            this.configValue = configValue;
        }
    }

    record Sample(
            Source source,
            long seed,
            int position,
            int distanceToFailure,
            RecoveryRobustnessBenchmark.Probe probe) {

        Sample {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(probe, "probe");
            if (position <= 0 || distanceToFailure < -1) {
                throw new IllegalArgumentException("invalid recovery robustness sample");
            }
            if (source == Source.CLEAN_WITNESS && distanceToFailure != -1) {
                throw new IllegalArgumentException(
                        "clean witness samples must not have failure distance");
            }
            if (source == Source.FAILED_GUARD_TAIL && distanceToFailure < 0) {
                throw new IllegalArgumentException(
                        "failed guard samples require failure distance");
            }
        }
    }

    private record Configuration(
            int games,
            int maxPieces,
            long seed,
            int feasibilityBeamWidth) {

        static Configuration fromEnvironment() {
            return new Configuration(
                    positiveInt(GAMES_ENV, DEFAULT_GAMES),
                    positiveInt(MAX_PIECES_ENV, DEFAULT_MAX_PIECES),
                    longValue(SEED_ENV, DEFAULT_SEED),
                    positiveInt(
                            FEASIBILITY_BEAM_WIDTH_ENV,
                            DEFAULT_FEASIBILITY_BEAM_WIDTH));
        }

        private static int positiveInt(String name, int defaultValue) {
            String value = System.getenv(name);
            if (value == null || value.isBlank()) {
                return defaultValue;
            }
            int parsed = Integer.parseInt(value);
            if (parsed <= 0) {
                throw new IllegalArgumentException(name + " must be greater than 0");
            }
            return parsed;
        }

        private static long longValue(String name, long defaultValue) {
            String value = System.getenv(name);
            return value == null || value.isBlank()
                    ? defaultValue
                    : Long.parseLong(value);
        }
    }
}
