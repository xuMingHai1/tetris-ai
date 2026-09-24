/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai.benchmark;

import xyz.xuminghai.tetris.ai.ConstructionSafetyEnvelopeBenchmark;
import xyz.xuminghai.tetris.ai.ShapeConstructionFeasibilityBenchmark;
import xyz.xuminghai.tetris.ai.ShapeProgress;
import xyz.xuminghai.tetris.ai.ShapeTarget;
import xyz.xuminghai.tetris.ai.ShapeWitnessConstraintAudit;
import xyz.xuminghai.tetris.core.BagPieceGenerator;
import xyz.xuminghai.tetris.core.TetrominoType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Manual benchmark entry point for bounded BUILD_SHAPE construction feasibility search.
 *
 * <p>A successful result is constructive evidence because the witness is produced entirely from
 * action-native reachable plans and production post-row-clear boards. An unsuccessful bounded
 * search is reported only as "not found", never as proof that the target is impossible.</p>
 */
public final class ShapeFeasibilityApplication {

    static final String GAMES_ENV = "TETRIS_BENCHMARK_GAMES";
    static final String MAX_PIECES_ENV = "TETRIS_BENCHMARK_MAX_PIECES";
    static final String SEED_ENV = "TETRIS_BENCHMARK_SEED";
    static final String BEAM_WIDTH_ENV = "TETRIS_BENCHMARK_FEASIBILITY_BEAM_WIDTH";

    private static final int DEFAULT_GAMES = 1;
    private static final int DEFAULT_MAX_PIECES = 24;
    private static final long DEFAULT_SEED = 1L;
    private static final int DEFAULT_BEAM_WIDTH = 128;

    private ShapeFeasibilityApplication() {
    }

