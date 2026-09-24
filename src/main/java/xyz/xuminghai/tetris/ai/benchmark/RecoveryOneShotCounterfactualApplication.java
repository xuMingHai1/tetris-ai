/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai.benchmark;

import xyz.xuminghai.tetris.ai.BuildShapeActionPlanningAgent;
import xyz.xuminghai.tetris.ai.BuildShapeContinuationBenchmark;
import xyz.xuminghai.tetris.ai.BuildShapeDecisionObservation;
import xyz.xuminghai.tetris.ai.PlacementCandidate;
import xyz.xuminghai.tetris.ai.RecoveryCandidateScanBenchmark;
import xyz.xuminghai.tetris.ai.RecoveryRobustnessBenchmark;
import xyz.xuminghai.tetris.ai.ShapeProgress;
import xyz.xuminghai.tetris.ai.ShapeTarget;
import xyz.xuminghai.tetris.core.BagPieceGenerator;
import xyz.xuminghai.tetris.core.TetrominoType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * One-shot counterfactual validation of the first warning-clearing recovery action.
 *
 * <p>A fresh production BUILD_SHAPE trajectory supplies real warned SURVIVAL-rank-1 states. For
 * every state that has a first warning-clearing alternative, the benchmark forks the exact state:
 * branch A keeps production rank 1 and branch B applies the first clearing candidate. From the next
 * piece onward both branches run ordinary production BUILD_SHAPE with no further recovery
 * intervention and the same deterministic future 7-bag suffix.</p>
 *
 * <p>The fixed continuation horizon is evidence-only. It is not a runtime threshold. This benchmark
 * isolates the causal value of one recovery action from the repeated-intervention feedback observed
 * in the preceding paired whole-game experiment.</p>
 */
public final class RecoveryOneShotCounterfactualApplication {

    static final String GAMES_ENV = "TETRIS_BENCHMARK_GAMES";
    static final String MAX_PIECES_ENV = "TETRIS_BENCHMARK_MAX_PIECES";
    static final String SEED_ENV = "TETRIS_BENCHMARK_SEED";

    static final int CONTINUATION_HORIZON = 50;

    private static final int DEFAULT_GAMES = 20;
    private static final int DEFAULT_MAX_PIECES = 1000;
    private static final long DEFAULT_SEED = 8000L;

    private RecoveryOneShotCounterfactualApplication() {
    }

    public static void main(String[] args) {
        Configuration configuration = Configuration.fromEnvironment();
        HeadlessGameRunner runner = new HeadlessGameRunner();
        List<Sample> samples = new ArrayList<>();
        List<GameObservation> games = new ArrayList<>();

        for (int game = 0; game < configuration.games(); game++) {
            long seed = configuration.seed() + game;
            List<TetrominoType> sequence = pieceSequence(
                    seed,
                    configuration.maxPieces() + CONTINUATION_HORIZON + 1);
            DecisionCapture capture = new DecisionCapture();
            GameCounters counters = new GameCounters();

            GameBenchmarkResult result = runner.runPlanningObserved(
                    seed,
                    configuration.maxPieces(),
                    new BuildShapeActionPlanningAgent(
                            ShapeTarget.HEART,
                            capture::record),
                    null,
                    turn -> inspectTurn(
                            seed,
                            turn,
                            capture.current(),
                            sequence,
                            samples,
                            counters));

            games.add(new GameObservation(
                    seed,
                    result.piecesPlaced(),
                    result.reachedPieceLimit(),
                    counters.warningRank1States,
                    counters.qualifyingSamples,
                    counters.improvedSamples,
                    counters.regressedSamples,
                    counters.tiedSamples,
                    counters.horizonWins,
                    counters.horizonLosses));
        }

        printSamples(samples);
        printGames(games);
        printProtocol(configuration);
        printSummary(samples, games);
    }

