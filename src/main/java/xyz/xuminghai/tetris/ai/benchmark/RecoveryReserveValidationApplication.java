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
 * Out-of-sample validation for the recovery-reserve plus structural-debt hypothesis.
 *
 * <p>The validation contract is intentionally frozen from the preceding calibration: an
 * unrecoverable preview is always a warning; otherwise a warning requires both recovery headroom
 * at or below {@value #RECOVERY_HEADROOM_CUTOFF} and recovery holes at or above
 * {@value #RECOVERY_HOLES_CUTOFF}. The thresholds are not configurable so this application cannot
 * silently re-tune them on the validation seeds.</p>
 *
 * <p>Known clean HEART witnesses remain regression controls. The actual validation population uses
 * a different seed range, a longer healthy runtime horizon, and the rejected preserve-baseline
 * construction guard as the failure population. This application is diagnostic only and does not
 * change runtime policy.</p>
 */
public final class RecoveryReserveValidationApplication {

    static final String GAMES_ENV = "TETRIS_BENCHMARK_GAMES";
    static final String HEALTHY_MAX_PIECES_ENV = "TETRIS_BENCHMARK_MAX_PIECES";
    static final String SEED_ENV = "TETRIS_BENCHMARK_SEED";
    static final String FEASIBILITY_BEAM_WIDTH_ENV =
            "TETRIS_BENCHMARK_FEASIBILITY_BEAM_WIDTH";
    static final String FAILURE_MAX_PIECES_ENV =
            "TETRIS_BENCHMARK_VALIDATION_FAILURE_MAX_PIECES";

    static final int RECOVERY_HEADROOM_CUTOFF = 2;
    static final int RECOVERY_HOLES_CUTOFF = 15;

    private static final int DEFAULT_GAMES = 20;
    private static final int DEFAULT_HEALTHY_MAX_PIECES = 1000;
    private static final int DEFAULT_FAILURE_MAX_PIECES = 500;
    private static final long DEFAULT_SEED = 2000L;
    private static final int DEFAULT_FEASIBILITY_BEAM_WIDTH = 128;
    private static final int WITNESS_SEARCH_DEPTH = 24;
    private static final int FAILURE_HISTORY_STATES = 30;
    private static final int HEALTHY_CONTROL_INTERVAL = 5;
    private static final int HEALTHY_CONTROL_TAIL_STATES = 30;
    private static final long[] CLEAN_WITNESS_SEEDS = {1001L, 1008L};

    private RecoveryReserveValidationApplication() {
    }

    public static void main(String[] args) {
        Configuration configuration = Configuration.fromEnvironment();
        List<Sample> samples = new ArrayList<>();
        RunSummary runSummary = new RunSummary();

        collectKnownCleanWitnessSamples(configuration, samples);
        collectFailedGuardValidationSamples(configuration, samples, runSummary);
        collectHealthyRuntimeValidationSamples(configuration, samples, runSummary);

        System.out.println(
                "recovery_reserve_validation,source,seed,position,distance_to_failure,"
                        + "warning,warning_reason,preview_piece,preview_reachable_outcomes,"
                        + "preview_recoverable,recovery_headroom,recovery_aggregate_height,"
                        + "recovery_holes,recovery_bumpiness,unknown_piece_types,"
                        + "unplayable_unknown_types,min_unknown_reachable_outcomes,"
                        + "avg_unknown_reachable_outcomes,max_unknown_reachable_outcomes,"
                        + "min_post_unknown_headroom,max_post_unknown_holes,"
                        + "max_post_unknown_aggregate_height,"
                        + "fully_recoverable_across_tetrominoes");
        for (Sample sample : samples) {
            System.out.println(formatLine(sample));
        }

        printProtocol(configuration);
        printValidationSummary(samples, runSummary);
    }

    private static void collectKnownCleanWitnessSamples(
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
                        Source.KNOWN_CLEAN_WITNESS,
                        seed,
                        index + 1,
                        -1,
                        replay.probe()));
            }
        }
    }

    private static void collectFailedGuardValidationSamples(
            Configuration configuration,
            List<Sample> samples,
            RunSummary runSummary) {
        HeadlessGameRunner runner = new HeadlessGameRunner();

        for (int game = 0; game < configuration.games(); game++) {
            long seed = configuration.seed() + game;
            List<HeadlessGameRunner.TurnObservation> turns = new ArrayList<>();
            GameBenchmarkResult result = runner.runPlanningObserved(
                    seed,
                    configuration.failureMaxPieces(),
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
                        Source.FAILED_GUARD_VALIDATION,
                        seed,
                        turn.decision(),
                        distanceToFailure,
                        RecoveryRobustnessBenchmark.probe(
                                turn.selected().resultingBoard(),
                                turn.snapshot().nextType().orElseThrow())));
            }
        }
    }

    private static void collectHealthyRuntimeValidationSamples(
            Configuration configuration,
            List<Sample> samples,
            RunSummary runSummary) {
        HeadlessGameRunner runner = new HeadlessGameRunner();

        for (int game = 0; game < configuration.games(); game++) {
            long seed = configuration.seed() + game;
            List<HeadlessGameRunner.TurnObservation> turns = new ArrayList<>();
            GameBenchmarkResult result = runner.runPlanningObserved(
                    seed,
                    configuration.healthyMaxPieces(),
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
                if (!shouldSampleHealthyControl(index, tailStart)) {
                    continue;
                }
                HeadlessGameRunner.TurnObservation turn = turns.get(index);
                samples.add(new Sample(
                        Source.HEALTHY_RUNTIME_VALIDATION,
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

    static boolean warning(RecoveryRobustnessBenchmark.Probe probe) {
        Objects.requireNonNull(probe, "probe");
        if (!probe.previewRecoverable()) {
            return true;
        }
        return probe.recoveryHeadroom() <= RECOVERY_HEADROOM_CUTOFF
                && probe.recoveryHoles() >= RECOVERY_HOLES_CUTOFF;
    }

    static String warningReason(RecoveryRobustnessBenchmark.Probe probe) {
        Objects.requireNonNull(probe, "probe");
        if (!probe.previewRecoverable()) {
            return "preview-unrecoverable";
        }
        if (warning(probe)) {
            return "low-reserve-plus-structural-debt";
        }
        return "none";
    }

    static String formatLine(Sample sample) {
        Objects.requireNonNull(sample, "sample");
        RecoveryRobustnessBenchmark.Probe probe = sample.probe();
        return String.format(
                Locale.ROOT,
                "recovery_reserve_validation,%s,%d,%d,%d,%s,%s,%s,%d,%s,%d,%d,%d,%d,"
                        + "%d,%d,%d,%.3f,%d,%d,%d,%d,%s",
                sample.source().configValue,
                sample.seed(),
                sample.position(),
                sample.distanceToFailure(),
                warning(probe),
                warningReason(probe),
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

    private static void printProtocol(Configuration configuration) {
        System.out.printf(
                Locale.ROOT,
                "# recovery_reserve_validation_protocol seed_start=%d games=%d "
                        + "healthy_max_pieces=%d failure_max_pieces=%d "
                        + "failure_history_states=%d control_interval=%d control_tail_states=%d "
                        + "recovery_headroom_cutoff=%d recovery_holes_cutoff=%d%n",
                configuration.seed(),
                configuration.games(),
                configuration.healthyMaxPieces(),
                configuration.failureMaxPieces(),
                FAILURE_HISTORY_STATES,
                HEALTHY_CONTROL_INTERVAL,
                HEALTHY_CONTROL_TAIL_STATES,
                RECOVERY_HEADROOM_CUTOFF,
                RECOVERY_HOLES_CUTOFF);
    }

    private static void printValidationSummary(
            List<Sample> samples,
            RunSummary runSummary) {
        List<Sample> clean = source(samples, Source.KNOWN_CLEAN_WITNESS);
        List<Sample> failed = source(samples, Source.FAILED_GUARD_VALIDATION);
        List<Sample> controls = source(samples, Source.HEALTHY_RUNTIME_VALIDATION);

        long cleanWarnings = warningCount(clean);
        long controlWarnings = warningCount(controls);
        long controlWarningGames = controls.stream()
                .filter(sample -> warning(sample.probe()))
                .mapToLong(Sample::seed)
                .distinct()
                .count();

        List<Integer> leadTimes = new ArrayList<>();
        for (long seed : failed.stream().map(Sample::seed).distinct().toList()) {
            int leadTime = failed.stream()
                    .filter(sample -> sample.seed() == seed)
                    .filter(sample -> warning(sample.probe()))
                    .mapToInt(Sample::distanceToFailure)
                    .max()
                    .orElse(-1);
            if (leadTime >= 0) {
                leadTimes.add(leadTime);
            }
        }

        int failedDetectedGames = leadTimes.size();
        int failedMissedGames = runSummary.guardFailedGames - failedDetectedGames;
        int minLead = leadTimes.stream().mapToInt(Integer::intValue).min().orElse(-1);
        int maxLead = leadTimes.stream().mapToInt(Integer::intValue).max().orElse(-1);
        double averageLead = leadTimes.stream()
                .mapToInt(Integer::intValue)
                .average()
                .orElse(-1.0);

        System.out.printf(
                Locale.ROOT,
                "# recovery_reserve_validation_result "
                        + "known_clean_warning_samples=%d known_clean_samples=%d "
                        + "healthy_warning_samples=%d healthy_samples=%d "
                        + "healthy_warning_rate=%.6f healthy_warning_games=%d "
                        + "runtime_control_healthy_games=%d runtime_control_failed_games=%d "
                        + "failed_detected_games=%d failed_missed_games=%d "
                        + "guard_failed_games=%d guard_survived_games=%d "
                        + "min_lead_time=%d avg_lead_time=%.3f max_lead_time=%d%n",
                cleanWarnings,
                clean.size(),
                controlWarnings,
                controls.size(),
                controls.isEmpty() ? 0.0 : (double) controlWarnings / controls.size(),
                controlWarningGames,
                runSummary.runtimeControlHealthyGames,
                runSummary.runtimeControlFailedGames,
                failedDetectedGames,
                failedMissedGames,
                runSummary.guardFailedGames,
                runSummary.guardSurvivedGames,
                minLead,
                averageLead,
                maxLead);
    }

    private static List<Sample> source(List<Sample> samples, Source source) {
        return samples.stream().filter(sample -> sample.source() == source).toList();
    }

    private static long warningCount(List<Sample> samples) {
        return samples.stream().filter(sample -> warning(sample.probe())).count();
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
        KNOWN_CLEAN_WITNESS("known-clean-witness"),
        FAILED_GUARD_VALIDATION("failed-guard-validation"),
        HEALTHY_RUNTIME_VALIDATION("healthy-runtime-validation");

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
                throw new IllegalArgumentException("invalid recovery reserve validation sample");
            }
            if (source == Source.FAILED_GUARD_VALIDATION && distanceToFailure < 0) {
                throw new IllegalArgumentException(
                        "failed guard samples require failure distance");
            }
            if (source != Source.FAILED_GUARD_VALIDATION && distanceToFailure != -1) {
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
            int healthyMaxPieces,
            int failureMaxPieces,
            long seed,
            int feasibilityBeamWidth) {

        static Configuration fromEnvironment() {
            return new Configuration(
                    positiveInt(GAMES_ENV, DEFAULT_GAMES),
                    positiveInt(HEALTHY_MAX_PIECES_ENV, DEFAULT_HEALTHY_MAX_PIECES),
                    positiveInt(FAILURE_MAX_PIECES_ENV, DEFAULT_FAILURE_MAX_PIECES),
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
