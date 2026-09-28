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
 * Paired whole-game evaluation of strict and preview-only benchmark rescue arms.
 *
 * <p>Unrecoverable preview after rank 1 implies baseline continuation depth zero; selecting an
 * alternative that makes that exact preview playable implies at least one more placement. The
 * old 5/5 one-shot positive depth labels therefore do not establish a learned discriminator.
 * This experiment compares both eligibility rules against the same baseline on fresh 7-bag seeds,
 * including survival, construction impact, and rank-1 checking cost.</p>
 */
public final class PreviewRescuePairedApplication {

    private static final int DEFAULT_GAMES = 120;
    private static final int DEFAULT_MAX_PIECES = 1000;
    private static final long DEFAULT_SEED = 11000L;

    private PreviewRescuePairedApplication() {
    }

    public static void main(String[] args) {
        int games = positiveInt("TETRIS_BENCHMARK_GAMES", DEFAULT_GAMES);
        int maxPieces = positiveInt("TETRIS_BENCHMARK_MAX_PIECES", DEFAULT_MAX_PIECES);
        long firstSeed = seedValue("TETRIS_BENCHMARK_SEED", DEFAULT_SEED);
        HeadlessGameRunner runner = new HeadlessGameRunner();
        List<GameComparison> comparisons = new ArrayList<>();
        List<DecisionTrace> traces = new ArrayList<>();

        for (int index = 0; index < games; index++) {
            long seed = firstSeed + index;
            ShapeSummary baselineShape = new ShapeSummary();
            GameBenchmarkResult baseline = runner.runPlanningObserved(
                    seed, maxPieces, new BuildShapeActionPlanningAgent(), null,
                    turn -> baselineShape.record(
                            ShapeTarget.HEART.progress(turn.selected().resultingBoard())));

            for (PreviewRescuePlanningAgent.Mode mode : PreviewRescuePlanningAgent.Mode.values()) {
                Telemetry telemetry = new Telemetry(seed, mode, traces);
                ShapeSummary rescueShape = new ShapeSummary();
                GameBenchmarkResult rescue = runner.runPlanningObserved(
                        seed, maxPieces, new PreviewRescuePlanningAgent(telemetry::record, mode),
                        null, turn -> rescueShape.record(
                                ShapeTarget.HEART.progress(turn.selected().resultingBoard())));
                comparisons.add(new GameComparison(seed, mode, baseline, rescue,
                        baselineShape.bestVisualError, rescueShape.bestVisualError,
                        baselineShape.cleanCompletion, rescueShape.cleanCompletion,
                        telemetry.snapshot()));
            }
        }

        printDecisions(traces);
        printGames(comparisons);
        printSummary(firstSeed, games, maxPieces, comparisons);
    }

    private static void printDecisions(List<DecisionTrace> traces) {
        System.out.println("preview_rescue_decision,seed,mode,decision,replaced,replacement_rank,"
                + "candidates_probed,detection_ms,search_ms,total_overhead_ms");
        for (DecisionTrace trace : traces) {
            PreviewRescuePlanningAgent.Observation o = trace.observation();
            if (!o.previewUnrecoverable()) {
                continue;
            }
            System.out.printf(Locale.ROOT,
                    "preview_rescue_decision,%d,%s,%d,%s,%d,%d,%.6f,%.6f,%.6f%n",
                    trace.seed(), trace.mode(), trace.decision(), o.replaced(), o.replacementRank(),
                    o.candidatesProbed(), millis(o.detectionNanos()),
                    millis(o.searchNanos()), millis(o.overheadNanos()));
        }
    }

    private static void printGames(List<GameComparison> games) {
        System.out.println("preview_rescue_game,seed,mode,baseline_pieces,rescue_pieces,"
                + "piece_delta,baseline_reached_limit,rescue_reached_limit,"
                + "rank1_evaluations,preview_unrecoverable,replacements,"
                + "unresolved,baseline_best_visual_error,rescue_best_visual_error,"
                + "baseline_clean,rescue_clean,baseline_avg_decision_ms,"
                + "rescue_avg_decision_ms,avg_detection_ms,avg_search_per_warning_ms");
        for (GameComparison game : games) {
            TelemetrySnapshot t = game.telemetry();
            System.out.printf(Locale.ROOT,
                    "preview_rescue_game,%d,%s,%d,%d,%d,%s,%s,%d,%d,%d,%d,%d,%d,%s,%s,"
                            + "%.6f,%.6f,%.6f,%.6f%n",
                    game.seed(), game.mode(), game.baseline().piecesPlaced(), game.rescue().piecesPlaced(),
                    game.pieceDelta(), game.baseline().reachedPieceLimit(),
                    game.rescue().reachedPieceLimit(), t.rank1Evaluations(),
                    t.previewUnrecoverable(), t.replacements(), t.unresolved(),
                    game.baselineBestVisualError(), game.rescueBestVisualError(),
                    game.baselineClean(), game.rescueClean(),
                    game.baseline().averageDecisionMillis(),
                    game.rescue().averageDecisionMillis(),
                    t.rank1Evaluations() == 0 ? 0.0
                            : millis(t.detectionNanos()) / t.rank1Evaluations(),
                    t.previewUnrecoverable() == 0 ? 0.0
                            : millis(t.searchNanos()) / t.previewUnrecoverable());
        }
    }