    public static void main(String[] args) {
        Configuration configuration = Configuration.fromEnvironment();
        List<SeedResult> results = new ArrayList<>(configuration.games());
        List<WitnessAuditResult> audits = new ArrayList<>();
        List<EnvelopeResult> envelopes = new ArrayList<>();

        System.out.println(
                "shape_feasibility,seed,search_depth,beam_width,clean_completion,"
                        + "pieces_to_completion,best_visual_error_cells,"
                        + "max_required_with_zero_forbidden,min_forbidden_at_full_required,"
                        + "best_matched_required_cells,best_forbidden_occupied_cells,"
                        + "best_support_occupied_cells,expanded_states,generated_placements,"
                        + "unique_states,max_frontier_size");

        System.out.println(
                "shape_witness_audit,seed,step,piece,survival_rank,reachable_candidates,"
                        + "action_only,in_top5,risk_level,risk_profile,baseline_headroom,"
                        + "baseline_holes,safety_allowed,cleared_lines_delta,height_delta,"
                        + "holes_delta,bumpiness_delta,danger_suppressed,"
                        + "runtime_selected_witness_outcome,blocker,before_matched_required,"
                        + "before_forbidden_occupied,before_visual_error,"
                        + "survival_matched_required,survival_forbidden_occupied,"
                        + "survival_visual_error,witness_matched_required,"
                        + "witness_forbidden_occupied,witness_visual_error,"
                        + "survival_raw_holes,survival_required_holes,"
                        + "survival_forbidden_holes,survival_support_holes,"
                        + "survival_outside_holes,witness_raw_holes,witness_required_holes,"
                        + "witness_forbidden_holes,witness_support_holes,witness_outside_holes,"
                        + "forbidden_excluded_holes_delta,"
                        + "forbidden_excluded_safety_allowed_current_profile,"
                        + "forbidden_excluded_risk_level,forbidden_excluded_risk_profile,"
                        + "forbidden_excluded_safety_allowed_adjusted_profile");

        System.out.println(
                "construction_safety_envelope,seed,step,piece,action_count,survival_rank,"
                        + "reachable_outcomes,next_piece,next_reachable_outcomes,cleared_lines,"
                        + "headroom,max_column_height,aggregate_height,raw_holes,"
                        + "non_forbidden_holes,required_holes,forbidden_holes,support_holes,"
                        + "outside_holes,bumpiness,matched_required,forbidden_occupied,"
                        + "visual_error,clean_completion");

        for (int game = 0; game < configuration.games(); game++) {
            long seed = configuration.seed() + game;
            List<TetrominoType> pieces = pieceSequence(seed, configuration.maxPieces());
            long started = System.nanoTime();
            ShapeConstructionFeasibilityBenchmark.Result result =
                    ShapeConstructionFeasibilityBenchmark.search(
                            ShapeTarget.HEART,
                            pieces,
                            HeadlessGameRunner.DEFAULT_ROWS,
                            HeadlessGameRunner.DEFAULT_COLS,
                            configuration.beamWidth());
            long elapsedNanos = System.nanoTime() - started;
            results.add(new SeedResult(seed, result, elapsedNanos));

            ShapeProgress best = result.bestProgress();
            System.out.printf(
                    Locale.ROOT,
                    "shape_feasibility,%d,%d,%d,%s,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d%n",
                    seed,
                    result.searchedPieces(),
                    result.beamWidth(),
                    result.cleanCompletionFound(),
                    result.piecesToCompletion(),
                    result.bestVisualErrorCells(),
                    result.maxRequiredWithZeroForbidden(),
                    result.minForbiddenAtFullRequired(),
                    best.matchedRequiredCells(),
                    best.forbiddenOccupiedCells(),
                    best.supportOccupiedCells(),
                    result.expandedStates(),
                    result.generatedPlacements(),
                    result.uniqueStates(),
                    result.maxFrontierSize());

            if (result.cleanCompletionFound()) {
                for (int index = 0; index < result.witness().size(); index++) {
                    ShapeConstructionFeasibilityBenchmark.WitnessStep step =
                            result.witness().get(index);
                    System.out.printf(
                            Locale.ROOT,
                            "shape_feasibility_witness,%d,%d,%s,%s%n",
                            seed,
                            index + 1,
                            step.pieceType().name(),
                            actions(step));
                }

                ShapeWitnessConstraintAudit.Result audit =
                        ShapeWitnessConstraintAudit.audit(
                                ShapeTarget.HEART,
                                result.witness(),
                                HeadlessGameRunner.DEFAULT_ROWS,
                                HeadlessGameRunner.DEFAULT_COLS);
                audits.add(new WitnessAuditResult(seed, audit));
                printWitnessAudit(seed, audit);

                ConstructionSafetyEnvelopeBenchmark.Result envelope =
                        ConstructionSafetyEnvelopeBenchmark.analyze(
                                ShapeTarget.HEART,
                                result.witness(),
                                HeadlessGameRunner.DEFAULT_ROWS,
                                HeadlessGameRunner.DEFAULT_COLS);
                envelopes.add(new EnvelopeResult(seed, envelope));
                printConstructionSafetyEnvelope(seed, envelope);
            }
        }

        printSummary(configuration, results);
        printWitnessAuditSummary(audits);
        printConstructionSafetyEnvelopeSummary(envelopes);
    }

    private static void printConstructionSafetyEnvelope(
            long seed,
            ConstructionSafetyEnvelopeBenchmark.Result envelope) {
        for (ConstructionSafetyEnvelopeBenchmark.Step step : envelope.steps()) {
            System.out.println(formatConstructionSafetyEnvelopeLine(seed, step));
        }

        System.out.printf(
                Locale.ROOT,
                "# construction_safety_envelope seed=%d steps=%d min_headroom=%d "
                        + "max_column_height=%d max_aggregate_height=%d max_raw_holes=%d "
                        + "max_non_forbidden_holes=%d max_required_holes=%d "
                        + "max_forbidden_holes=%d max_support_holes=%d max_outside_holes=%d "
                        + "max_bumpiness=%d min_reachable_outcomes=%d "
                        + "min_next_reachable_outcomes=%d avg_reachable_outcomes=%.2f "
                        + "max_survival_rank=%d avg_survival_rank=%.2f%n",
                seed,
                envelope.steps().size(),
                envelope.minHeadroom(),
                envelope.maxColumnHeight(),
                envelope.maxAggregateHeight(),
                envelope.maxRawHoles(),
                envelope.maxNonForbiddenHoles(),
                envelope.maxRequiredHoles(),
                envelope.maxForbiddenHoles(),
                envelope.maxSupportHoles(),
                envelope.maxOutsideHoles(),
                envelope.maxBumpiness(),
                envelope.minReachableOutcomes(),
                envelope.minNextReachableOutcomes(),
                envelope.averageReachableOutcomes(),
                envelope.maxSurvivalRank(),
                envelope.averageSurvivalRank());
    }

