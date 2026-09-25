/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai.benchmark;

import xyz.xuminghai.tetris.ai.BuildShapeActionPlanningAgent;
import xyz.xuminghai.tetris.ai.BuildShapeContinuationBenchmark;
import xyz.xuminghai.tetris.ai.BuildShapeDecisionObservation;
import xyz.xuminghai.tetris.ai.ObjectiveRiskController;
import xyz.xuminghai.tetris.ai.ObjectiveRiskProfile;
import xyz.xuminghai.tetris.ai.PlacementCandidate;
import xyz.xuminghai.tetris.ai.RecoveryCandidateScanBenchmark;
import xyz.xuminghai.tetris.ai.RecoveryRobustnessBenchmark;
import xyz.xuminghai.tetris.ai.ShapeProgress;
import xyz.xuminghai.tetris.ai.ShapeTarget;
import xyz.xuminghai.tetris.core.BagPieceGenerator;
import xyz.xuminghai.tetris.core.TetrominoType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;

/**
 * Calibration-only audit of runtime-observable recovery-intervention eligibility features.
 *
 * <p>The benchmark keeps the one-shot counterfactual label from
 * {@link RecoveryOneShotCounterfactualApplication}: a production rank-1 warning state is sampled
 * only when a first warning-clearing alternative exists, then the rank-1 and one-shot-clearing
 * branches receive the same deterministic future piece suffix and ordinary production BUILD_SHAPE
 * continuation. A positive continuation-depth delta is the calibration label {@code helpful}.</p>
 *
 * <p>Every audited feature is available before the counterfactual outcome is known. The original
 * baseline game's eventual 1000-piece outcome is retained only as evaluation metadata and is never
 * included in numeric or categorical feature discrimination. This application deliberately reports
 * threshold-free ROC AUC rather than choosing a runtime cutoff from the calibration population.</p>
 */
public final class RecoveryEligibilityFeatureAuditApplication {

    static final String GAMES_ENV = "TETRIS_BENCHMARK_GAMES";
    static final String MAX_PIECES_ENV = "TETRIS_BENCHMARK_MAX_PIECES";
    static final String SEED_ENV = "TETRIS_BENCHMARK_SEED";

    private static final int DEFAULT_GAMES = 30;
    private static final int DEFAULT_MAX_PIECES = 1000;
    private static final long DEFAULT_SEED = 9000L;

    static final String SAMPLE_HEADER =
            "recovery_eligibility_sample,seed,decision,evaluation_baseline_game_reached_limit,"
                    + "outcome,helpful,survived_depth_delta,baseline_reached_horizon,"
                    + "recovery_reached_horizon,warning_reason,current_type,preview_type,"
                    + "risk_level,risk_profile,creative_suppressed_by_danger,"
                    + "reachable_candidate_count,quality_shortlist_count,"
                    + "safety_eligible_candidates,baseline_risk_headroom,baseline_risk_holes,"
                    + "baseline_visual_error,baseline_matched_required,"
                    + "baseline_forbidden_occupied,total_candidates,candidates_probed,"
                    + "clearing_rank,rank1_cleared_lines,rank1_aggregate_height,rank1_holes,"
                    + "rank1_bumpiness,rank1_preview_recoverable,"
                    + "rank1_preview_reachable_outcomes,rank1_recovery_headroom,"
                    + "rank1_recovery_aggregate_height,rank1_recovery_holes,"
                    + "rank1_recovery_bumpiness,rank1_unplayable_unknown_types,"
                    + "rank1_min_unknown_reachable_outcomes,"
                    + "rank1_average_unknown_reachable_outcomes,"
                    + "rank1_max_unknown_reachable_outcomes,rank1_min_post_unknown_headroom,"
                    + "rank1_max_post_unknown_holes,rank1_max_post_unknown_aggregate_height,"
                    + "clearing_cleared_lines,clearing_aggregate_height,clearing_holes,"
                    + "clearing_bumpiness,clearing_preview_recoverable,"
                    + "clearing_preview_reachable_outcomes,clearing_recovery_headroom,"
                    + "clearing_recovery_aggregate_height,clearing_recovery_holes,"
                    + "clearing_recovery_bumpiness,clearing_unplayable_unknown_types,"
                    + "clearing_min_unknown_reachable_outcomes,"
                    + "clearing_average_unknown_reachable_outcomes,"
                    + "clearing_max_unknown_reachable_outcomes,"
                    + "clearing_min_post_unknown_headroom,clearing_max_post_unknown_holes,"
                    + "clearing_max_post_unknown_aggregate_height,"
                    + "clearing_visual_error,clearing_visual_error_delta";

