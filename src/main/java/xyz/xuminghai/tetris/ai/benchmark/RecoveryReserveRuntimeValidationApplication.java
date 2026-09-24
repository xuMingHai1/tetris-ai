/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai.benchmark;

import xyz.xuminghai.tetris.ai.BuildShapeActionPlanningAgent;
import xyz.xuminghai.tetris.ai.RecoveryRobustnessBenchmark;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Validates the frozen recovery-reserve warning signal against failures of the current production
 * BUILD_SHAPE planner itself.
 *
 * <p>This is the follow-up to {@link RecoveryReserveValidationApplication}. It does not calibrate
 * or tune the signal. The warning semantics are delegated to that frozen validation contract:
 * unrecoverable preview is a warning; otherwise recovery headroom must be at most 2 and recovery
 * holes at least 15.</p>
 *
 * <p>Each seed runs the production {@link BuildShapeActionPlanningAgent} exactly once. Games that
 * reach the complete horizon provide sampled healthy controls. Games that terminate before the
 * horizon provide up to the final thirty successful placements as failure trajectories. This keeps
 * prediction evidence tied to the production policy instead of the earlier benchmark-only
 * construction guard.</p>
 */
public final class RecoveryReserveRuntimeValidationApplication {

    static final String GAMES_ENV = "TETRIS_BENCHMARK_GAMES";
    static final String MAX_PIECES_ENV = "TETRIS_BENCHMARK_MAX_PIECES";
    static final String SEED_ENV = "TETRIS_BENCHMARK_SEED";

    private static final int DEFAULT_GAMES = 40;
    private static final int DEFAULT_MAX_PIECES = 1000;
    private static final long DEFAULT_SEED = 3000L;
    private static final int FAILURE_HISTORY_STATES = 30;
    private static final int HEALTHY_CONTROL_TAIL_STATES = 30;

    private RecoveryReserveRuntimeValidationApplication() {
    }