    static String formatConstructionSafetyEnvelopeLine(
            long seed,
            ConstructionSafetyEnvelopeBenchmark.Step step) {
        return String.join(
                ",",
                "construction_safety_envelope",
                Long.toString(seed),
                Integer.toString(step.step()),
                step.pieceType().name(),
                Integer.toString(step.actionCount()),
                Integer.toString(step.survivalRank()),
                Integer.toString(step.reachableOutcomes()),
                step.nextPieceType().map(Enum::name).orElse(""),
                Integer.toString(step.nextReachableOutcomes()),
                Integer.toString(step.clearedLines()),
                Integer.toString(step.headroom()),
                Integer.toString(step.maxColumnHeight()),
                Integer.toString(step.aggregateHeight()),
                Integer.toString(step.holes().totalHoles()),
                Integer.toString(step.holes().nonForbiddenHoles()),
                Integer.toString(step.holes().requiredHoles()),
                Integer.toString(step.holes().forbiddenHoles()),
                Integer.toString(step.holes().supportAllowedHoles()),
                Integer.toString(step.holes().outsideTargetHoles()),
                Integer.toString(step.bumpiness()),
                Integer.toString(step.progress().matchedRequiredCells()),
                Integer.toString(step.progress().forbiddenOccupiedCells()),
                Integer.toString(step.progress().visualErrorCells()),
                Boolean.toString(step.progress().cleanCompletion()));
    }

    private static void printConstructionSafetyEnvelopeSummary(
            List<EnvelopeResult> envelopes) {
        if (envelopes.isEmpty()) {
            return;
        }

        List<ConstructionSafetyEnvelopeBenchmark.Step> steps = envelopes.stream()
                .flatMap(envelope -> envelope.envelope().steps().stream())
                .toList();
        int minHeadroom = steps.stream()
                .mapToInt(ConstructionSafetyEnvelopeBenchmark.Step::headroom)
                .min()
                .orElseThrow();
        int maxColumnHeight = steps.stream()
                .mapToInt(ConstructionSafetyEnvelopeBenchmark.Step::maxColumnHeight)
                .max()
                .orElseThrow();
        int maxAggregateHeight = steps.stream()
                .mapToInt(ConstructionSafetyEnvelopeBenchmark.Step::aggregateHeight)
                .max()
                .orElseThrow();
        int maxRawHoles = steps.stream()
                .mapToInt(step -> step.holes().totalHoles())
                .max()
                .orElseThrow();
        int maxNonForbiddenHoles = steps.stream()
                .mapToInt(step -> step.holes().nonForbiddenHoles())
                .max()
                .orElseThrow();
        int maxRequiredHoles = steps.stream()
                .mapToInt(step -> step.holes().requiredHoles())
                .max()
                .orElseThrow();
        int maxForbiddenHoles = steps.stream()
                .mapToInt(step -> step.holes().forbiddenHoles())
                .max()
                .orElseThrow();
        int maxSupportHoles = steps.stream()
                .mapToInt(step -> step.holes().supportAllowedHoles())
                .max()
                .orElseThrow();
        int maxOutsideHoles = steps.stream()
                .mapToInt(step -> step.holes().outsideTargetHoles())
                .max()
                .orElseThrow();
        int maxBumpiness = steps.stream()
                .mapToInt(ConstructionSafetyEnvelopeBenchmark.Step::bumpiness)
                .max()
                .orElseThrow();
        int minReachableOutcomes = steps.stream()
                .mapToInt(ConstructionSafetyEnvelopeBenchmark.Step::reachableOutcomes)
                .min()
                .orElseThrow();
        int minNextReachableOutcomes = steps.stream()
                .mapToInt(ConstructionSafetyEnvelopeBenchmark.Step::nextReachableOutcomes)
                .filter(value -> value >= 0)
                .min()
                .orElse(-1);
        double averageReachableOutcomes = steps.stream()
                .mapToInt(ConstructionSafetyEnvelopeBenchmark.Step::reachableOutcomes)
                .average()
                .orElse(0.0);
        int maxSurvivalRank = steps.stream()
                .mapToInt(ConstructionSafetyEnvelopeBenchmark.Step::survivalRank)
                .max()
                .orElseThrow();

        System.out.printf(
                Locale.ROOT,
                "# construction_safety_envelope_total witnesses=%d steps=%d min_headroom=%d "
                        + "max_column_height=%d max_aggregate_height=%d max_raw_holes=%d "
                        + "max_non_forbidden_holes=%d max_required_holes=%d "
                        + "max_forbidden_holes=%d max_support_holes=%d max_outside_holes=%d "
                        + "max_bumpiness=%d min_reachable_outcomes=%d "
                        + "min_next_reachable_outcomes=%d avg_reachable_outcomes=%.2f "
                        + "max_survival_rank=%d%n",
                envelopes.size(),
                steps.size(),
                minHeadroom,
                maxColumnHeight,
                maxAggregateHeight,
                maxRawHoles,
                maxNonForbiddenHoles,
                maxRequiredHoles,
                maxForbiddenHoles,
                maxSupportHoles,
                maxOutsideHoles,
                maxBumpiness,
                minReachableOutcomes,
                minNextReachableOutcomes,
                averageReachableOutcomes,
                maxSurvivalRank);
    }