    private static void inspectTurn(
            long seed,
            HeadlessGameRunner.TurnObservation turn,
            BuildShapeDecisionObservation buildDecision,
            List<TetrominoType> sequence,
            List<Sample> samples,
            GameCounters counters) {
        if (buildDecision == null
                || buildDecision.selectedRank() != 1
                || turn.snapshot().nextType().isEmpty()) {
            return;
        }

        assertSequenceAlignment(turn, sequence);
        TetrominoType preview = turn.snapshot().nextType().orElseThrow();
        RecoveryRobustnessBenchmark.Probe selectedProbe =
                RecoveryRobustnessBenchmark.probe(
                        turn.selected().resultingBoard(),
                        preview);
        if (!RecoveryReserveValidationApplication.warning(selectedProbe)) {
            return;
        }
        counters.warningRank1States++;

        RecoveryCandidateScanBenchmark.MatchSearch search =
                RecoveryCandidateScanBenchmark.findFirstMatching(
                        turn.snapshot(),
                        2,
                        probe -> !RecoveryReserveValidationApplication.warning(probe));
        RecoveryCandidateScanBenchmark.CandidateAnalysis clearing =
                search.match().orElse(null);
        if (clearing == null) {
            return;
        }

        int suffixStart = turn.decision();
        int suffixEnd = suffixStart + CONTINUATION_HORIZON + 1;
        List<TetrominoType> futureSuffix =
                sequence.subList(suffixStart, suffixEnd);

        BuildShapeContinuationBenchmark.Result baseline =
                BuildShapeContinuationBenchmark.rollout(
                        ShapeTarget.HEART,
                        turn.selected().resultingBoard(),
                        futureSuffix,
                        CONTINUATION_HORIZON);
        BuildShapeContinuationBenchmark.Result recovery =
                BuildShapeContinuationBenchmark.rollout(
                        ShapeTarget.HEART,
                        clearing.placement().resultingBoard(),
                        futureSuffix,
                        CONTINUATION_HORIZON);

        BranchMetrics baselineMetrics =
                analyzeBranch(turn.selected(), baseline);
        BranchMetrics recoveryMetrics =
                analyzeBranch(clearing.placement(), recovery);
        int depthDelta =
                recoveryMetrics.survivedDepth()
                        - baselineMetrics.survivedDepth();
        Outcome outcome = outcome(depthDelta);
        boolean horizonWin =
                !baselineMetrics.reachedHorizon()
                        && recoveryMetrics.reachedHorizon();
        boolean horizonLoss =
                baselineMetrics.reachedHorizon()
                        && !recoveryMetrics.reachedHorizon();

        samples.add(new Sample(
                seed,
                turn.decision(),
                RecoveryReserveValidationApplication.warningReason(selectedProbe),
                clearing.survivalRank(),
                search.candidatesProbed(),
                ShapeTarget.HEART
                        .progress(turn.selected().resultingBoard())
                        .visualErrorCells(),
                ShapeTarget.HEART
                        .progress(clearing.placement().resultingBoard())
                        .visualErrorCells(),
                baselineMetrics,
                recoveryMetrics,
                outcome));

        counters.qualifyingSamples++;
        switch (outcome) {
            case IMPROVED -> counters.improvedSamples++;
            case REGRESSED -> counters.regressedSamples++;
            case TIED -> counters.tiedSamples++;
        }
        if (horizonWin) {
            counters.horizonWins++;
        }
        if (horizonLoss) {
            counters.horizonLosses++;
        }
    }

