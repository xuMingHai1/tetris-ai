/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai.benchmark;

import xyz.xuminghai.tetris.ai.BuildShapeActionPlanningAgent;
import xyz.xuminghai.tetris.ai.ShapeProgress;
import xyz.xuminghai.tetris.ai.ShapeTarget;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Paired deterministic comparison of production BUILD_SHAPE against a benchmark-only reversible
 * recovery intervention.
 *
 * <p>Both arms use the same seeded 7-bag sequence and production rules. The intervention arm keeps
 * BUILD_SHAPE unchanged except for a single decision whose selected resulting board triggers the
 * frozen recovery-reserve warning; that decision is replaced by the existing deterministic
 * SURVIVAL top-1 plan. No threshold is tuned and no behavior is added to the production planner.</p>
 */
public final class RecoveryInterventionComparisonApplication {

    static final String GAMES_ENV = "TETRIS_BENCHMARK_GAMES";
    static final String MAX_PIECES_ENV = "TETRIS_BENCHMARK_MAX_PIECES";
    static final String SEED_ENV = "TETRIS_BENCHMARK_SEED";

    private static final int DEFAULT_GAMES = 30;
    private static final int DEFAULT_MAX_PIECES = 1000;
    private static final long DEFAULT_SEED = 5000L;

    private RecoveryInterventionComparisonApplication() {
    }

    public static void main(String[] args) {
        Configuration configuration = Configuration.fromEnvironment();
        HeadlessGameRunner runner = new HeadlessGameRunner();
        List<GameComparison> comparisons = new ArrayList<>();
        List<DecisionTrace> decisionTraces = new ArrayList<>();

        for (int game = 0; game < configuration.games(); game++) {
            long seed = configuration.seed() + game;

            ShapeSummary baselineShape = new ShapeSummary();
            GameBenchmarkResult baseline = runner.runPlanningObserved(
                    seed,
                    configuration.maxPieces(),
                    new BuildShapeActionPlanningAgent(),
                    null,
                    turn -> baselineShape.record(
                            ShapeTarget.HEART.progress(turn.selected().resultingBoard())));

            InterventionTelemetry telemetry = new InterventionTelemetry(seed, decisionTraces);
            ShapeSummary interventionShape = new ShapeSummary();
            GameBenchmarkResult intervention = runner.runPlanningObserved(
                    seed,
                    configuration.maxPieces(),
                    new RecoveryInterventionPlanningAgent(
                            ShapeTarget.HEART,
                            telemetry::record),
                    null,
                    turn -> interventionShape.record(
                            ShapeTarget.HEART.progress(turn.selected().resultingBoard())));

            comparisons.add(new GameComparison(
                    seed,
                    baseline,
                    intervention,
                    baselineShape.snapshot(),
                    interventionShape.snapshot(),
                    telemetry.snapshot()));
        }

        printDecisions(decisionTraces);
        printGames(comparisons);
        printProtocol(configuration);
        printSummary(comparisons);
    }

    private static void printDecisions(List<DecisionTrace> traces) {
        System.out.println(
                "recovery_intervention_decision,seed,decision,warning,warning_reason,"
                        + "build_shape_selected_rank,build_shape_objective_applied,plan_replaced,"
                        + "warning_after_survival,warning_cleared_by_survival,"
                        + "build_shape_visual_error,applied_visual_error,"
                        + "applied_visual_error_delta,probe_ms,verification_probe_ms");
        for (DecisionTrace trace : traces) {
            RecoveryInterventionPlanningAgent.Observation observation = trace.observation();
            if (!observation.warning()) {
                continue;
            }
            System.out.printf(
                    Locale.ROOT,
                    "recovery_intervention_decision,%d,%d,%s,%s,%d,%s,%s,%s,%s,"
                            + "%d,%d,%d,%.6f,%.6f%n",
                    trace.seed(),
                    trace.decision(),
                    observation.warning(),
                    observation.warningReason(),
                    observation.buildShapeSelectedRank(),
                    observation.buildShapeObjectiveApplied(),
                    observation.planReplaced(),
                    observation.warningAfterSurvival(),
                    observation.warningClearedBySurvival(),
                    observation.buildShapeProgress().visualErrorCells(),
                    observation.appliedProgress().visualErrorCells(),
                    observation.appliedVisualErrorDelta(),
                    nanosToMillis(observation.probeNanos()),
                    nanosToMillis(observation.verificationProbeNanos()));
        }
    }