    private static void printWitnessAudit(
            long seed,
            ShapeWitnessConstraintAudit.Result audit) {
        for (ShapeWitnessConstraintAudit.Step step : audit.steps()) {
            System.out.println(formatWitnessAuditLine(seed, step));
        }

        System.out.printf(
                Locale.ROOT,
                "# shape_witness_audit seed=%d steps=%d action_only_steps=%d "
                        + "outside_top5_steps=%d danger_suppressed_steps=%d "
                        + "safety_rejected_steps=%d runtime_selected_witness_steps=%d "
                        + "forbidden_excluded_allowed_current_profile_steps=%d "
                        + "forbidden_excluded_allowed_adjusted_profile_steps=%d "
                        + "forbidden_excluded_danger_steps=%d "
                        + "avg_survival_rank=%.2f max_survival_rank=%d "
                        + "final_raw_holes=%d final_required_holes=%d "
                        + "final_forbidden_holes=%d final_support_holes=%d "
                        + "final_outside_holes=%d first_blocked_step=%d first_blocker=%s%n",
                seed,
                audit.steps().size(),
                audit.actionOnlySteps(),
                audit.outsideTopFiveSteps(),
                audit.dangerSuppressedSteps(),
                audit.safetyRejectedSteps(),
                audit.runtimeSelectedWitnessSteps(),
                audit.forbiddenExcludedAllowedCurrentProfileSteps(),
                audit.forbiddenExcludedAllowedAdjustedProfileSteps(),
                audit.forbiddenExcludedDangerSteps(),
                audit.averageSurvivalRank(),
                audit.maxSurvivalRank(),
                audit.finalWitnessHoles().totalHoles(),
                audit.finalWitnessHoles().requiredHoles(),
                audit.finalWitnessHoles().forbiddenHoles(),
                audit.finalWitnessHoles().supportAllowedHoles(),
                audit.finalWitnessHoles().outsideTargetHoles(),
                audit.firstBlockedStep(),
                audit.firstBlocker().name().toLowerCase(Locale.ROOT));
    }