    static BranchMetrics analyzeBranch(
            PlacementCandidate initialPlacement,
            BuildShapeContinuationBenchmark.Result continuation) {
        Objects.requireNonNull(initialPlacement, "initialPlacement");
        Objects.requireNonNull(continuation, "continuation");

        int warningRecurrences = 0;
        int firstWarningDepth = -1;
        int bestVisualError =
                ShapeTarget.HEART
                        .progress(initialPlacement.resultingBoard())
                        .visualErrorCells();

        for (BuildShapeContinuationBenchmark.Step step : continuation.steps()) {
            bestVisualError = Math.min(
                    bestVisualError,
                    step.progress().visualErrorCells());
            RecoveryRobustnessBenchmark.Probe probe =
                    RecoveryRobustnessBenchmark.probe(
                            step.placement().resultingBoard(),
                            step.nextType());
            if (RecoveryReserveValidationApplication.warning(probe)) {
                warningRecurrences++;
                if (firstWarningDepth < 0) {
                    firstWarningDepth = step.depth();
                }
            }
        }

        PlacementCandidate finalPlacement = continuation.steps().isEmpty()
                ? initialPlacement
                : continuation.steps().getLast().placement();
        int finalVisualError = continuation.steps().isEmpty()
                ? ShapeTarget.HEART
                        .progress(initialPlacement.resultingBoard())
                        .visualErrorCells()
                : continuation.steps().getLast().progress().visualErrorCells();

        return new BranchMetrics(
                continuation.survivedDepth(),
                continuation.reachedHorizon(),
                warningRecurrences,
                firstWarningDepth,
                finalPlacement.aggregateHeight(),
                finalPlacement.holes(),
                finalPlacement.bumpiness(),
                bestVisualError,
                finalVisualError);
    }

    static Outcome outcome(int depthDelta) {
        if (depthDelta > 0) {
            return Outcome.IMPROVED;
        }
        if (depthDelta < 0) {
            return Outcome.REGRESSED;
        }
        return Outcome.TIED;
    }

    private static void assertSequenceAlignment(
            HeadlessGameRunner.TurnObservation turn,
            List<TetrominoType> sequence) {
        int currentIndex = turn.decision() - 1;
        int previewIndex = turn.decision();
        if (previewIndex >= sequence.size()
                || sequence.get(currentIndex) != turn.snapshot().currentType()
                || sequence.get(previewIndex)
                        != turn.snapshot().nextType().orElseThrow()) {
            throw new IllegalStateException(
                    "seeded piece sequence diverged from baseline trajectory at decision "
                            + turn.decision());
        }
    }

    private static void printSamples(List<Sample> samples) {
        System.out.println(
                "recovery_one_shot_state,seed,decision,warning_reason,clearing_rank,"
                        + "candidates_probed,baseline_initial_visual_error,"
                        + "recovery_initial_visual_error,initial_visual_error_delta,"
                        + "baseline_survived_depth,recovery_survived_depth,survived_depth_delta,"
                        + "outcome,baseline_reached_horizon,recovery_reached_horizon,"
                        + "baseline_warning_recurrences,recovery_warning_recurrences,"
                        + "warning_recurrence_delta,baseline_first_warning_depth,"
                        + "recovery_first_warning_depth,baseline_final_aggregate_height,"
                        + "recovery_final_aggregate_height,final_aggregate_height_delta,"
                        + "baseline_final_holes,recovery_final_holes,final_holes_delta,"
                        + "baseline_final_bumpiness,recovery_final_bumpiness,"
                        + "final_bumpiness_delta,baseline_best_visual_error,"
                        + "recovery_best_visual_error,best_visual_error_delta,"
                        + "baseline_final_visual_error,recovery_final_visual_error,"
                        + "final_visual_error_delta");

        for (Sample sample : samples) {
            System.out.println(formatSample(sample));
        }
    }