    private static void printGames(List<GameComparison> comparisons) {
        System.out.println(
                "recovery_intervention_game,seed,baseline_pieces,intervention_pieces,piece_delta,"
                        + "outcome,baseline_reached_limit,intervention_reached_limit,"
                        + "warning_decisions,plan_replacements,warning_cleared_by_survival,"
                        + "warning_still_after_survival,warning_already_survival,"
                        + "baseline_best_visual_error,intervention_best_visual_error,"
                        + "best_visual_error_delta,baseline_clean_completion,"
                        + "intervention_clean_completion,avg_probe_ms,max_probe_ms");
        for (GameComparison comparison : comparisons) {
            System.out.println(formatGame(comparison));
        }
    }

    static String formatGame(GameComparison comparison) {
        Objects.requireNonNull(comparison, "comparison");
        int pieceDelta = comparison.intervention().piecesPlaced()
                - comparison.baseline().piecesPlaced();
        return String.format(
                Locale.ROOT,
                "recovery_intervention_game,%d,%d,%d,%d,%s,%s,%s,%d,%d,%d,%d,%d,"
                        + "%d,%d,%d,%s,%s,%.6f,%.6f",
                comparison.seed(),
                comparison.baseline().piecesPlaced(),
                comparison.intervention().piecesPlaced(),
                pieceDelta,
                outcome(pieceDelta),
                comparison.baseline().reachedPieceLimit(),
                comparison.intervention().reachedPieceLimit(),
                comparison.telemetry().warningDecisions(),
                comparison.telemetry().planReplacements(),
                comparison.telemetry().warningsClearedBySurvival(),
                comparison.telemetry().warningsStillAfterSurvival(),
                comparison.telemetry().warningAlreadySurvival(),
                comparison.baselineShape().bestVisualError(),
                comparison.interventionShape().bestVisualError(),
                comparison.interventionShape().bestVisualError()
                        - comparison.baselineShape().bestVisualError(),
                comparison.baselineShape().cleanCompletion(),
                comparison.interventionShape().cleanCompletion(),
                comparison.telemetry().averageProbeMillis(),
                comparison.telemetry().maxProbeMillis());
    }

    static String outcome(int pieceDelta) {
        if (pieceDelta > 0) {
            return "improved";
        }
        if (pieceDelta < 0) {
            return "regressed";
        }
        return "tied";
    }

    private static void printProtocol(Configuration configuration) {
        System.out.printf(
                Locale.ROOT,
                "# recovery_intervention_protocol seed_start=%d games=%d max_pieces=%d "
                        + "baseline=production_build_shape intervention=single_decision_survival_top1 "
                        + "recovery_headroom_cutoff=%d recovery_holes_cutoff=%d%n",
                configuration.seed(),
                configuration.games(),
                configuration.maxPieces(),
                RecoveryReserveValidationApplication.RECOVERY_HEADROOM_CUTOFF,
                RecoveryReserveValidationApplication.RECOVERY_HOLES_CUTOFF);
    }

