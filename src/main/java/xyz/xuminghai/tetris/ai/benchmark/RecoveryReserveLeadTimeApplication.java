/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai.benchmark;

import xyz.xuminghai.tetris.ai.BuildShapeActionPlanningAgent;
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
 * Extends recovery-robustness calibration with longer failure lead-time and healthy runtime
 * controls.
 *
 * <p>This benchmark intentionally does not choose a runtime threshold. It compares three sources:
 * known clean HEART witness states, up to the final thirty successful placements before game-over
 * from the rejected preserve-baseline construction guard, and sampled states from current runtime
 * BUILD_SHAPE games that reach the full piece limit. Candidate headroom cutoffs are swept only to
 * measure warning lead-time and false positives.</p>
 */
public final class RecoveryReserveLeadTimeApplication {

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
    private static final int FAILURE_HISTORY_STATES = 30;
    private static final int HEALTHY_CONTROL_INTERVAL = 5;
    private static final int HEALTHY_CONTROL_TAIL_STATES = 30;
    private static final int[] HEADROOM_CUTOFFS = {2, 4, 6, 8};
    private static final long[] CLEAN_WITNESS_SEEDS = {1001L, 1008L};

    private RecoveryReserveLeadTimeApplication() {
    }

    public static void main(String[] args) {
        Configuration configuration = Configuration.fromEnvironment();
        List<Sample> samples = new ArrayList<>();
        RunSummary runSummary = new RunSummary();

        collectCleanWitnessSamples(configuration, samples);
        collectFailedGuardLeadTimeSamples(configuration, samples, runSummary);
        collectHealthyRuntimeControlSamples(configuration, samples, runSummary);

        System.out.println(
                "recovery_reserve,source,seed,position,distance_to_failure,preview_piece,"
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

        printSourceSummary(samples);
        printRunSummary(runSummary);
        printCutoffSweep(samples, runSummary, Metric.RECOVERY_HEADROOM);
        printCutoffSweep(samples, runSummary, Metric.POST_UNKNOWN_HEADROOM);
        printFailureDistanceBands(samples);
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
                ShapeConstructionFeasibilityBenchmark.WitnessStep step = witness.get(index);
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

    private static void collectFailedGuardLeadTimeSamples(
            Configuration configuration,
            List<Sample> samples,
            RunSummary runSummary) {
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
                runSummary.guardSurvivedGames++;
                continue;
            }
            runSummary.guardFailedGames++;

            int first = Math.max(0, turns.size() - FAILURE_HISTORY_STATES);
            for (int index = first; index < turns.size(); index++) {
                HeadlessGameRunner.TurnObservation turn = turns.get(index);
                int distanceToFailure = turns.size() - 1 - index;
                samples.add(new Sample(
                        Source.FAILED_GUARD_LEAD_TIME,
                        seed,
                        turn.decision(),
                        distanceToFailure,
                        RecoveryRobustnessBenchmark.probe(
                                turn.selected().resultingBoard(),
                                turn.snapshot().nextType().orElseThrow())));
            }
        }
    }

    private static void collectHealthyRuntimeControlSamples(
            Configuration configuration,
            List<Sample> samples,
            RunSummary runSummary) {
        HeadlessGameRunner runner = new HeadlessGameRunner();

        for (int game = 0; game < configuration.games(); game++) {
            long seed = configuration.seed() + game;
            List<HeadlessGameRunner.TurnObservation> turns = new ArrayList<>();
            GameBenchmarkResult result = runner.runPlanningObserved(
                    seed,
                    configuration.maxPieces(),
                    new BuildShapeActionPlanningAgent(),
                    null,
                    turns::add);

            if (!result.reachedPieceLimit()) {
                runSummary.runtimeControlFailedGames++;
                continue;
            }
            runSummary.runtimeControlHealthyGames++;

            int tailStart = Math.max(0, turns.size() - HEALTHY_CONTROL_TAIL_STATES);
            for (int index = 0; index < turns.size(); index++) {
                HeadlessGameRunner.TurnObservation turn = turns.get(index);
                if (!shouldSampleHealthyControl(index, tailStart)) {
                    continue;
                }
                samples.add(new Sample(
                        Source.HEALTHY_RUNTIME_CONTROL,
                        seed,
                        turn.decision(),
                        -1,
                        RecoveryRobustnessBenchmark.probe(
                                turn.selected().resultingBoard(),
                                turn.snapshot().nextType().orElseThrow())));
            }
        }
    }

    static boolean shouldSampleHealthyControl(int zeroBasedIndex, int tailStart) {
        if (zeroBasedIndex < 0 || tailStart < 0) {
            throw new IllegalArgumentException("sample indices must not be negative");
        }
        int decision = zeroBasedIndex + 1;
        return decision % HEALTHY_CONTROL_INTERVAL == 0 || zeroBasedIndex >= tailStart;
    }