    static String formatSample(Sample sample) {
        Objects.requireNonNull(sample, "sample");
        BranchMetrics baseline = sample.baseline();
        BranchMetrics recovery = sample.recovery();
        return String.format(
                Locale.ROOT,
                "recovery_one_shot_state,%d,%d,%s,%d,%d,%d,%d,%d,"
                        + "%d,%d,%d,%s,%s,%s,%d,%d,%d,%d,%d,%d,%d,%d,"
                        + "%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d",
                sample.seed(),
                sample.decision(),
                sample.warningReason(),
                sample.clearingRank(),
                sample.candidatesProbed(),
                sample.baselineInitialVisualError(),
                sample.recoveryInitialVisualError(),
                sample.initialVisualErrorDelta(),
                baseline.survivedDepth(),
                recovery.survivedDepth(),
                recovery.survivedDepth() - baseline.survivedDepth(),
                sample.outcome().configValue,
                baseline.reachedHorizon(),
                recovery.reachedHorizon(),
                baseline.warningRecurrences(),
                recovery.warningRecurrences(),
                recovery.warningRecurrences() - baseline.warningRecurrences(),
                baseline.firstWarningDepth(),
                recovery.firstWarningDepth(),
                baseline.finalAggregateHeight(),
                recovery.finalAggregateHeight(),
                recovery.finalAggregateHeight()
                        - baseline.finalAggregateHeight(),
                baseline.finalHoles(),
                recovery.finalHoles(),
                recovery.finalHoles() - baseline.finalHoles(),
                baseline.finalBumpiness(),
                recovery.finalBumpiness(),
                recovery.finalBumpiness() - baseline.finalBumpiness(),
                baseline.bestVisualError(),
                recovery.bestVisualError(),
                recovery.bestVisualError() - baseline.bestVisualError(),
                baseline.finalVisualError(),
                recovery.finalVisualError(),
                recovery.finalVisualError() - baseline.finalVisualError());
    }

    private static void printGames(List<GameObservation> games) {
        System.out.println(
                "recovery_one_shot_game,seed,baseline_pieces,baseline_reached_limit,"
                        + "warning_rank1_states,qualifying_samples,improved_samples,"
                        + "regressed_samples,tied_samples,horizon_wins,horizon_losses");
        for (GameObservation game : games) {
            System.out.printf(
                    Locale.ROOT,
                    "recovery_one_shot_game,%d,%d,%s,%d,%d,%d,%d,%d,%d,%d%n",
                    game.seed(),
                    game.baselinePieces(),
                    game.baselineReachedLimit(),
                    game.warningRank1States(),
                    game.qualifyingSamples(),
                    game.improvedSamples(),
                    game.regressedSamples(),
                    game.tiedSamples(),
                    game.horizonWins(),
                    game.horizonLosses());
        }
    }

    private static void printProtocol(Configuration configuration) {
        System.out.printf(
                Locale.ROOT,
                "# recovery_one_shot_protocol seed_start=%d games=%d max_pieces=%d "
                        + "continuation_horizon=%d sample=rank1_warning_with_first_clearing_alternative "
                        + "branch_a=survival_rank1_then_production_build_shape "
                        + "branch_b=first_clearing_then_production_build_shape "
                        + "recovery_headroom_cutoff=%d recovery_holes_cutoff=%d%n",
                configuration.seed(),
                configuration.games(),
                configuration.maxPieces(),
                CONTINUATION_HORIZON,
                RecoveryReserveValidationApplication.RECOVERY_HEADROOM_CUTOFF,
                RecoveryReserveValidationApplication.RECOVERY_HOLES_CUTOFF);
    }