    private static void printSummary(
            long seed, int games, int maxPieces, List<GameComparison> comparisons) {
        System.out.printf(Locale.ROOT,
                "# preview_rescue_protocol seed_start=%d games=%d max_pieces=%d "
                        + "baseline=production_build_shape arms=STRICT,PREVIEW_ONLY "
                        + "trigger=rank1_preview_unrecoverable repeated_rescue=allowed "
                        + "runtime_policy=unchanged%n", seed, games, maxPieces);
        for (PreviewRescuePlanningAgent.Mode mode : PreviewRescuePlanningAgent.Mode.values()) {
            printArmSummary(mode, games, comparisons.stream()
                    .filter(g -> g.mode() == mode).toList());
        }
        int better = 0;
        int worse = 0;
        int extraPieces = 0;
        int visualRegressions = 0;
        for (int index = 0; index < comparisons.size(); index += 2) {
            GameComparison strict = comparisons.get(index);
            GameComparison previewOnly = comparisons.get(index + 1);
            if (strict.seed() != previewOnly.seed()
                    || strict.mode() != PreviewRescuePlanningAgent.Mode.STRICT
                    || previewOnly.mode() != PreviewRescuePlanningAgent.Mode.PREVIEW_ONLY) {
                throw new IllegalStateException("rescue arms must be paired by seed");
            }
            int delta = previewOnly.rescue().piecesPlaced() - strict.rescue().piecesPlaced();
            better += delta > 0 ? 1 : 0;
            worse += delta < 0 ? 1 : 0;
            extraPieces += delta;
            visualRegressions += previewOnly.rescueBestVisualError()
                    > strict.rescueBestVisualError() ? 1 : 0;
        }
        System.out.printf(Locale.ROOT,
                "# preview_rescue_arm_delta preview_only_vs_strict_improved=%d "
                        + "regressed=%d tied=%d total_piece_delta=%d "
                        + "best_visual_regression_games=%d%n",
                better, worse, games - better - worse, extraPieces, visualRegressions);
    }

    private static void printArmSummary(
            PreviewRescuePlanningAgent.Mode mode, int games, List<GameComparison> comparisons) {
        long improved = comparisons.stream().filter(g -> g.pieceDelta() > 0).count();
        long regressed = comparisons.stream().filter(g -> g.pieceDelta() < 0).count();
        long baselineHorizon = comparisons.stream()
                .filter(g -> g.baseline().reachedPieceLimit()).count();
        long rescueHorizon = comparisons.stream()
                .filter(g -> g.rescue().reachedPieceLimit()).count();
        long interventionGames = comparisons.stream()
                .filter(g -> g.telemetry().replacements() > 0).count();
        long replacements = comparisons.stream()
                .mapToInt(g -> g.telemetry().replacements()).sum();
        long warnings = comparisons.stream()
                .mapToInt(g -> g.telemetry().previewUnrecoverable()).sum();
        long evaluated = comparisons.stream()
                .mapToInt(g -> g.telemetry().rank1Evaluations()).sum();
        long detection = comparisons.stream()
                .mapToLong(g -> g.telemetry().detectionNanos()).sum();
        long search = comparisons.stream()
                .mapToLong(g -> g.telemetry().searchNanos()).sum();
        long visualRegressions = comparisons.stream()
                .filter(g -> g.rescueBestVisualError() > g.baselineBestVisualError()).count();
        System.out.printf(Locale.ROOT,
                "# preview_rescue_result mode=%s games=%d baseline_horizon=%d rescue_horizon=%d "
                        + "improved_games=%d regressed_games=%d tied_games=%d "
                        + "intervention_games=%d rank1_evaluations=%d preview_unrecoverable=%d "
                        + "replacements=%d unresolved=%d total_piece_delta=%d "
                        + "avg_piece_delta=%.3f best_visual_regression_games=%d "
                        + "avg_detection_ms=%.6f avg_search_per_warning_ms=%.6f%n",
                mode, games, baselineHorizon, rescueHorizon, improved, regressed,
                games - improved - regressed, interventionGames, evaluated, warnings,
                replacements, warnings - replacements,
                comparisons.stream().mapToInt(GameComparison::pieceDelta).sum(),
                comparisons.stream().mapToInt(GameComparison::pieceDelta).average().orElse(0.0),
                visualRegressions, evaluated == 0 ? 0.0 : millis(detection) / evaluated,
                warnings == 0 ? 0.0 : millis(search) / warnings);
    }