    private static void printSummary(List<GameComparison> comparisons) {
        int baselineSurvived = 0;
        int interventionSurvived = 0;
        int improved = 0;
        int regressed = 0;
        int tied = 0;
        long totalPieceDelta = 0L;
        long warningDecisions = 0L;
        long planReplacements = 0L;
        long warningsCleared = 0L;
        long warningsStill = 0L;
        long warningAlreadySurvival = 0L;
        int baselineClean = 0;
        int interventionClean = 0;
        long baselineBestVisualError = 0L;
        long interventionBestVisualError = 0L;
        long probeSamples = 0L;
        long totalProbeNanos = 0L;
        long maxProbeNanos = 0L;

        for (GameComparison comparison : comparisons) {
            if (comparison.baseline().reachedPieceLimit()) {
                baselineSurvived++;
            }
            if (comparison.intervention().reachedPieceLimit()) {
                interventionSurvived++;
            }

            int pieceDelta = comparison.intervention().piecesPlaced()
                    - comparison.baseline().piecesPlaced();
            if (pieceDelta > 0) {
                improved++;
            }
            else if (pieceDelta < 0) {
                regressed++;
            }
            else {
                tied++;
            }
            totalPieceDelta += pieceDelta;

            InterventionSummary telemetry = comparison.telemetry();
            warningDecisions += telemetry.warningDecisions();
            planReplacements += telemetry.planReplacements();
            warningsCleared += telemetry.warningsClearedBySurvival();
            warningsStill += telemetry.warningsStillAfterSurvival();
            warningAlreadySurvival += telemetry.warningAlreadySurvival();
            probeSamples += telemetry.probeSamples();
            totalProbeNanos += telemetry.totalProbeNanos();
            maxProbeNanos = Math.max(maxProbeNanos, telemetry.maxProbeNanos());

            baselineBestVisualError += comparison.baselineShape().bestVisualError();
            interventionBestVisualError += comparison.interventionShape().bestVisualError();
            if (comparison.baselineShape().cleanCompletion()) {
                baselineClean++;
            }
            if (comparison.interventionShape().cleanCompletion()) {
                interventionClean++;
            }
        }

        double games = comparisons.size();
        double averageProbeMillis = probeSamples == 0
                ? 0.0
                : nanosToMillis(totalProbeNanos) / probeSamples;

        System.out.printf(
                Locale.ROOT,
                "# recovery_intervention_result games=%d "
                        + "baseline_survived=%d intervention_survived=%d "
                        + "improved_games=%d regressed_games=%d tied_games=%d "
                        + "total_piece_delta=%d avg_piece_delta=%.3f "
                        + "warning_decisions=%d plan_replacements=%d "
                        + "warnings_cleared_by_survival=%d warnings_still_after_survival=%d "
                        + "warning_already_survival=%d "
                        + "baseline_avg_best_visual_error=%.3f "
                        + "intervention_avg_best_visual_error=%.3f "
                        + "baseline_clean_games=%d intervention_clean_games=%d "
                        + "avg_probe_ms=%.6f max_probe_ms=%.6f%n",
                comparisons.size(),
                baselineSurvived,
                interventionSurvived,
                improved,
                regressed,
                tied,
                totalPieceDelta,
                games == 0.0 ? 0.0 : totalPieceDelta / games,
                warningDecisions,
                planReplacements,
                warningsCleared,
                warningsStill,
                warningAlreadySurvival,
                games == 0.0 ? 0.0 : baselineBestVisualError / games,
                games == 0.0 ? 0.0 : interventionBestVisualError / games,
                baselineClean,
                interventionClean,
                averageProbeMillis,
                nanosToMillis(maxProbeNanos));
    }

    private static double nanosToMillis(long nanos) {
        return nanos / 1_000_000.0;
    }

    record GameComparison(
            long seed,
            GameBenchmarkResult baseline,
            GameBenchmarkResult intervention,
            ShapeSummarySnapshot baselineShape,
            ShapeSummarySnapshot interventionShape,
            InterventionSummary telemetry) {

        GameComparison {
            Objects.requireNonNull(baseline, "baseline");
            Objects.requireNonNull(intervention, "intervention");
            Objects.requireNonNull(baselineShape, "baselineShape");
            Objects.requireNonNull(interventionShape, "interventionShape");
            Objects.requireNonNull(telemetry, "telemetry");
            if (baseline.seed() != seed || intervention.seed() != seed) {
                throw new IllegalArgumentException(
                        "paired comparison must use the same seed");
            }
        }
    }