    private static void printSummary(
            List<Sample> samples,
            List<GameObservation> games) {
        long improved = samples.stream()
                .filter(sample -> sample.outcome() == Outcome.IMPROVED)
                .count();
        long regressed = samples.stream()
                .filter(sample -> sample.outcome() == Outcome.REGRESSED)
                .count();
        long tied = samples.size() - improved - regressed;
        long horizonWins = samples.stream()
                .filter(sample ->
                        !sample.baseline().reachedHorizon()
                                && sample.recovery().reachedHorizon())
                .count();
        long horizonLosses = samples.stream()
                .filter(sample ->
                        sample.baseline().reachedHorizon()
                                && !sample.recovery().reachedHorizon())
                .count();
        long baselineHorizon = samples.stream()
                .filter(sample -> sample.baseline().reachedHorizon())
                .count();
        long recoveryHorizon = samples.stream()
                .filter(sample -> sample.recovery().reachedHorizon())
                .count();

        double averageDepthDelta = samples.stream()
                .mapToInt(sample ->
                        sample.recovery().survivedDepth()
                                - sample.baseline().survivedDepth())
                .average()
                .orElse(0.0);
        double medianDepthDelta = median(
                samples.stream()
                        .map(sample ->
                                sample.recovery().survivedDepth()
                                        - sample.baseline().survivedDepth())
                        .sorted()
                        .toList());
        double averageWarningDelta = samples.stream()
                .mapToInt(sample ->
                        sample.recovery().warningRecurrences()
                                - sample.baseline().warningRecurrences())
                .average()
                .orElse(0.0);
        long fewerWarningRecurrences = samples.stream()
                .filter(sample ->
                        sample.recovery().warningRecurrences()
                                < sample.baseline().warningRecurrences())
                .count();
        long moreWarningRecurrences = samples.stream()
                .filter(sample ->
                        sample.recovery().warningRecurrences()
                                > sample.baseline().warningRecurrences())
                .count();
        long sameWarningRecurrences =
                samples.size() - fewerWarningRecurrences - moreWarningRecurrences;
        double averageFinalHolesDelta = samples.stream()
                .mapToInt(sample ->
                        sample.recovery().finalHoles()
                                - sample.baseline().finalHoles())
                .average()
                .orElse(0.0);
        double averageFinalHeightDelta = samples.stream()
                .mapToInt(sample ->
                        sample.recovery().finalAggregateHeight()
                                - sample.baseline().finalAggregateHeight())
                .average()
                .orElse(0.0);
        double averageBestVisualErrorDelta = samples.stream()
                .mapToInt(sample ->
                        sample.recovery().bestVisualError()
                                - sample.baseline().bestVisualError())
                .average()
                .orElse(0.0);
        long nonZeroInitialVisualDelta = samples.stream()
                .filter(sample -> sample.initialVisualErrorDelta() != 0)
                .count();
        long sampleGames = samples.stream()
                .mapToLong(Sample::seed)
                .distinct()
                .count();
        long baselineHealthyGames = games.stream()
                .filter(GameObservation::baselineReachedLimit)
                .count();

        System.out.printf(
                Locale.ROOT,
                "# recovery_one_shot_result games=%d baseline_healthy_games=%d "
                        + "sample_games=%d qualifying_samples=%d improved_samples=%d "
                        + "regressed_samples=%d tied_samples=%d baseline_horizon_samples=%d "
                        + "recovery_horizon_samples=%d horizon_wins=%d horizon_losses=%d "
                        + "avg_survived_depth_delta=%.3f median_survived_depth_delta=%.3f "
                        + "fewer_warning_recurrence_samples=%d more_warning_recurrence_samples=%d "
                        + "same_warning_recurrence_samples=%d avg_warning_recurrence_delta=%.3f "
                        + "avg_final_holes_delta=%.3f avg_final_aggregate_height_delta=%.3f "
                        + "avg_best_visual_error_delta=%.3f "
                        + "nonzero_initial_visual_error_delta_samples=%d%n",
                games.size(),
                baselineHealthyGames,
                sampleGames,
                samples.size(),
                improved,
                regressed,
                tied,
                baselineHorizon,
                recoveryHorizon,
                horizonWins,
                horizonLosses,
                averageDepthDelta,
                medianDepthDelta,
                fewerWarningRecurrences,
                moreWarningRecurrences,
                sameWarningRecurrences,
                averageWarningDelta,
                averageFinalHolesDelta,
                averageFinalHeightDelta,
                averageBestVisualErrorDelta,
                nonZeroInitialVisualDelta);
    }

    static double median(List<Integer> sortedValues) {
        Objects.requireNonNull(sortedValues, "sortedValues");
        if (sortedValues.isEmpty()) {
            return 0.0;
        }
        int middle = sortedValues.size() / 2;
        if (sortedValues.size() % 2 == 1) {
            return sortedValues.get(middle);
        }
        return (sortedValues.get(middle - 1) + sortedValues.get(middle)) / 2.0;
    }