    static String formatWitnessAuditLine(
            long seed,
            ShapeWitnessConstraintAudit.Step step) {
        return String.join(
                ",",
                "shape_witness_audit",
                Long.toString(seed),
                Integer.toString(step.step()),
                step.pieceType().name(),
                Integer.toString(step.survivalRank()),
                Integer.toString(step.reachableCandidates()),
                Boolean.toString(step.actionOnly()),
                Boolean.toString(step.inTopFive()),
                step.riskLevel().name().toLowerCase(Locale.ROOT),
                step.riskProfile().configValue(),
                Integer.toString(step.baselineHeadroom()),
                Integer.toString(step.baselineHoles()),
                Boolean.toString(step.safety().allowed()),
                Integer.toString(step.safety().clearedLinesDelta()),
                Integer.toString(step.safety().aggregateHeightDelta()),
                Integer.toString(step.safety().holesDelta()),
                Integer.toString(step.safety().bumpinessDelta()),
                Boolean.toString(step.dangerSuppressed()),
                Boolean.toString(step.runtimeSelectedWitnessOutcome()),
                step.blocker().name().toLowerCase(Locale.ROOT),
                Integer.toString(step.beforeProgress().matchedRequiredCells()),
                Integer.toString(step.beforeProgress().forbiddenOccupiedCells()),
                Integer.toString(step.beforeProgress().visualErrorCells()),
                Integer.toString(step.survivalProgress().matchedRequiredCells()),
                Integer.toString(step.survivalProgress().forbiddenOccupiedCells()),
                Integer.toString(step.survivalProgress().visualErrorCells()),
                Integer.toString(step.witnessProgress().matchedRequiredCells()),
                Integer.toString(step.witnessProgress().forbiddenOccupiedCells()),
                Integer.toString(step.witnessProgress().visualErrorCells()),
                Integer.toString(step.survivalHoles().totalHoles()),
                Integer.toString(step.survivalHoles().requiredHoles()),
                Integer.toString(step.survivalHoles().forbiddenHoles()),
                Integer.toString(step.survivalHoles().supportAllowedHoles()),
                Integer.toString(step.survivalHoles().outsideTargetHoles()),
                Integer.toString(step.witnessHoles().totalHoles()),
                Integer.toString(step.witnessHoles().requiredHoles()),
                Integer.toString(step.witnessHoles().forbiddenHoles()),
                Integer.toString(step.witnessHoles().supportAllowedHoles()),
                Integer.toString(step.witnessHoles().outsideTargetHoles()),
                Integer.toString(step.forbiddenExcludedHolesDelta()),
                Boolean.toString(step.forbiddenExcludedSafetyAllowedCurrentProfile()),
                step.forbiddenExcludedRiskLevel().name().toLowerCase(Locale.ROOT),
                step.forbiddenExcludedRiskProfile().configValue(),
                Boolean.toString(step.forbiddenExcludedSafetyAllowedAdjustedProfile()));
    }