    record ShapeSummarySnapshot(
            int bestVisualError,
            boolean cleanCompletion) {

        ShapeSummarySnapshot {
            if (bestVisualError < 0) {
                throw new IllegalArgumentException(
                        "bestVisualError must not be negative");
            }
        }
    }

    record InterventionSummary(
            int warningDecisions,
            int planReplacements,
            int warningsClearedBySurvival,
            int warningsStillAfterSurvival,
            int warningAlreadySurvival,
            long probeSamples,
            long totalProbeNanos,
            long maxProbeNanos) {

        InterventionSummary {
            if (warningDecisions < 0
                    || planReplacements < 0
                    || warningsClearedBySurvival < 0
                    || warningsStillAfterSurvival < 0
                    || warningAlreadySurvival < 0
                    || probeSamples < 0
                    || totalProbeNanos < 0
                    || maxProbeNanos < 0) {
                throw new IllegalArgumentException(
                        "intervention summary values must not be negative");
            }
            if (planReplacements > warningDecisions
                    || warningsClearedBySurvival + warningsStillAfterSurvival
                            != warningDecisions
                    || warningAlreadySurvival > warningDecisions
                    || probeSamples < warningDecisions) {
                throw new IllegalArgumentException(
                        "intervention summary counts are inconsistent");
            }
        }

        double averageProbeMillis() {
            return probeSamples == 0
                    ? 0.0
                    : nanosToMillis(totalProbeNanos) / probeSamples;
        }

        double maxProbeMillis() {
            return nanosToMillis(maxProbeNanos);
        }
    }

    private static final class ShapeSummary {
        private int bestVisualError = ShapeTarget.HEART.requiredCells();
        private boolean cleanCompletion;

        void record(ShapeProgress progress) {
            Objects.requireNonNull(progress, "progress");
            bestVisualError = Math.min(bestVisualError, progress.visualErrorCells());
            cleanCompletion |= progress.cleanCompletion();
        }

        ShapeSummarySnapshot snapshot() {
            return new ShapeSummarySnapshot(bestVisualError, cleanCompletion);
        }
    }

    private static final class InterventionTelemetry {
        private final long seed;
        private final List<DecisionTrace> traces;
        private int decision;
        private int warningDecisions;
        private int planReplacements;
        private int warningsClearedBySurvival;
        private int warningsStillAfterSurvival;
        private int warningAlreadySurvival;
        private long probeSamples;
        private long totalProbeNanos;
        private long maxProbeNanos;

        private InterventionTelemetry(
                long seed,
                List<DecisionTrace> traces) {
            this.seed = seed;
            this.traces = Objects.requireNonNull(traces, "traces");
        }

        void record(RecoveryInterventionPlanningAgent.Observation observation) {
            Objects.requireNonNull(observation, "observation");
            decision++;
            probeSamples++;
            totalProbeNanos += observation.probeNanos();
            maxProbeNanos = Math.max(maxProbeNanos, observation.probeNanos());

            if (observation.warning()) {
                warningDecisions++;
                if (observation.planReplaced()) {
                    planReplacements++;
                }
                else {
                    warningAlreadySurvival++;
                }
                if (observation.warningClearedBySurvival()) {
                    warningsClearedBySurvival++;
                }
                else {
                    warningsStillAfterSurvival++;
                }
            }
            traces.add(new DecisionTrace(seed, decision, observation));
        }

        InterventionSummary snapshot() {
            return new InterventionSummary(
                    warningDecisions,
                    planReplacements,
                    warningsClearedBySurvival,
                    warningsStillAfterSurvival,
                    warningAlreadySurvival,
                    probeSamples,
                    totalProbeNanos,
                    maxProbeNanos);
        }
    }

    record DecisionTrace(
            long seed,
            int decision,
            RecoveryInterventionPlanningAgent.Observation observation) {

        DecisionTrace {
            Objects.requireNonNull(observation, "observation");
            if (decision <= 0) {
                throw new IllegalArgumentException("decision must be positive");
            }
        }
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