    private static List<TetrominoType> pieceSequence(
            long seed,
            int count) {
        BagPieceGenerator generator = new BagPieceGenerator(seed);
        List<TetrominoType> pieces = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            pieces.add(TetrominoType.from(generator.next()));
        }
        return List.copyOf(pieces);
    }

    enum Outcome {
        IMPROVED("improved"),
        REGRESSED("regressed"),
        TIED("tied");

        private final String configValue;

        Outcome(String configValue) {
            this.configValue = configValue;
        }
    }

    record BranchMetrics(
            int survivedDepth,
            boolean reachedHorizon,
            int warningRecurrences,
            int firstWarningDepth,
            int finalAggregateHeight,
            int finalHoles,
            int finalBumpiness,
            int bestVisualError,
            int finalVisualError) {

        BranchMetrics {
            if (survivedDepth < 0
                    || warningRecurrences < 0
                    || firstWarningDepth < -1
                    || finalAggregateHeight < 0
                    || finalHoles < 0
                    || finalBumpiness < 0
                    || bestVisualError < 0
                    || finalVisualError < 0
                    || reachedHorizon != (survivedDepth == CONTINUATION_HORIZON)
                    || (warningRecurrences == 0) != (firstWarningDepth == -1)) {
                throw new IllegalArgumentException(
                        "invalid one-shot branch metrics");
            }
        }
    }

    record Sample(
            long seed,
            int decision,
            String warningReason,
            int clearingRank,
            int candidatesProbed,
            int baselineInitialVisualError,
            int recoveryInitialVisualError,
            BranchMetrics baseline,
            BranchMetrics recovery,
            Outcome outcome) {

        Sample {
            Objects.requireNonNull(warningReason, "warningReason");
            Objects.requireNonNull(baseline, "baseline");
            Objects.requireNonNull(recovery, "recovery");
            Objects.requireNonNull(outcome, "outcome");
            if (decision <= 0
                    || clearingRank <= 1
                    || candidatesProbed <= 0
                    || candidatesProbed != clearingRank - 1
                    || baselineInitialVisualError < 0
                    || recoveryInitialVisualError < 0
                    || outcome != RecoveryOneShotCounterfactualApplication.outcome(
                            recovery.survivedDepth()
                                    - baseline.survivedDepth())) {
                throw new IllegalArgumentException(
                        "invalid one-shot counterfactual sample");
            }
        }

        int initialVisualErrorDelta() {
            return recoveryInitialVisualError
                    - baselineInitialVisualError;
        }
    }

    record GameObservation(
            long seed,
            int baselinePieces,
            boolean baselineReachedLimit,
            int warningRank1States,
            int qualifyingSamples,
            int improvedSamples,
            int regressedSamples,
            int tiedSamples,
            int horizonWins,
            int horizonLosses) {

        GameObservation {
            if (baselinePieces < 0
                    || warningRank1States < 0
                    || qualifyingSamples < 0
                    || improvedSamples < 0
                    || regressedSamples < 0
                    || tiedSamples < 0
                    || horizonWins < 0
                    || horizonLosses < 0
                    || improvedSamples + regressedSamples + tiedSamples
                            != qualifyingSamples
                    || horizonWins > improvedSamples
                    || horizonLosses > regressedSamples) {
                throw new IllegalArgumentException(
                        "invalid one-shot game observation");
            }
        }
    }

    private static final class DecisionCapture {
        private BuildShapeDecisionObservation current;

        void record(BuildShapeDecisionObservation decision) {
            current = Objects.requireNonNull(decision, "decision");
        }

        BuildShapeDecisionObservation current() {
            return current;
        }
    }

    private static final class GameCounters {
        private int warningRank1States;
        private int qualifyingSamples;
        private int improvedSamples;
        private int regressedSamples;
        private int tiedSamples;
        private int horizonWins;
        private int horizonLosses;
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