    private static void printWitnessAuditSummary(List<WitnessAuditResult> audits) {
        if (audits.isEmpty()) {
            return;
        }

        long steps = audits.stream()
                .mapToLong(audit -> audit.audit().steps().size())
                .sum();
        long actionOnly = audits.stream()
                .mapToLong(audit -> audit.audit().actionOnlySteps())
                .sum();
        long outsideTopFive = audits.stream()
                .mapToLong(audit -> audit.audit().outsideTopFiveSteps())
                .sum();
        long dangerSuppressed = audits.stream()
                .mapToLong(audit -> audit.audit().dangerSuppressedSteps())
                .sum();
        long safetyRejected = audits.stream()
                .mapToLong(audit -> audit.audit().safetyRejectedSteps())
                .sum();
        long runtimeSelected = audits.stream()
                .mapToLong(audit -> audit.audit().runtimeSelectedWitnessSteps())
                .sum();
        long forbiddenExcludedAllowedCurrentProfile = audits.stream()
                .mapToLong(audit ->
                        audit.audit().forbiddenExcludedAllowedCurrentProfileSteps())
                .sum();
        long forbiddenExcludedAllowedAdjustedProfile = audits.stream()
                .mapToLong(audit ->
                        audit.audit().forbiddenExcludedAllowedAdjustedProfileSteps())
                .sum();
        long forbiddenExcludedDanger = audits.stream()
                .mapToLong(audit -> audit.audit().forbiddenExcludedDangerSteps())
                .sum();
        long rawHoleObservations = holeObservationSum(audits, HoleMetric.TOTAL);
        long requiredHoleObservations = holeObservationSum(audits, HoleMetric.REQUIRED);
        long forbiddenHoleObservations = holeObservationSum(audits, HoleMetric.FORBIDDEN);
        long supportHoleObservations = holeObservationSum(audits, HoleMetric.SUPPORT);
        long outsideHoleObservations = holeObservationSum(audits, HoleMetric.OUTSIDE);
        long topFiveBlockers = blockerCount(audits, ShapeWitnessConstraintAudit.Blocker.TOP_FIVE);
        long dangerBlockers =
                blockerCount(audits, ShapeWitnessConstraintAudit.Blocker.DANGER_SUPPRESSION);
        long safetyBlockers =
                blockerCount(audits, ShapeWitnessConstraintAudit.Blocker.SAFETY_BUDGET);
        long greedyBlockers =
                blockerCount(audits, ShapeWitnessConstraintAudit.Blocker.GREEDY_SELECTION);

        System.out.printf(
                Locale.ROOT,
                "# shape_witness_audit_total witnesses=%d steps=%d action_only_steps=%d "
                        + "outside_top5_steps=%d danger_suppressed_steps=%d "
                        + "safety_rejected_steps=%d runtime_selected_witness_steps=%d "
                        + "forbidden_excluded_allowed_current_profile_steps=%d "
                        + "forbidden_excluded_allowed_adjusted_profile_steps=%d "
                        + "forbidden_excluded_danger_steps=%d "
                        + "witness_raw_hole_observations=%d "
                        + "witness_required_hole_observations=%d "
                        + "witness_forbidden_hole_observations=%d "
                        + "witness_support_hole_observations=%d "
                        + "witness_outside_hole_observations=%d "
                        + "top5_blockers=%d danger_blockers=%d safety_blockers=%d "
                        + "greedy_blockers=%d%n",
                audits.size(),
                steps,
                actionOnly,
                outsideTopFive,
                dangerSuppressed,
                safetyRejected,
                runtimeSelected,
                forbiddenExcludedAllowedCurrentProfile,
                forbiddenExcludedAllowedAdjustedProfile,
                forbiddenExcludedDanger,
                rawHoleObservations,
                requiredHoleObservations,
                forbiddenHoleObservations,
                supportHoleObservations,
                outsideHoleObservations,
                topFiveBlockers,
                dangerBlockers,
                safetyBlockers,
                greedyBlockers);
    }

    private static long blockerCount(
            List<WitnessAuditResult> audits,
            ShapeWitnessConstraintAudit.Blocker blocker) {
        return audits.stream()
                .flatMap(audit -> audit.audit().steps().stream())
                .filter(step -> step.blocker() == blocker)
                .count();
    }

    private static long holeObservationSum(
            List<WitnessAuditResult> audits,
            HoleMetric metric) {
        return audits.stream()
                .flatMap(audit -> audit.audit().steps().stream())
                .map(ShapeWitnessConstraintAudit.Step::witnessHoles)
                .mapToLong(metric::value)
                .sum();
    }