    private RecoveryEligibilityFeatureAuditApplication() {
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
                    configuration.maxPieces()
                            + RecoveryOneShotCounterfactualApplication.CONTINUATION_HORIZON
                            + 1);
            DecisionCapture capture = new DecisionCapture();
            List<PendingSample> gameSamples = new ArrayList<>();
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
                            gameSamples,
                            counters));

            for (PendingSample pending : gameSamples) {
                samples.add(pending.complete(result.reachedPieceLimit()));
            }
            games.add(new GameObservation(
                    seed,
                    result.piecesPlaced(),
                    result.reachedPieceLimit(),
                    counters.warningRank1States,
                    gameSamples.size()));
        }

        List<NumericFeatureAudit> numericAudits = numericFeatures().stream()
                .map(feature -> auditNumericFeature(
                        feature.name(),
                        samples,
                        feature.extractor()))
                .sorted(Comparator
                        .comparingDouble(RecoveryEligibilityFeatureAuditApplication::sortableAuc)
                        .reversed()
                        .thenComparing(NumericFeatureAudit::feature))
                .toList();

        printSamples(samples);
        printNumericAudits(numericAudits);
        printCategoryAudits(samples);
        printGames(games);
        printProtocol(configuration);
        printSummary(samples, games, numericAudits);
    }

    private static void inspectTurn(
            long seed,
            HeadlessGameRunner.TurnObservation turn,
            BuildShapeDecisionObservation buildDecision,
            List<TetrominoType> sequence,
            List<PendingSample> samples,
            GameCounters counters) {
        if (buildDecision == null
                || buildDecision.selectedRank() != 1
                || turn.snapshot().nextType().isEmpty()) {
            return;
        }

        assertSequenceAlignment(turn, sequence);
        TetrominoType preview = turn.snapshot().nextType().orElseThrow();
        RecoveryRobustnessBenchmark.Probe rank1Probe =
                RecoveryRobustnessBenchmark.probe(
                        turn.selected().resultingBoard(),
                        preview);
        if (!RecoveryReserveValidationApplication.warning(rank1Probe)) {
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
        int suffixEnd = suffixStart
                + RecoveryOneShotCounterfactualApplication.CONTINUATION_HORIZON
                + 1;
        List<TetrominoType> futureSuffix =
                sequence.subList(suffixStart, suffixEnd);

        BuildShapeContinuationBenchmark.Result baseline =
                BuildShapeContinuationBenchmark.rollout(
                        ShapeTarget.HEART,
                        turn.selected().resultingBoard(),
                        futureSuffix,
                        RecoveryOneShotCounterfactualApplication.CONTINUATION_HORIZON);
        BuildShapeContinuationBenchmark.Result recovery =
                BuildShapeContinuationBenchmark.rollout(
                        ShapeTarget.HEART,
                        clearing.placement().resultingBoard(),
                        futureSuffix,
                        RecoveryOneShotCounterfactualApplication.CONTINUATION_HORIZON);

        RecoveryOneShotCounterfactualApplication.BranchMetrics baselineMetrics =
                RecoveryOneShotCounterfactualApplication.analyzeBranch(
                        turn.selected(),
                        baseline);
        RecoveryOneShotCounterfactualApplication.BranchMetrics recoveryMetrics =
                RecoveryOneShotCounterfactualApplication.analyzeBranch(
                        clearing.placement(),
                        recovery);
        int depthDelta =
                recoveryMetrics.survivedDepth()
                        - baselineMetrics.survivedDepth();
        RecoveryOneShotCounterfactualApplication.Outcome outcome =
                RecoveryOneShotCounterfactualApplication.outcome(depthDelta);

        ShapeProgress baselineProgress =
                ShapeTarget.HEART.progress(turn.selected().resultingBoard());
        ShapeProgress clearingProgress =
                ShapeTarget.HEART.progress(clearing.placement().resultingBoard());

        FeatureSnapshot features = new FeatureSnapshot(
                RecoveryReserveValidationApplication.warningReason(rank1Probe),
                turn.snapshot().currentType(),
                preview,
                buildDecision.riskLevel(),
                buildDecision.riskProfile(),
                buildDecision.creativeSuppressedByDanger(),
                buildDecision.reachableCandidateCount(),
                buildDecision.candidateCount(),
                buildDecision.safetyEligibleCandidates(),
                buildDecision.baselineHeadroom(),
                buildDecision.baselineHoles(),
                baselineProgress,
                search.totalCandidates(),
                search.candidatesProbed(),
                clearing.survivalRank(),
                turn.selected(),
                rank1Probe,
                clearing.placement(),
                clearing.probe(),
                clearingProgress);

        samples.add(new PendingSample(
                seed,
                turn.decision(),
                features,
                baselineMetrics,
                recoveryMetrics,
                outcome));
    }

    static NumericFeatureAudit auditNumericFeature(
            String feature,
            List<Sample> samples,
            ToDoubleFunction<Sample> extractor) {
        Objects.requireNonNull(feature, "feature");
        Objects.requireNonNull(samples, "samples");
        Objects.requireNonNull(extractor, "extractor");

        List<LabeledValue> values = new ArrayList<>();
        for (Sample sample : samples) {
            double value = extractor.applyAsDouble(sample);
            if (Double.isFinite(value)) {
                values.add(new LabeledValue(value, sample.helpful()));
            }
        }

        List<Double> helpful = values.stream()
                .filter(LabeledValue::helpful)
                .map(LabeledValue::value)
                .toList();
        List<Double> nonHelpful = values.stream()
                .filter(value -> !value.helpful())
                .map(LabeledValue::value)
                .toList();

        double helpfulMean = mean(helpful);
        double nonHelpfulMean = mean(nonHelpful);
        double aucHigher = rocAuc(values);
        double aucLower = Double.isFinite(aucHigher) ? 1.0 - aucHigher : Double.NaN;
        double bestAuc = Double.isFinite(aucHigher)
                ? Math.max(aucHigher, aucLower)
                : Double.NaN;
        Direction direction;
        if (!Double.isFinite(aucHigher)) {
            direction = Direction.INSUFFICIENT;
        }
        else if (aucHigher > 0.5) {
            direction = Direction.HELPFUL_HIGHER;
        }
        else if (aucHigher < 0.5) {
            direction = Direction.HELPFUL_LOWER;
        }
        else {
            direction = Direction.NONE;
        }

        return new NumericFeatureAudit(
                feature,
                values.size(),
                helpful.size(),
                nonHelpful.size(),
                helpfulMean,
                nonHelpfulMean,
                aucHigher,
                aucLower,
                bestAuc,
                direction);
    }

    /**
     * Pairwise ROC AUC with 0.5 credit for ties.
     *
     * <p>The result asks whether larger feature values tend to belong to helpful one-shot samples.
     * It does not select a threshold.</p>
     */
    static double rocAuc(List<LabeledValue> values) {
        Objects.requireNonNull(values, "values");
        List<LabeledValue> positives = values.stream()
                .filter(LabeledValue::helpful)
                .toList();
        List<LabeledValue> negatives = values.stream()
                .filter(value -> !value.helpful())
                .toList();
        if (positives.isEmpty() || negatives.isEmpty()) {
            return Double.NaN;
        }

        double score = 0.0;
        for (LabeledValue positive : positives) {
            for (LabeledValue negative : negatives) {
                int comparison = Double.compare(positive.value(), negative.value());
                if (comparison > 0) {
                    score += 1.0;
                }
                else if (comparison == 0) {
                    score += 0.5;
                }
            }
        }
        return score / (positives.size() * (double) negatives.size());
    }

    private static List<NumericFeature> numericFeatures() {
        return List.of(
                feature("clearing_rank", sample -> sample.features().clearingRank()),
                feature("total_candidates", sample -> sample.features().totalCandidates()),
                feature(
                        "reachable_candidate_count",
                        sample -> sample.features().reachableCandidateCount()),
                feature(
                        "quality_shortlist_count",
                        sample -> sample.features().qualityShortlistCount()),
                feature(
                        "safety_eligible_candidates",
                        sample -> sample.features().safetyEligibleCandidates()),
                feature(
                        "baseline_risk_headroom",
                        sample -> sample.features().baselineRiskHeadroom()),
                feature(
                        "baseline_risk_holes",
                        sample -> sample.features().baselineRiskHoles()),
                feature(
                        "baseline_visual_error",
                        sample -> sample.features().baselineProgress().visualErrorCells()),
                feature(
                        "baseline_matched_required",
                        sample -> sample.features().baselineProgress().matchedRequiredCells()),
                feature(
                        "baseline_forbidden_occupied",
                        sample -> sample.features().baselineProgress().forbiddenOccupiedCells()),
                feature(
                        "rank1_cleared_lines",
                        sample -> sample.features().rank1Placement().clearedLines()),
                feature(
                        "rank1_aggregate_height",
                        sample -> sample.features().rank1Placement().aggregateHeight()),
                feature(
                        "rank1_holes",
                        sample -> sample.features().rank1Placement().holes()),
                feature(
                        "rank1_bumpiness",
                        sample -> sample.features().rank1Placement().bumpiness()),
                feature(
                        "rank1_preview_reachable_outcomes",
                        sample -> sample.features().rank1Probe().previewReachableOutcomes()),
                feature(
                        "rank1_recovery_headroom",
                        sample -> available(
                                sample.features().rank1Probe(),
                                RecoveryRobustnessBenchmark.Probe::recoveryHeadroom)),
                feature(
                        "rank1_recovery_aggregate_height",
                        sample -> available(
                                sample.features().rank1Probe(),
                                RecoveryRobustnessBenchmark.Probe::recoveryAggregateHeight)),
                feature(
                        "rank1_recovery_holes",
                        sample -> available(
                                sample.features().rank1Probe(),
                                RecoveryRobustnessBenchmark.Probe::recoveryHoles)),
                feature(
                        "rank1_recovery_bumpiness",
                        sample -> available(
                                sample.features().rank1Probe(),
                                RecoveryRobustnessBenchmark.Probe::recoveryBumpiness)),
                feature(
                        "rank1_unplayable_unknown_types",
                        sample -> sample.features().rank1Probe().unplayableUnknownTypes()),
                feature(
                        "rank1_min_unknown_reachable_outcomes",
                        sample -> available(
                                sample.features().rank1Probe(),
                                probe -> probe.minUnknownReachableOutcomes())),
                feature(
                        "rank1_average_unknown_reachable_outcomes",
                        sample -> available(
                                sample.features().rank1Probe(),
                                RecoveryRobustnessBenchmark.Probe::averageUnknownReachableOutcomes)),
                feature(
                        "rank1_max_unknown_reachable_outcomes",
                        sample -> available(
                                sample.features().rank1Probe(),
                                probe -> probe.maxUnknownReachableOutcomes())),
                feature(
                        "rank1_min_post_unknown_headroom",
                        sample -> available(
                                sample.features().rank1Probe(),
                                RecoveryRobustnessBenchmark.Probe::minPostUnknownHeadroom)),
                feature(
                        "rank1_max_post_unknown_holes",
                        sample -> available(
                                sample.features().rank1Probe(),
                                RecoveryRobustnessBenchmark.Probe::maxPostUnknownHoles)),
                feature(
                        "rank1_max_post_unknown_aggregate_height",
                        sample -> available(
                                sample.features().rank1Probe(),
                                RecoveryRobustnessBenchmark.Probe::maxPostUnknownAggregateHeight)),
                feature(
                        "clearing_cleared_lines",
                        sample -> sample.features().clearingPlacement().clearedLines()),
                feature(
                        "clearing_aggregate_height",
                        sample -> sample.features().clearingPlacement().aggregateHeight()),
                feature(
                        "clearing_holes",
                        sample -> sample.features().clearingPlacement().holes()),
                feature(
                        "clearing_bumpiness",
                        sample -> sample.features().clearingPlacement().bumpiness()),
                feature(
                        "clearing_preview_reachable_outcomes",
                        sample -> sample.features().clearingProbe().previewReachableOutcomes()),
                feature(
                        "clearing_recovery_headroom",
                        sample -> available(
                                sample.features().clearingProbe(),
                                RecoveryRobustnessBenchmark.Probe::recoveryHeadroom)),
                feature(
                        "clearing_recovery_aggregate_height",
                        sample -> available(
                                sample.features().clearingProbe(),
                                RecoveryRobustnessBenchmark.Probe::recoveryAggregateHeight)),
                feature(
                        "clearing_recovery_holes",
                        sample -> available(
                                sample.features().clearingProbe(),
                                RecoveryRobustnessBenchmark.Probe::recoveryHoles)),
                feature(
                        "clearing_recovery_bumpiness",
                        sample -> available(
                                sample.features().clearingProbe(),
                                RecoveryRobustnessBenchmark.Probe::recoveryBumpiness)),
                feature(
                        "clearing_unplayable_unknown_types",
                        sample -> sample.features().clearingProbe().unplayableUnknownTypes()),
                feature(
                        "clearing_min_unknown_reachable_outcomes",
                        sample -> available(
                                sample.features().clearingProbe(),
                                probe -> probe.minUnknownReachableOutcomes())),
                feature(
                        "clearing_average_unknown_reachable_outcomes",
                        sample -> available(
                                sample.features().clearingProbe(),
                                RecoveryRobustnessBenchmark.Probe::averageUnknownReachableOutcomes)),
                feature(
                        "clearing_max_unknown_reachable_outcomes",
                        sample -> available(
                                sample.features().clearingProbe(),
                                probe -> probe.maxUnknownReachableOutcomes())),
                feature(
                        "clearing_min_post_unknown_headroom",
                        sample -> available(
                                sample.features().clearingProbe(),
                                RecoveryRobustnessBenchmark.Probe::minPostUnknownHeadroom)),
                feature(
                        "clearing_max_post_unknown_holes",
                        sample -> available(
                                sample.features().clearingProbe(),
                                RecoveryRobustnessBenchmark.Probe::maxPostUnknownHoles)),
                feature(
                        "clearing_max_post_unknown_aggregate_height",
                        sample -> available(
                                sample.features().clearingProbe(),
                                RecoveryRobustnessBenchmark.Probe::maxPostUnknownAggregateHeight)),
                feature(
                        "clearing_visual_error",
                        sample -> sample.features().clearingProgress().visualErrorCells()),
                feature(
                        "delta_aggregate_height",
                        sample -> sample.features().clearingPlacement().aggregateHeight()
                                - sample.features().rank1Placement().aggregateHeight()),
                feature(
                        "delta_holes",
                        sample -> sample.features().clearingPlacement().holes()
                                - sample.features().rank1Placement().holes()),
                feature(
                        "delta_bumpiness",
                        sample -> sample.features().clearingPlacement().bumpiness()
                                - sample.features().rank1Placement().bumpiness()),
                feature(
                        "delta_preview_reachable_outcomes",
                        sample -> sample.features().clearingProbe().previewReachableOutcomes()
                                - sample.features().rank1Probe().previewReachableOutcomes()),
                feature(
                        "delta_recovery_headroom",
                        sample -> deltaAvailable(
                                sample.features().rank1Probe(),
                                sample.features().clearingProbe(),
                                RecoveryRobustnessBenchmark.Probe::recoveryHeadroom)),
                feature(
                        "delta_recovery_aggregate_height",
                        sample -> deltaAvailable(
                                sample.features().rank1Probe(),
                                sample.features().clearingProbe(),
                                RecoveryRobustnessBenchmark.Probe::recoveryAggregateHeight)),
                feature(
                        "delta_recovery_holes",
                        sample -> deltaAvailable(
                                sample.features().rank1Probe(),
                                sample.features().clearingProbe(),
                                RecoveryRobustnessBenchmark.Probe::recoveryHoles)),
                feature(
                        "delta_recovery_bumpiness",
                        sample -> deltaAvailable(
                                sample.features().rank1Probe(),
                                sample.features().clearingProbe(),
                                RecoveryRobustnessBenchmark.Probe::recoveryBumpiness)),
                feature(
                        "delta_min_unknown_reachable_outcomes",
                        sample -> deltaAvailable(
                                sample.features().rank1Probe(),
                                sample.features().clearingProbe(),
                                probe -> probe.minUnknownReachableOutcomes())),
                feature(
                        "delta_average_unknown_reachable_outcomes",
                        sample -> deltaAvailable(
                                sample.features().rank1Probe(),
                                sample.features().clearingProbe(),
                                RecoveryRobustnessBenchmark.Probe::averageUnknownReachableOutcomes)),
                feature(
                        "delta_min_post_unknown_headroom",
                        sample -> deltaAvailable(
                                sample.features().rank1Probe(),
                                sample.features().clearingProbe(),
                                RecoveryRobustnessBenchmark.Probe::minPostUnknownHeadroom)),
                feature(
                        "delta_max_post_unknown_holes",
                        sample -> deltaAvailable(
                                sample.features().rank1Probe(),
                                sample.features().clearingProbe(),
                                RecoveryRobustnessBenchmark.Probe::maxPostUnknownHoles)),
                feature(
                        "delta_max_post_unknown_aggregate_height",
                        sample -> deltaAvailable(
                                sample.features().rank1Probe(),
                                sample.features().clearingProbe(),
                                RecoveryRobustnessBenchmark.Probe::maxPostUnknownAggregateHeight)));
    }

    private static NumericFeature feature(
            String name,
            ToDoubleFunction<Sample> extractor) {
        return new NumericFeature(name, extractor);
    }

    private static double available(
            RecoveryRobustnessBenchmark.Probe probe,
            ToDoubleFunction<RecoveryRobustnessBenchmark.Probe> extractor) {
        return probe.previewRecoverable()
                ? extractor.applyAsDouble(probe)
                : Double.NaN;
    }

    private static double deltaAvailable(
            RecoveryRobustnessBenchmark.Probe baseline,
            RecoveryRobustnessBenchmark.Probe clearing,
            ToDoubleFunction<RecoveryRobustnessBenchmark.Probe> extractor) {
        if (!baseline.previewRecoverable() || !clearing.previewRecoverable()) {
            return Double.NaN;
        }
        return extractor.applyAsDouble(clearing)
                - extractor.applyAsDouble(baseline);
    }

    private static double sortableAuc(NumericFeatureAudit audit) {
        return Double.isFinite(audit.bestAuc())
                ? audit.bestAuc()
                : Double.NEGATIVE_INFINITY;
    }

    private static double mean(List<Double> values) {
        return values.stream()
                .mapToDouble(Double::doubleValue)
                .average()
                .orElse(Double.NaN);
    }

    private static void printSamples(List<Sample> samples) {
        System.out.println(SAMPLE_HEADER);
        for (Sample sample : samples) {
            System.out.println(formatSample(sample));
        }
    }

    static String formatSample(Sample sample) {
        Objects.requireNonNull(sample, "sample");
        FeatureSnapshot features = sample.features();
        RecoveryRobustnessBenchmark.Probe rank1 = features.rank1Probe();
        RecoveryRobustnessBenchmark.Probe clearing = features.clearingProbe();

        List<String> columns = new ArrayList<>();
        columns.add("recovery_eligibility_sample");
        columns.add(Long.toString(sample.seed()));
        columns.add(Integer.toString(sample.decision()));
        columns.add(Boolean.toString(sample.baselineGameReachedLimit()));
        columns.add(sample.outcome().configValue);
        columns.add(Boolean.toString(sample.helpful()));
        columns.add(Integer.toString(sample.survivedDepthDelta()));
        columns.add(Boolean.toString(sample.baseline().reachedHorizon()));
        columns.add(Boolean.toString(sample.recovery().reachedHorizon()));
        columns.add(features.warningReason());
        columns.add(features.currentType().name());
        columns.add(features.previewType().name());
        columns.add(features.riskLevel().name());
        columns.add(features.riskProfile().configValue());
        columns.add(Boolean.toString(features.creativeSuppressedByDanger()));
        columns.add(Integer.toString(features.reachableCandidateCount()));
        columns.add(Integer.toString(features.qualityShortlistCount()));
        columns.add(Integer.toString(features.safetyEligibleCandidates()));
        columns.add(Integer.toString(features.baselineRiskHeadroom()));
        columns.add(Integer.toString(features.baselineRiskHoles()));
        columns.add(Integer.toString(features.baselineProgress().visualErrorCells()));
        columns.add(Integer.toString(features.baselineProgress().matchedRequiredCells()));
        columns.add(Integer.toString(features.baselineProgress().forbiddenOccupiedCells()));
        columns.add(Integer.toString(features.totalCandidates()));
        columns.add(Integer.toString(features.candidatesProbed()));
        columns.add(Integer.toString(features.clearingRank()));
        addPlacement(columns, features.rank1Placement());
        addProbe(columns, rank1);
        addPlacement(columns, features.clearingPlacement());
        addProbe(columns, clearing);
        columns.add(Integer.toString(features.clearingProgress().visualErrorCells()));
        columns.add(Integer.toString(
                features.clearingProgress().visualErrorCells()
                        - features.baselineProgress().visualErrorCells()));
        return String.join(",", columns);
    }

    private static void addPlacement(
            List<String> columns,
            PlacementCandidate placement) {
        columns.add(Integer.toString(placement.clearedLines()));
        columns.add(Integer.toString(placement.aggregateHeight()));
        columns.add(Integer.toString(placement.holes()));
        columns.add(Integer.toString(placement.bumpiness()));
    }

    private static void addProbe(
            List<String> columns,
            RecoveryRobustnessBenchmark.Probe probe) {
        columns.add(Boolean.toString(probe.previewRecoverable()));
        columns.add(Integer.toString(probe.previewReachableOutcomes()));
        columns.add(Integer.toString(probe.recoveryHeadroom()));
        columns.add(Integer.toString(probe.recoveryAggregateHeight()));
        columns.add(Integer.toString(probe.recoveryHoles()));
        columns.add(Integer.toString(probe.recoveryBumpiness()));
        columns.add(Integer.toString(probe.unplayableUnknownTypes()));
        columns.add(Integer.toString(probe.minUnknownReachableOutcomes()));
        columns.add(formatDouble(probe.averageUnknownReachableOutcomes()));
        columns.add(Integer.toString(probe.maxUnknownReachableOutcomes()));
        columns.add(Integer.toString(probe.minPostUnknownHeadroom()));
        columns.add(Integer.toString(probe.maxPostUnknownHoles()));
        columns.add(Integer.toString(probe.maxPostUnknownAggregateHeight()));
    }

    private static void printNumericAudits(List<NumericFeatureAudit> audits) {
        System.out.println(
                "recovery_eligibility_feature,feature,usable_samples,helpful_samples,"
                        + "non_helpful_samples,helpful_mean,non_helpful_mean,"
                        + "auc_helpful_higher,auc_helpful_lower,best_auc,direction");
        for (NumericFeatureAudit audit : audits) {
            System.out.printf(
                    Locale.ROOT,
                    "recovery_eligibility_feature,%s,%d,%d,%d,%s,%s,%s,%s,%s,%s%n",
                    audit.feature(),
                    audit.usableSamples(),
                    audit.helpfulSamples(),
                    audit.nonHelpfulSamples(),
                    formatDouble(audit.helpfulMean()),
                    formatDouble(audit.nonHelpfulMean()),
                    formatDouble(audit.aucHelpfulHigher()),
                    formatDouble(audit.aucHelpfulLower()),
                    formatDouble(audit.bestAuc()),
                    audit.direction().configValue);
        }
    }

    private static void printCategoryAudits(List<Sample> samples) {
        System.out.println(
                "recovery_eligibility_category,feature,value,samples,helpful,harmful,tied,"
                        + "helpful_rate,evaluation_only");
        for (CategoryFeature feature : categoryFeatures()) {
            Map<String, CategoryCounts> counts = new TreeMap<>();
            for (Sample sample : samples) {
                String value = feature.extractor().apply(sample);
                counts.computeIfAbsent(value, ignored -> new CategoryCounts())
                        .accept(sample);
            }
            for (Map.Entry<String, CategoryCounts> entry : counts.entrySet()) {
                CategoryCounts value = entry.getValue();
                System.out.printf(
                        Locale.ROOT,
                        "recovery_eligibility_category,%s,%s,%d,%d,%d,%d,%.6f,%s%n",
                        feature.name(),
                        entry.getKey(),
                        value.samples,
                        value.helpful,
                        value.harmful,
                        value.tied,
                        value.samples == 0
                                ? 0.0
                                : value.helpful / (double) value.samples,
                        feature.evaluationOnly());
            }
        }
    }

    private static List<CategoryFeature> categoryFeatures() {
        return List.of(
                category("warning_reason", sample -> sample.features().warningReason(), false),
                category(
                        "risk_level",
                        sample -> sample.features().riskLevel().name(),
                        false),
                category(
                        "risk_profile",
                        sample -> sample.features().riskProfile().configValue(),
                        false),
                category(
                        "current_type",
                        sample -> sample.features().currentType().name(),
                        false),
                category(
                        "preview_type",
                        sample -> sample.features().previewType().name(),
                        false),
                category(
                        "rank1_preview_recoverable",
                        sample -> Boolean.toString(
                                sample.features().rank1Probe().previewRecoverable()),
                        false),
                category(
                        "creative_suppressed_by_danger",
                        sample -> Boolean.toString(
                                sample.features().creativeSuppressedByDanger()),
                        false),
                category(
                        "baseline_game_result",
                        sample -> sample.baselineGameReachedLimit()
                                ? "reached-limit"
                                : "failed-before-limit",
                        true));
    }

    private static CategoryFeature category(
            String name,
            Function<Sample, String> extractor,
            boolean evaluationOnly) {
        return new CategoryFeature(name, extractor, evaluationOnly);
    }

    private static void printGames(List<GameObservation> games) {
        System.out.println(
                "recovery_eligibility_game,seed,baseline_pieces,baseline_reached_limit,"
                        + "warning_rank1_states,qualifying_samples");
        for (GameObservation game : games) {
            System.out.printf(
                    Locale.ROOT,
                    "recovery_eligibility_game,%d,%d,%s,%d,%d%n",
                    game.seed(),
                    game.baselinePieces(),
                    game.baselineReachedLimit(),
                    game.warningRank1States(),
                    game.qualifyingSamples());
        }
    }

    private static void printProtocol(Configuration configuration) {
        System.out.printf(
                Locale.ROOT,
                "# recovery_eligibility_protocol seed_start=%d games=%d max_pieces=%d "
                        + "continuation_horizon=%d label=one_shot_survived_depth_delta "
                        + "positive=depth_delta_gt_0 feature_timing=pre_outcome "
                        + "threshold_selection=none evaluation_only_future_game_result=true%n",
                configuration.seed(),
                configuration.games(),
                configuration.maxPieces(),
                RecoveryOneShotCounterfactualApplication.CONTINUATION_HORIZON);
    }

    private static void printSummary(
            List<Sample> samples,
            List<GameObservation> games,
            List<NumericFeatureAudit> numericAudits) {
        long helpful = samples.stream().filter(Sample::helpful).count();
        long harmful = samples.stream()
                .filter(sample ->
                        sample.outcome()
                                == RecoveryOneShotCounterfactualApplication.Outcome.REGRESSED)
                .count();
        long tied = samples.size() - helpful - harmful;
        long sampleGames = samples.stream().mapToLong(Sample::seed).distinct().count();
        long baselineHealthyGames = games.stream()
                .filter(GameObservation::baselineReachedLimit)
                .count();

        OutcomeCounts healthySamples = outcomeCounts(
                samples.stream()
                        .filter(Sample::baselineGameReachedLimit)
                        .toList());
        OutcomeCounts failedSamples = outcomeCounts(
                samples.stream()
                        .filter(sample -> !sample.baselineGameReachedLimit())
                        .toList());

        NumericFeatureAudit best = numericAudits.stream()
                .filter(audit -> Double.isFinite(audit.bestAuc()))
                .findFirst()
                .orElse(null);

        System.out.printf(
                Locale.ROOT,
                "# recovery_eligibility_result games=%d baseline_healthy_games=%d "
                        + "sample_games=%d samples=%d helpful=%d harmful=%d tied=%d "
                        + "healthy_game_samples=%d healthy_game_helpful=%d "
                        + "healthy_game_harmful=%d healthy_game_tied=%d "
                        + "failed_game_samples=%d failed_game_helpful=%d "
                        + "failed_game_harmful=%d failed_game_tied=%d "
                        + "best_numeric_feature=%s best_auc=%s best_direction=%s%n",
                games.size(),
                baselineHealthyGames,
                sampleGames,
                samples.size(),
                helpful,
                harmful,
                tied,
                healthySamples.samples(),
                healthySamples.helpful(),
                healthySamples.harmful(),
                healthySamples.tied(),
                failedSamples.samples(),
                failedSamples.helpful(),
                failedSamples.harmful(),
                failedSamples.tied(),
                best == null ? "none" : best.feature(),
                best == null ? "nan" : formatDouble(best.bestAuc()),
                best == null ? "insufficient" : best.direction().configValue);

        int top = Math.min(5, numericAudits.size());
        for (int index = 0; index < top; index++) {
            NumericFeatureAudit audit = numericAudits.get(index);
            if (!Double.isFinite(audit.bestAuc())) {
                break;
            }
            System.out.printf(
                    Locale.ROOT,
                    "# recovery_eligibility_top_feature rank=%d feature=%s usable_samples=%d "
                            + "best_auc=%.6f direction=%s helpful_mean=%s non_helpful_mean=%s%n",
                    index + 1,
                    audit.feature(),
                    audit.usableSamples(),
                    audit.bestAuc(),
                    audit.direction().configValue,
                    formatDouble(audit.helpfulMean()),
                    formatDouble(audit.nonHelpfulMean()));
        }
    }

    private static OutcomeCounts outcomeCounts(List<Sample> samples) {
        long helpful = samples.stream().filter(Sample::helpful).count();
        long harmful = samples.stream()
                .filter(sample ->
                        sample.outcome()
                                == RecoveryOneShotCounterfactualApplication.Outcome.REGRESSED)
                .count();
        return new OutcomeCounts(
                samples.size(),
                helpful,
                harmful,
                samples.size() - helpful - harmful);
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

    private static String formatDouble(double value) {
        return Double.isFinite(value)
                ? String.format(Locale.ROOT, "%.6f", value)
                : "nan";
    }

    enum Direction {
        HELPFUL_HIGHER("helpful-higher"),
        HELPFUL_LOWER("helpful-lower"),
        NONE("none"),
        INSUFFICIENT("insufficient");

        private final String configValue;

        Direction(String configValue) {
            this.configValue = configValue;
        }
    }

    record LabeledValue(double value, boolean helpful) {
        LabeledValue {
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException(
                        "labeled feature value must be finite");
            }
        }
    }

    record NumericFeatureAudit(
            String feature,
            int usableSamples,
            int helpfulSamples,
            int nonHelpfulSamples,
            double helpfulMean,
            double nonHelpfulMean,
            double aucHelpfulHigher,
            double aucHelpfulLower,
            double bestAuc,
            Direction direction) {

        NumericFeatureAudit {
            Objects.requireNonNull(feature, "feature");
            Objects.requireNonNull(direction, "direction");
            if (usableSamples < 0
                    || helpfulSamples < 0
                    || nonHelpfulSamples < 0
                    || helpfulSamples + nonHelpfulSamples != usableSamples) {
                throw new IllegalArgumentException(
                        "invalid numeric feature audit counts");
            }
        }
    }

    record FeatureSnapshot(
            String warningReason,
            TetrominoType currentType,
            TetrominoType previewType,
            ObjectiveRiskController.RiskLevel riskLevel,
            ObjectiveRiskProfile riskProfile,
            boolean creativeSuppressedByDanger,
            int reachableCandidateCount,
            int qualityShortlistCount,
            int safetyEligibleCandidates,
            int baselineRiskHeadroom,
            int baselineRiskHoles,
            ShapeProgress baselineProgress,
            int totalCandidates,
            int candidatesProbed,
            int clearingRank,
            PlacementCandidate rank1Placement,
            RecoveryRobustnessBenchmark.Probe rank1Probe,
            PlacementCandidate clearingPlacement,
            RecoveryRobustnessBenchmark.Probe clearingProbe,
            ShapeProgress clearingProgress) {

        FeatureSnapshot {
            Objects.requireNonNull(warningReason, "warningReason");
            Objects.requireNonNull(currentType, "currentType");
            Objects.requireNonNull(previewType, "previewType");
            Objects.requireNonNull(riskLevel, "riskLevel");
            Objects.requireNonNull(riskProfile, "riskProfile");
            Objects.requireNonNull(baselineProgress, "baselineProgress");
            Objects.requireNonNull(rank1Placement, "rank1Placement");
            Objects.requireNonNull(rank1Probe, "rank1Probe");
            Objects.requireNonNull(clearingPlacement, "clearingPlacement");
            Objects.requireNonNull(clearingProbe, "clearingProbe");
            Objects.requireNonNull(clearingProgress, "clearingProgress");
            if (reachableCandidateCount <= 0
                    || qualityShortlistCount <= 0
                    || safetyEligibleCandidates <= 0
                    || baselineRiskHeadroom < 0
                    || baselineRiskHoles < 0
                    || totalCandidates <= 1
                    || candidatesProbed <= 0
                    || clearingRank <= 1
                    || candidatesProbed != clearingRank - 1
                    || clearingRank > totalCandidates) {
                throw new IllegalArgumentException(
                        "invalid recovery eligibility feature snapshot");
            }
        }
    }

    record Sample(
            long seed,
            int decision,
            boolean baselineGameReachedLimit,
            FeatureSnapshot features,
            RecoveryOneShotCounterfactualApplication.BranchMetrics baseline,
            RecoveryOneShotCounterfactualApplication.BranchMetrics recovery,
            RecoveryOneShotCounterfactualApplication.Outcome outcome) {

        Sample {
            Objects.requireNonNull(features, "features");
            Objects.requireNonNull(baseline, "baseline");
            Objects.requireNonNull(recovery, "recovery");
            Objects.requireNonNull(outcome, "outcome");
            if (decision <= 0
                    || outcome
                            != RecoveryOneShotCounterfactualApplication.outcome(
                                    recovery.survivedDepth()
                                            - baseline.survivedDepth())) {
                throw new IllegalArgumentException(
                        "invalid recovery eligibility sample");
            }
        }

        boolean helpful() {
            return outcome
                    == RecoveryOneShotCounterfactualApplication.Outcome.IMPROVED;
        }

        int survivedDepthDelta() {
            return recovery.survivedDepth() - baseline.survivedDepth();
        }
    }

    record PendingSample(
            long seed,
            int decision,
            FeatureSnapshot features,
            RecoveryOneShotCounterfactualApplication.BranchMetrics baseline,
            RecoveryOneShotCounterfactualApplication.BranchMetrics recovery,
            RecoveryOneShotCounterfactualApplication.Outcome outcome) {

        Sample complete(boolean baselineGameReachedLimit) {
            return new Sample(
                    seed,
                    decision,
                    baselineGameReachedLimit,
                    features,
                    baseline,
                    recovery,
                    outcome);
        }
    }

    record GameObservation(
            long seed,
            int baselinePieces,
            boolean baselineReachedLimit,
            int warningRank1States,
            int qualifyingSamples) {

        GameObservation {
            if (baselinePieces < 0
                    || warningRank1States < 0
                    || qualifyingSamples < 0
                    || qualifyingSamples > warningRank1States) {
                throw new IllegalArgumentException(
                        "invalid recovery eligibility game observation");
            }
        }
    }

    record NumericFeature(
            String name,
            ToDoubleFunction<Sample> extractor) {

        NumericFeature {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(extractor, "extractor");
        }
    }

    record CategoryFeature(
            String name,
            Function<Sample, String> extractor,
            boolean evaluationOnly) {

        CategoryFeature {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(extractor, "extractor");
        }
    }

    record OutcomeCounts(
            long samples,
            long helpful,
            long harmful,
            long tied) {
    }

    private static final class CategoryCounts {
        private int samples;
        private int helpful;
        private int harmful;
        private int tied;

        void accept(Sample sample) {
            samples++;
            switch (sample.outcome()) {
                case IMPROVED -> helpful++;
                case REGRESSED -> harmful++;
                case TIED -> tied++;
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