    public static void main(String[] args) {
        Configuration configuration = Configuration.fromEnvironment();
        List<Sample> samples = new ArrayList<>();
        RunSummary runSummary = new RunSummary();

        collectRuntimeSamples(configuration, samples, runSummary);

        System.out.println(
                "recovery_reserve_runtime_validation,source,seed,position,distance_to_failure,"
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

    private static void collectRuntimeSamples(
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

            if (result.reachedPieceLimit()) {
                runSummary.healthyGames++;
                collectHealthySamples(seed, turns, samples);
            } else {
                runSummary.failedGames++;
                collectFailureSamples(seed, turns, samples);
            }
        }
    }

    private static void collectHealthySamples(
            long seed,
            List<HeadlessGameRunner.TurnObservation> turns,
            List<Sample> samples) {
        int tailStart = Math.max(0, turns.size() - HEALTHY_CONTROL_TAIL_STATES);
        for (int index = 0; index < turns.size(); index++) {
            if (!RecoveryReserveValidationApplication.shouldSampleHealthyControl(
                    index,
                    tailStart)) {
                continue;
            }
            HeadlessGameRunner.TurnObservation turn = turns.get(index);
            samples.add(new Sample(
                    Source.HEALTHY_RUNTIME,
                    seed,
                    turn.decision(),
                    -1,
                    RecoveryRobustnessBenchmark.probe(
                            turn.selected().resultingBoard(),
                            turn.snapshot().nextType().orElseThrow())));
        }
    }

    private static void collectFailureSamples(
            long seed,
            List<HeadlessGameRunner.TurnObservation> turns,
            List<Sample> samples) {
        int first = Math.max(0, turns.size() - FAILURE_HISTORY_STATES);
        for (int index = first; index < turns.size(); index++) {
            HeadlessGameRunner.TurnObservation turn = turns.get(index);
            int distanceToFailure = turns.size() - 1 - index;
            samples.add(new Sample(
                    Source.FAILED_RUNTIME,
                    seed,
                    turn.decision(),
                    distanceToFailure,
                    RecoveryRobustnessBenchmark.probe(
                            turn.selected().resultingBoard(),
                            turn.snapshot().nextType().orElseThrow())));
        }
    }

    static boolean warning(RecoveryRobustnessBenchmark.Probe probe) {
        return RecoveryReserveValidationApplication.warning(probe);
    }

    static String warningReason(RecoveryRobustnessBenchmark.Probe probe) {
        return RecoveryReserveValidationApplication.warningReason(probe);
    }

    static String formatLine(Sample sample) {
        Objects.requireNonNull(sample, "sample");
        RecoveryRobustnessBenchmark.Probe probe = sample.probe();
        return String.format(
                Locale.ROOT,
                "recovery_reserve_runtime_validation,%s,%d,%d,%d,%s,%s,%s,%d,%s,%d,%d,"
                        + "%d,%d,%d,%d,%d,%.3f,%d,%d,%d,%d,%s",
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
                "# recovery_reserve_runtime_validation_protocol seed_start=%d games=%d "
                        + "max_pieces=%d failure_history_states=%d "
                        + "healthy_control_tail_states=%d recovery_headroom_cutoff=%d "
                        + "recovery_holes_cutoff=%d%n",
                configuration.seed(),
                configuration.games(),
                configuration.maxPieces(),
                FAILURE_HISTORY_STATES,
                HEALTHY_CONTROL_TAIL_STATES,
                RecoveryReserveValidationApplication.RECOVERY_HEADROOM_CUTOFF,
                RecoveryReserveValidationApplication.RECOVERY_HOLES_CUTOFF);
    }

    private static void printValidationSummary(
            List<Sample> samples,
            RunSummary runSummary) {
        List<Sample> healthy = source(samples, Source.HEALTHY_RUNTIME);
        List<Sample> failed = source(samples, Source.FAILED_RUNTIME);

        long healthyWarnings = warningCount(healthy);
        long healthyWarningGames = healthy.stream()
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
        int failedMissedGames = runSummary.failedGames - failedDetectedGames;
        int minLead = leadTimes.stream().mapToInt(Integer::intValue).min().orElse(-1);
        int maxLead = leadTimes.stream().mapToInt(Integer::intValue).max().orElse(-1);
        double averageLead = leadTimes.stream()
                .mapToInt(Integer::intValue)
                .average()
                .orElse(-1.0);

        System.out.printf(
                Locale.ROOT,
                "# recovery_reserve_runtime_validation_result "
                        + "healthy_games=%d failed_games=%d "
                        + "healthy_warning_samples=%d healthy_samples=%d "
                        + "healthy_warning_rate=%.6f healthy_warning_games=%d "
                        + "failed_detected_games=%d failed_missed_games=%d "
                        + "min_lead_time=%d avg_lead_time=%.3f max_lead_time=%d%n",
                runSummary.healthyGames,
                runSummary.failedGames,
                healthyWarnings,
                healthy.size(),
                healthy.isEmpty() ? 0.0 : (double) healthyWarnings / healthy.size(),
                healthyWarningGames,
                failedDetectedGames,
                failedMissedGames,
                minLead,
                averageLead,
                maxLead);
    }

    private static List<Sample> source(List<Sample> samples, Source source) {
        return samples.stream()
                .filter(sample -> sample.source() == source)
                .toList();
    }

    private static long warningCount(List<Sample> samples) {
        return samples.stream()
                .filter(sample -> warning(sample.probe()))
                .count();
    }

    enum Source {
        HEALTHY_RUNTIME("healthy-runtime"),
        FAILED_RUNTIME("failed-runtime");

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
                throw new IllegalArgumentException(
                        "invalid runtime recovery validation sample");
            }
            if (source == Source.FAILED_RUNTIME && distanceToFailure < 0) {
                throw new IllegalArgumentException(
                        "failed runtime samples require failure distance");
            }
            if (source != Source.FAILED_RUNTIME && distanceToFailure != -1) {
                throw new IllegalArgumentException(
                        "healthy runtime samples must not have failure distance");
            }
        }
    }

    private static final class RunSummary {
        private int healthyGames;
        private int failedGames;
    }

    private record Configuration(
            int games,
            int maxPieces,
            long seed) {

        static Configuration fromEnvironment() {
            return new Configuration(
                    positiveInt(GAMES_ENV, DEFAULT_GAMES),
                    positiveInt(MAX_PIECES_ENV, DEFAULT_MAX_PIECES),
                    longValue(SEED_ENV, DEFAULT_SEED));
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