    private static void printSummary(
            Configuration configuration,
            List<SeedResult> results) {
        long cleanCompletions = results.stream()
                .filter(result -> result.result().cleanCompletionFound())
                .count();
        int bestVisualError = results.stream()
                .mapToInt(result -> result.result().bestVisualErrorCells())
                .min()
                .orElse(ShapeTarget.HEART.requiredCells());
        long gamesErrorLe8 = countAtMost(results, 8);
        long gamesErrorLe4 = countAtMost(results, 4);
        long gamesError0 = countAtMost(results, 0);
        int maxRequiredZeroForbidden = results.stream()
                .mapToInt(result -> result.result().maxRequiredWithZeroForbidden())
                .max()
                .orElse(0);
        int minForbiddenAtFullRequired = results.stream()
                .mapToInt(result -> result.result().minForbiddenAtFullRequired())
                .filter(value -> value >= 0)
                .min()
                .orElse(-1);
        long expandedStates = results.stream()
                .mapToLong(result -> result.result().expandedStates())
                .sum();
        long generatedPlacements = results.stream()
                .mapToLong(result -> result.result().generatedPlacements())
                .sum();
        long elapsedNanos = results.stream().mapToLong(SeedResult::elapsedNanos).sum();

        System.out.printf(
                Locale.ROOT,
                "# shape_feasibility target=%s games=%d search_depth=%d beam_width=%d "
                        + "clean_completions=%d clean_completion_rate=%.4f "
                        + "best_visual_error_cells=%d games_error_le_8=%d "
                        + "games_error_le_4=%d games_error_0=%d "
                        + "max_required_with_zero_forbidden=%d "
                        + "min_forbidden_at_full_required=%d "
                        + "expanded_states=%d generated_placements=%d elapsed_ms=%.3f%n",
                ShapeTarget.HEART.configValue(),
                configuration.games(),
                configuration.maxPieces(),
                configuration.beamWidth(),
                cleanCompletions,
                (double) cleanCompletions / configuration.games(),
                bestVisualError,
                gamesErrorLe8,
                gamesErrorLe4,
                gamesError0,
                maxRequiredZeroForbidden,
                minForbiddenAtFullRequired,
                expandedStates,
                generatedPlacements,
                elapsedNanos / 1_000_000.0);
    }

    private static long countAtMost(List<SeedResult> results, int threshold) {
        return results.stream()
                .filter(result -> result.result().bestVisualErrorCells() <= threshold)
                .count();
    }

    private static List<TetrominoType> pieceSequence(long seed, int count) {
        BagPieceGenerator generator = new BagPieceGenerator(seed);
        List<TetrominoType> pieces = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            pieces.add(TetrominoType.from(generator.next()));
        }
        return List.copyOf(pieces);
    }

    private static String actions(ShapeConstructionFeasibilityBenchmark.WitnessStep step) {
        return step.plan().actions().stream()
                .map(Enum::name)
                .reduce((left, right) -> left + ">" + right)
                .orElse("");
    }

    private record SeedResult(
            long seed,
            ShapeConstructionFeasibilityBenchmark.Result result,
            long elapsedNanos) {
    }

    private record WitnessAuditResult(
            long seed,
            ShapeWitnessConstraintAudit.Result audit) {
    }

    private record EnvelopeResult(
            long seed,
            ConstructionSafetyEnvelopeBenchmark.Result envelope) {
    }

    private enum HoleMetric {
        TOTAL {
            @Override
            int value(ShapeWitnessConstraintAudit.HoleBreakdown holes) {
                return holes.totalHoles();
            }
        },
        REQUIRED {
            @Override
            int value(ShapeWitnessConstraintAudit.HoleBreakdown holes) {
                return holes.requiredHoles();
            }
        },
        FORBIDDEN {
            @Override
            int value(ShapeWitnessConstraintAudit.HoleBreakdown holes) {
                return holes.forbiddenHoles();
            }
        },
        SUPPORT {
            @Override
            int value(ShapeWitnessConstraintAudit.HoleBreakdown holes) {
                return holes.supportAllowedHoles();
            }
        },
        OUTSIDE {
            @Override
            int value(ShapeWitnessConstraintAudit.HoleBreakdown holes) {
                return holes.outsideTargetHoles();
            }
        };

        abstract int value(ShapeWitnessConstraintAudit.HoleBreakdown holes);
    }

    private record Configuration(int games, int maxPieces, long seed, int beamWidth) {

        static Configuration fromEnvironment() {
            return new Configuration(
                    positiveInt(GAMES_ENV, DEFAULT_GAMES),
                    positiveInt(MAX_PIECES_ENV, DEFAULT_MAX_PIECES),
                    longValue(SEED_ENV, DEFAULT_SEED),
                    positiveInt(BEAM_WIDTH_ENV, DEFAULT_BEAM_WIDTH));
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
            return value == null || value.isBlank() ? defaultValue : Long.parseLong(value);
        }
    }
}
