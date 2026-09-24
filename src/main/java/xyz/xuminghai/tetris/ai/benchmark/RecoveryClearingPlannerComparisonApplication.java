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
 * Paired deterministic comparison of production BUILD_SHAPE against first-clearing recovery
 * planning.
 *
 * <p>Both arms consume the same seeded 7-bag sequence. The recovery arm changes only warned
 * SURVIVAL-rank-1 decisions: it probes lower-ranked reachable actions in the existing SURVIVAL
 * order and applies the first candidate that clears the already-frozen warning. No candidate is
 * selected by a new score and no production runtime class is changed.</p>
 */
public final class RecoveryClearingPlannerComparisonApplication {

    static final String GAMES_ENV = "TETRIS_BENCHMARK_GAMES";
    static final String MAX_PIECES_ENV = "TETRIS_BENCHMARK_MAX_PIECES";
    static final String SEED_ENV = "TETRIS_BENCHMARK_SEED";

    private static final int DEFAULT_GAMES = 30;
    private static final int DEFAULT_MAX_PIECES = 1000;
    private static final long DEFAULT_SEED = 7000L;

    private RecoveryClearingPlannerComparisonApplication() {
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
                            ShapeTarget.HEART.progress(
                                    turn.selected().resultingBoard())));

            RecoveryTelemetry telemetry =
                    new RecoveryTelemetry(seed, decisionTraces);
            ShapeSummary recoveryShape = new ShapeSummary();
            GameBenchmarkResult recovery = runner.runPlanningObserved(
                    seed,
                    configuration.maxPieces(),
                    new RecoveryClearingPlanningAgent(
                            ShapeTarget.HEART,
                            telemetry::record),
                    null,
                    turn -> recoveryShape.record(
                            ShapeTarget.HEART.progress(
                                    turn.selected().resultingBoard())));

            comparisons.add(new GameComparison(
                    seed,
                    baseline,
                    recovery,
                    baselineShape.snapshot(),
                    recoveryShape.snapshot(),
                    telemetry.snapshot()));
        }

        printDecisions(decisionTraces);
        printGames(comparisons);
        printProtocol(configuration);
        printSummary(comparisons);
    }

    private static void printDecisions(List<DecisionTrace> traces) {
        System.out.println(
                "recovery_clearing_decision,seed,decision,warning,warning_reason,"
                        + "plan_replaced,applied_survival_rank,candidates_probed,"
                        + "reachable_candidates,build_shape_visual_error,"
                        + "applied_visual_error,visual_error_delta,"
                        + "detection_probe_ms,search_ms,recovery_overhead_ms");
        for (DecisionTrace trace : traces) {
            RecoveryClearingPlanningAgent.Observation observation =
                    trace.observation();
            if (!observation.warning()) {
                continue;
            }
            System.out.printf(
                    Locale.ROOT,
                    "recovery_clearing_decision,%d,%d,%s,%s,%s,%d,%d,%d,"
                            + "%d,%d,%d,%.6f,%.6f,%.6f%n",
                    trace.seed(),
                    trace.decision(),
                    observation.warning(),
                    observation.warningReason(),
                    observation.planReplaced(),
                    observation.appliedSurvivalRank(),
                    observation.candidatesProbed(),
                    observation.reachableCandidateCount(),
                    observation.buildShapeProgress().visualErrorCells(),
                    observation.appliedProgress().visualErrorCells(),
                    observation.appliedVisualErrorDelta(),
                    nanosToMillis(observation.detectionProbeNanos()),
                    nanosToMillis(observation.searchNanos()),
                    nanosToMillis(observation.recoveryOverheadNanos()));
        }
    }

    private static void printGames(List<GameComparison> comparisons) {
        System.out.println(
                "recovery_clearing_game,seed,baseline_pieces,recovery_pieces,piece_delta,"
                        + "outcome,baseline_reached_limit,recovery_reached_limit,"
                        + "evaluated_rank1_decisions,warning_decisions,plan_replacements,"
                        + "unresolved_warnings,avg_replacement_rank,avg_candidates_probed_per_warning,"
                        + "baseline_best_visual_error,recovery_best_visual_error,"
                        + "best_visual_error_delta,baseline_clean_completion,recovery_clean_completion,"
                        + "baseline_avg_decision_ms,recovery_avg_decision_ms,"
                        + "decision_latency_delta_ms,avg_recovery_overhead_ms,"
                        + "max_recovery_overhead_ms");
        for (GameComparison comparison : comparisons) {
            System.out.println(formatGame(comparison));
        }
    }

    static String formatGame(GameComparison comparison) {
        Objects.requireNonNull(comparison, "comparison");
        int pieceDelta = comparison.recovery().piecesPlaced()
                - comparison.baseline().piecesPlaced();
        return String.format(
                Locale.ROOT,
                "recovery_clearing_game,%d,%d,%d,%d,%s,%s,%s,%d,%d,%d,%d,"
                        + "%.3f,%.3f,%d,%d,%d,%s,%s,%.6f,%.6f,%.6f,%.6f,%.6f",
                comparison.seed(),
                comparison.baseline().piecesPlaced(),
                comparison.recovery().piecesPlaced(),
                pieceDelta,
                outcome(pieceDelta),
                comparison.baseline().reachedPieceLimit(),
                comparison.recovery().reachedPieceLimit(),
                comparison.telemetry().evaluatedRank1Decisions(),
                comparison.telemetry().warningDecisions(),
                comparison.telemetry().planReplacements(),
                comparison.telemetry().unresolvedWarnings(),
                comparison.telemetry().averageReplacementRank(),
                comparison.telemetry().averageCandidatesProbedPerWarning(),
                comparison.baselineShape().bestVisualError(),
                comparison.recoveryShape().bestVisualError(),
                comparison.recoveryShape().bestVisualError()
                        - comparison.baselineShape().bestVisualError(),
                comparison.baselineShape().cleanCompletion(),
                comparison.recoveryShape().cleanCompletion(),
                comparison.baseline().averageDecisionMillis(),
                comparison.recovery().averageDecisionMillis(),
                comparison.recovery().averageDecisionMillis()
                        - comparison.baseline().averageDecisionMillis(),
                comparison.telemetry().averageRecoveryOverheadMillis(),
                comparison.telemetry().maxRecoveryOverheadMillis());
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
                "# recovery_clearing_protocol seed_start=%d games=%d max_pieces=%d "
                        + "baseline=production_build_shape "
                        + "recovery_policy=first_frozen_warning_clearing_survival_rank "
                        + "search_start_rank=2 recovery_headroom_cutoff=%d "
                        + "recovery_holes_cutoff=%d%n",
                configuration.seed(),
                configuration.games(),
                configuration.maxPieces(),
                RecoveryReserveValidationApplication.RECOVERY_HEADROOM_CUTOFF,
                RecoveryReserveValidationApplication.RECOVERY_HOLES_CUTOFF);
    }

    private static void printSummary(List<GameComparison> comparisons) {
        int baselineSurvived = 0;
        int recoverySurvived = 0;
        int improved = 0;
        int regressed = 0;
        int tied = 0;
        int interventionGames = 0;
        long totalPieceDelta = 0L;
        long evaluatedRank1Decisions = 0L;
        long warningDecisions = 0L;
        long planReplacements = 0L;
        long unresolvedWarnings = 0L;
        long candidatesProbed = 0L;
        long replacementRankSum = 0L;
        int baselineClean = 0;
        int recoveryClean = 0;
        long baselineBestVisualError = 0L;
        long recoveryBestVisualError = 0L;
        long baselineDecisionNanos = 0L;
        long recoveryDecisionNanos = 0L;
        long baselineDecisions = 0L;
        long recoveryDecisions = 0L;
        long recoveryOverheadSamples = 0L;
        long totalRecoveryOverheadNanos = 0L;
        long maxRecoveryOverheadNanos = 0L;

        for (GameComparison comparison : comparisons) {
            if (comparison.baseline().reachedPieceLimit()) {
                baselineSurvived++;
            }
            if (comparison.recovery().reachedPieceLimit()) {
                recoverySurvived++;
            }

            int pieceDelta = comparison.recovery().piecesPlaced()
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

            RecoverySummary telemetry = comparison.telemetry();
            evaluatedRank1Decisions += telemetry.evaluatedRank1Decisions();
            warningDecisions += telemetry.warningDecisions();
            planReplacements += telemetry.planReplacements();
            unresolvedWarnings += telemetry.unresolvedWarnings();
            candidatesProbed += telemetry.candidatesProbed();
            replacementRankSum += telemetry.replacementRankSum();
            recoveryOverheadSamples += telemetry.recoveryOverheadSamples();
            totalRecoveryOverheadNanos += telemetry.totalRecoveryOverheadNanos();
            maxRecoveryOverheadNanos = Math.max(
                    maxRecoveryOverheadNanos,
                    telemetry.maxRecoveryOverheadNanos());
            if (telemetry.planReplacements() > 0) {
                interventionGames++;
            }

            baselineBestVisualError += comparison.baselineShape().bestVisualError();
            recoveryBestVisualError += comparison.recoveryShape().bestVisualError();
            if (comparison.baselineShape().cleanCompletion()) {
                baselineClean++;
            }
            if (comparison.recoveryShape().cleanCompletion()) {
                recoveryClean++;
            }

            baselineDecisionNanos += comparison.baseline().totalDecisionNanos();
            recoveryDecisionNanos += comparison.recovery().totalDecisionNanos();
            baselineDecisions += comparison.baseline().decisions();
            recoveryDecisions += comparison.recovery().decisions();
        }

        double games = comparisons.size();
        double baselineAverageDecisionMillis = baselineDecisions == 0
                ? 0.0
                : nanosToMillis(baselineDecisionNanos) / baselineDecisions;
        double recoveryAverageDecisionMillis = recoveryDecisions == 0
                ? 0.0
                : nanosToMillis(recoveryDecisionNanos) / recoveryDecisions;
        double averageReplacementRank = planReplacements == 0
                ? -1.0
                : (double) replacementRankSum / planReplacements;
        double averageCandidatesProbed = warningDecisions == 0
                ? 0.0
                : (double) candidatesProbed / warningDecisions;
        double averageRecoveryOverheadMillis = recoveryOverheadSamples == 0
                ? 0.0
                : nanosToMillis(totalRecoveryOverheadNanos)
                        / recoveryOverheadSamples;

        System.out.printf(
                Locale.ROOT,
                "# recovery_clearing_result games=%d "
                        + "baseline_survived=%d recovery_survived=%d "
                        + "improved_games=%d regressed_games=%d tied_games=%d "
                        + "intervention_games=%d total_piece_delta=%d avg_piece_delta=%.3f "
                        + "evaluated_rank1_decisions=%d warning_decisions=%d "
                        + "plan_replacements=%d unresolved_warnings=%d "
                        + "avg_replacement_rank=%.3f avg_candidates_probed_per_warning=%.3f "
                        + "baseline_avg_best_visual_error=%.3f "
                        + "recovery_avg_best_visual_error=%.3f "
                        + "baseline_clean_games=%d recovery_clean_games=%d "
                        + "baseline_avg_decision_ms=%.6f recovery_avg_decision_ms=%.6f "
                        + "decision_latency_delta_ms=%.6f "
                        + "avg_recovery_overhead_ms=%.6f max_recovery_overhead_ms=%.6f%n",
                comparisons.size(),
                baselineSurvived,
                recoverySurvived,
                improved,
                regressed,
                tied,
                interventionGames,
                totalPieceDelta,
                games == 0.0 ? 0.0 : totalPieceDelta / games,
                evaluatedRank1Decisions,
                warningDecisions,
                planReplacements,
                unresolvedWarnings,
                averageReplacementRank,
                averageCandidatesProbed,
                games == 0.0 ? 0.0 : baselineBestVisualError / games,
                games == 0.0 ? 0.0 : recoveryBestVisualError / games,
                baselineClean,
                recoveryClean,
                baselineAverageDecisionMillis,
                recoveryAverageDecisionMillis,
                recoveryAverageDecisionMillis - baselineAverageDecisionMillis,
                averageRecoveryOverheadMillis,
                nanosToMillis(maxRecoveryOverheadNanos));
    }

    private static double nanosToMillis(long nanos) {
        return nanos / 1_000_000.0;
    }

    record GameComparison(
            long seed,
            GameBenchmarkResult baseline,
            GameBenchmarkResult recovery,
            ShapeSummarySnapshot baselineShape,
            ShapeSummarySnapshot recoveryShape,
            RecoverySummary telemetry) {

        GameComparison {
            Objects.requireNonNull(baseline, "baseline");
            Objects.requireNonNull(recovery, "recovery");
            Objects.requireNonNull(baselineShape, "baselineShape");
            Objects.requireNonNull(recoveryShape, "recoveryShape");
            Objects.requireNonNull(telemetry, "telemetry");
            if (baseline.seed() != seed || recovery.seed() != seed) {
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

    record RecoverySummary(
            int evaluatedRank1Decisions,
            int warningDecisions,
            int planReplacements,
            int unresolvedWarnings,
            long candidatesProbed,
            long replacementRankSum,
            long recoveryOverheadSamples,
            long totalRecoveryOverheadNanos,
            long maxRecoveryOverheadNanos) {

        RecoverySummary {
            if (evaluatedRank1Decisions < 0
                    || warningDecisions < 0
                    || planReplacements < 0
                    || unresolvedWarnings < 0
                    || candidatesProbed < 0
                    || replacementRankSum < 0
                    || recoveryOverheadSamples < 0
                    || totalRecoveryOverheadNanos < 0
                    || maxRecoveryOverheadNanos < 0
                    || warningDecisions > evaluatedRank1Decisions
                    || planReplacements + unresolvedWarnings != warningDecisions
                    || recoveryOverheadSamples != evaluatedRank1Decisions) {
                throw new IllegalArgumentException(
                        "invalid recovery planner summary");
            }
        }

        double averageReplacementRank() {
            return planReplacements == 0
                    ? -1.0
                    : (double) replacementRankSum / planReplacements;
        }

        double averageCandidatesProbedPerWarning() {
            return warningDecisions == 0
                    ? 0.0
                    : (double) candidatesProbed / warningDecisions;
        }

        double averageRecoveryOverheadMillis() {
            return recoveryOverheadSamples == 0
                    ? 0.0
                    : nanosToMillis(totalRecoveryOverheadNanos)
                            / recoveryOverheadSamples;
        }

        double maxRecoveryOverheadMillis() {
            return nanosToMillis(maxRecoveryOverheadNanos);
        }
    }

    private static final class ShapeSummary {
        private int bestVisualError = ShapeTarget.HEART.requiredCells();
        private boolean cleanCompletion;

        void record(ShapeProgress progress) {
            Objects.requireNonNull(progress, "progress");
            bestVisualError = Math.min(
                    bestVisualError,
                    progress.visualErrorCells());
            cleanCompletion |= progress.cleanCompletion();
        }

        ShapeSummarySnapshot snapshot() {
            return new ShapeSummarySnapshot(
                    bestVisualError,
                    cleanCompletion);
        }
    }

    private static final class RecoveryTelemetry {
        private final long seed;
        private final List<DecisionTrace> traces;
        private int decision;
        private int evaluatedRank1Decisions;
        private int warningDecisions;
        private int planReplacements;
        private int unresolvedWarnings;
        private long candidatesProbed;
        private long replacementRankSum;
        private long recoveryOverheadSamples;
        private long totalRecoveryOverheadNanos;
        private long maxRecoveryOverheadNanos;

        private RecoveryTelemetry(
                long seed,
                List<DecisionTrace> traces) {
            this.seed = seed;
            this.traces = Objects.requireNonNull(traces, "traces");
        }

        void record(RecoveryClearingPlanningAgent.Observation observation) {
            Objects.requireNonNull(observation, "observation");
            decision++;

            if (observation.recoveryEvaluated()) {
                evaluatedRank1Decisions++;
                recoveryOverheadSamples++;
                totalRecoveryOverheadNanos += observation.recoveryOverheadNanos();
                maxRecoveryOverheadNanos = Math.max(
                        maxRecoveryOverheadNanos,
                        observation.recoveryOverheadNanos());
            }

            if (observation.warning()) {
                warningDecisions++;
                candidatesProbed += observation.candidatesProbed();
                if (observation.planReplaced()) {
                    planReplacements++;
                    replacementRankSum += observation.appliedSurvivalRank();
                }
                else {
                    unresolvedWarnings++;
                }
            }

            traces.add(new DecisionTrace(
                    seed,
                    decision,
                    observation));
        }

        RecoverySummary snapshot() {
            return new RecoverySummary(
                    evaluatedRank1Decisions,
                    warningDecisions,
                    planReplacements,
                    unresolvedWarnings,
                    candidatesProbed,
                    replacementRankSum,
                    recoveryOverheadSamples,
                    totalRecoveryOverheadNanos,
                    maxRecoveryOverheadNanos);
        }
    }

    record DecisionTrace(
            long seed,
            int decision,
            RecoveryClearingPlanningAgent.Observation observation) {

        DecisionTrace {
            Objects.requireNonNull(observation, "observation");
            if (decision <= 0) {
                throw new IllegalArgumentException(
                        "decision must be positive");
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

        private static int positiveInt(
                String name,
                int defaultValue) {
            String value = System.getenv(name);
            if (value == null || value.isBlank()) {
                return defaultValue;
            }
            int parsed = Integer.parseInt(value);
            if (parsed <= 0) {
                throw new IllegalArgumentException(
                        name + " must be greater than 0");
            }
            return parsed;
        }

        private static long longValue(
                String name,
                long defaultValue) {
            String value = System.getenv(name);
            return value == null || value.isBlank()
                    ? defaultValue
                    : Long.parseLong(value);
        }
    }
}