    private static double millis(long nanos) {
        return nanos / 1_000_000.0;
    }

    private static int positiveInt(String name, int fallback) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        int parsed = Integer.parseInt(value);
        if (parsed <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return parsed;
    }

    private static long seedValue(String name, long fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : Long.parseLong(value);
    }

    record GameComparison(
            long seed, PreviewRescuePlanningAgent.Mode mode,
            GameBenchmarkResult baseline, GameBenchmarkResult rescue,
            int baselineBestVisualError, int rescueBestVisualError,
            boolean baselineClean, boolean rescueClean, TelemetrySnapshot telemetry) {
        GameComparison {
            Objects.requireNonNull(baseline, "baseline");
            Objects.requireNonNull(rescue, "rescue");
            Objects.requireNonNull(mode, "mode");
            Objects.requireNonNull(telemetry, "telemetry");
            if (baseline.seed() != seed || rescue.seed() != seed
                    || baselineBestVisualError < 0 || rescueBestVisualError < 0) {
                throw new IllegalArgumentException("invalid paired rescue game");
            }
        }

        int pieceDelta() {
            return rescue.piecesPlaced() - baseline.piecesPlaced();
        }
    }

    record TelemetrySnapshot(
            int rank1Evaluations, int previewUnrecoverable, int replacements,
            int unresolved, long detectionNanos, long searchNanos) {
        TelemetrySnapshot {
            if (rank1Evaluations < 0 || previewUnrecoverable < 0 || replacements < 0
                    || unresolved < 0 || previewUnrecoverable > rank1Evaluations
                    || replacements + unresolved != previewUnrecoverable
                    || detectionNanos < 0 || searchNanos < 0) {
                throw new IllegalArgumentException("invalid rescue telemetry");
            }
        }
    }

    private record DecisionTrace(
            long seed, PreviewRescuePlanningAgent.Mode mode,
            int decision, PreviewRescuePlanningAgent.Observation observation) {
        DecisionTrace {
            Objects.requireNonNull(mode, "mode");
            Objects.requireNonNull(observation, "observation");
            if (decision <= 0) {
                throw new IllegalArgumentException("invalid decision");
            }
        }
    }

    private static final class Telemetry {
        private final long seed;
        private final PreviewRescuePlanningAgent.Mode mode;
        private final List<DecisionTrace> traces;
        private int decision;
        private int evaluated;
        private int warnings;
        private int replacements;
        private long detection;
        private long search;

        Telemetry(long seed, PreviewRescuePlanningAgent.Mode mode, List<DecisionTrace> traces) {
            this.seed = seed;
            this.mode = Objects.requireNonNull(mode, "mode");
            this.traces = Objects.requireNonNull(traces, "traces");
        }

        void record(PreviewRescuePlanningAgent.Observation o) {
            decision++;
            if (o.rank1Evaluated()) {
                evaluated++;
                detection += o.detectionNanos();
            }
            if (o.previewUnrecoverable()) {
                warnings++;
                search += o.searchNanos();
                traces.add(new DecisionTrace(seed, mode, decision, o));
            }
            if (o.replaced()) {
                replacements++;
            }
        }

        TelemetrySnapshot snapshot() {
            return new TelemetrySnapshot(evaluated, warnings, replacements,
                    warnings - replacements, detection, search);
        }
    }

    private static final class ShapeSummary {
        private int bestVisualError = ShapeTarget.HEART.requiredCells();
        private boolean cleanCompletion;

        void record(ShapeProgress progress) {
            bestVisualError = Math.min(bestVisualError, progress.visualErrorCells());
            cleanCompletion |= progress.cleanCompletion();
        }
    }
}