    static String formatLine(Sample sample) {
        Objects.requireNonNull(sample, "sample");
        RecoveryRobustnessBenchmark.Probe probe = sample.probe();
        return String.format(
                Locale.ROOT,
                "recovery_reserve,%s,%d,%d,%d,%s,%d,%s,%d,%d,%d,%d,%d,%d,%d,%.3f,"
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

    private static void printSourceSummary(List<Sample> samples) {
        for (Source source : Source.values()) {
            List<Sample> sourceSamples = samples.stream()
                    .filter(sample -> sample.source() == source)
                    .toList();
            if (sourceSamples.isEmpty()) {
                continue;
            }

            int minRecoveryHeadroom = sourceSamples.stream()
                    .mapToInt(sample -> sample.probe().recoveryHeadroom())
                    .filter(value -> value >= 0)
                    .min()
                    .orElse(-1);
            int maxRecoveryHeadroom = sourceSamples.stream()
                    .mapToInt(sample -> sample.probe().recoveryHeadroom())
                    .max()
                    .orElse(-1);
            int minPostUnknownHeadroom = sourceSamples.stream()
                    .mapToInt(sample -> sample.probe().minPostUnknownHeadroom())
                    .filter(value -> value >= 0)
                    .min()
                    .orElse(-1);
            int maxPostUnknownHeadroom = sourceSamples.stream()
                    .mapToInt(sample -> sample.probe().minPostUnknownHeadroom())
                    .max()
                    .orElse(-1);
            long previewUnrecoverable = sourceSamples.stream()
                    .filter(sample -> !sample.probe().previewRecoverable())
                    .count();

            System.out.printf(
                    Locale.ROOT,
                    "# recovery_reserve source=%s samples=%d preview_unrecoverable=%d "
                            + "min_recovery_headroom=%d max_recovery_headroom=%d "
                            + "min_post_unknown_headroom=%d max_post_unknown_headroom=%d%n",
                    source.configValue,
                    sourceSamples.size(),
                    previewUnrecoverable,
                    minRecoveryHeadroom,
                    maxRecoveryHeadroom,
                    minPostUnknownHeadroom,
                    maxPostUnknownHeadroom);
        }
    }

    private static void printRunSummary(RunSummary summary) {
        System.out.printf(
                Locale.ROOT,
                "# recovery_reserve_runs guard_failed_games=%d guard_survived_games=%d "
                        + "runtime_control_healthy_games=%d runtime_control_failed_games=%d "
                        + "failure_history_states=%d control_interval=%d control_tail_states=%d%n",
                summary.guardFailedGames,
                summary.guardSurvivedGames,
                summary.runtimeControlHealthyGames,
                summary.runtimeControlFailedGames,
                FAILURE_HISTORY_STATES,
                HEALTHY_CONTROL_INTERVAL,
                HEALTHY_CONTROL_TAIL_STATES);
    }

    private static void printCutoffSweep(
            List<Sample> samples,
            RunSummary runSummary,
            Metric metric) {
        List<Sample> clean = source(samples, Source.CLEAN_WITNESS);
        List<Sample> failed = source(samples, Source.FAILED_GUARD_LEAD_TIME);
        List<Sample> controls = source(samples, Source.HEALTHY_RUNTIME_CONTROL);

        for (int cutoff : HEADROOM_CUTOFFS) {
            long cleanWarnings = warningCount(clean, metric, cutoff);
            long controlWarnings = warningCount(controls, metric, cutoff);
            long controlWarningGames = controls.stream()
                    .filter(sample -> warning(sample, metric, cutoff))
                    .mapToLong(Sample::seed)
                    .distinct()
                    .count();

            List<Long> failedSeeds = failed.stream()
                    .map(Sample::seed)
                    .distinct()
                    .toList();
            List<Integer> leadTimes = new ArrayList<>();
            for (long seed : failedSeeds) {
                int leadTime = failed.stream()
                        .filter(sample -> sample.seed() == seed)
                        .filter(sample -> warning(sample, metric, cutoff))
                        .mapToInt(Sample::distanceToFailure)
                        .max()
                        .orElse(-1);
                if (leadTime >= 0) {
                    leadTimes.add(leadTime);
                }
            }

            int minLead = leadTimes.stream().mapToInt(Integer::intValue).min().orElse(-1);
            int maxLead = leadTimes.stream().mapToInt(Integer::intValue).max().orElse(-1);
            double averageLead = leadTimes.stream()
                    .mapToInt(Integer::intValue)
                    .average()
                    .orElse(-1.0);

            System.out.printf(
                    Locale.ROOT,
                    "# recovery_reserve_cutoff metric=%s cutoff=%d "
                            + "clean_warning_samples=%d clean_samples=%d "
                            + "control_warning_samples=%d control_samples=%d "
                            + "control_warning_rate=%.4f control_warning_games=%d "
                            + "control_healthy_games=%d failed_detected_games=%d "
                            + "guard_failed_games=%d min_lead_time=%d avg_lead_time=%.3f "
                            + "max_lead_time=%d%n",
                    metric.configValue,
                    cutoff,
                    cleanWarnings,
                    clean.size(),
                    controlWarnings,
                    controls.size(),
                    controls.isEmpty() ? 0.0 : (double) controlWarnings / controls.size(),
                    controlWarningGames,
                    runSummary.runtimeControlHealthyGames,
                    leadTimes.size(),
                    runSummary.guardFailedGames,
                    minLead,
                    averageLead,
                    maxLead);
        }
    }

    private static void printFailureDistanceBands(List<Sample> samples) {
        List<Sample> failed = source(samples, Source.FAILED_GUARD_LEAD_TIME);
        int[][] bands = {
                {0, 4},
                {5, 9},
                {10, 14},
                {15, 19},
                {20, 24},
                {25, 29}
        };

        for (int[] band : bands) {
            List<Sample> inBand = failed.stream()
                    .filter(sample ->
                            sample.distanceToFailure() >= band[0]
                                    && sample.distanceToFailure() <= band[1])
                    .toList();
            if (inBand.isEmpty()) {
                continue;
            }

            double averageRecoveryHeadroom = inBand.stream()
                    .mapToInt(sample -> Math.max(0, sample.probe().recoveryHeadroom()))
                    .average()
                    .orElse(0.0);
            int maxRecoveryHeadroom = inBand.stream()
                    .mapToInt(sample -> sample.probe().recoveryHeadroom())
                    .max()
                    .orElse(-1);
            double averagePostUnknownHeadroom = inBand.stream()
                    .mapToInt(sample -> Math.max(0, sample.probe().minPostUnknownHeadroom()))
                    .average()
                    .orElse(0.0);
            int maxPostUnknownHeadroom = inBand.stream()
                    .mapToInt(sample -> sample.probe().minPostUnknownHeadroom())
                    .max()
                    .orElse(-1);

            System.out.printf(
                    Locale.ROOT,
                    "# recovery_reserve_distance_band min_distance=%d max_distance=%d samples=%d "
                            + "avg_recovery_headroom=%.3f max_recovery_headroom=%d "
                            + "avg_post_unknown_headroom=%.3f max_post_unknown_headroom=%d%n",
                    band[0],
                    band[1],
                    inBand.size(),
                    averageRecoveryHeadroom,
                    maxRecoveryHeadroom,
                    averagePostUnknownHeadroom,
                    maxPostUnknownHeadroom);
        }
    }

    private static List<Sample> source(List<Sample> samples, Source source) {
        return samples.stream().filter(sample -> sample.source() == source).toList();
    }

    private static long warningCount(
            List<Sample> samples,
            Metric metric,
            int cutoff) {
        return samples.stream().filter(sample -> warning(sample, metric, cutoff)).count();
    }

    static boolean warning(
            Sample sample,
            Metric metric,
            int cutoff) {
        Objects.requireNonNull(sample, "sample");
        Objects.requireNonNull(metric, "metric");
        if (cutoff < 0) {
            throw new IllegalArgumentException("cutoff must not be negative");
        }
        int value = metric.value(sample.probe());
        return value < 0 || value <= cutoff;
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
        FAILED_GUARD_LEAD_TIME("failed-guard-lead-time"),
        HEALTHY_RUNTIME_CONTROL("healthy-runtime-control");

        private final String configValue;

        Source(String configValue) {
            this.configValue = configValue;
        }
    }

    enum Metric {
        RECOVERY_HEADROOM("recovery-headroom") {
            @Override
            int value(RecoveryRobustnessBenchmark.Probe probe) {
                return probe.recoveryHeadroom();
            }
        },
        POST_UNKNOWN_HEADROOM("post-unknown-headroom") {
            @Override
            int value(RecoveryRobustnessBenchmark.Probe probe) {
                return probe.minPostUnknownHeadroom();
            }
        };

        private final String configValue;

        Metric(String configValue) {
            this.configValue = configValue;
        }

        abstract int value(RecoveryRobustnessBenchmark.Probe probe);
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
                throw new IllegalArgumentException("invalid recovery reserve sample");
            }
            if (source == Source.FAILED_GUARD_LEAD_TIME && distanceToFailure < 0) {
                throw new IllegalArgumentException(
                        "failed guard samples require failure distance");
            }
            if (source != Source.FAILED_GUARD_LEAD_TIME && distanceToFailure != -1) {
                throw new IllegalArgumentException(
                        "non-failure samples must not have failure distance");
            }
        }
    }

    private static final class RunSummary {
        private int guardFailedGames;
        private int guardSurvivedGames;
        private int runtimeControlHealthyGames;
        private int runtimeControlFailedGames;
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
