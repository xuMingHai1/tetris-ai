/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import xyz.xuminghai.tetris.core.BoardPosition;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Replays a constructive BUILD_SHAPE witness against the current runtime planning boundaries.
 *
 * <p>The witness itself comes from {@link ShapeConstructionFeasibilityBenchmark}, which proves
 * every step reachable through the production action-native rules. This audit asks a different
 * question: at which runtime policy boundary would the same successful construction path first be
 * rejected? Each step is classified against the current heuristic top-five prior, adaptive risk
 * controller, objective safety budget and greedy BUILD_SHAPE selection.</p>
 *
 * <p>The audit is observational only. It does not change runtime ranking, safety policy or target
 * scoring.</p>
 */
public final class ShapeWitnessConstraintAudit {

    private ShapeWitnessConstraintAudit() {
    }

    public static Result audit(
            ShapeTarget target,
            List<ShapeConstructionFeasibilityBenchmark.WitnessStep> witness,
            int rows,
            int cols) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(witness, "witness");
        if (witness.isEmpty()) {
            throw new IllegalArgumentException("witness must not be empty");
        }
        if (witness.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("witness must not contain null");
        }
        if (rows <= 0 || cols <= 0) {
            throw new IllegalArgumentException("rows and cols must be greater than 0");
        }

        ObjectiveRiskController riskController = ObjectiveRiskController.adaptive();
        BuildShapeActionPlanningAgent runtimePlanner = new BuildShapeActionPlanningAgent(target);
        boolean[][] board = new boolean[rows][cols];
        List<Step> steps = new ArrayList<>(witness.size());

        for (int index = 0; index < witness.size(); index++) {
            ShapeConstructionFeasibilityBenchmark.WitnessStep witnessStep = witness.get(index);
            GameSnapshot snapshot =
                    BoardSimulator.snapshotForSpawnedPiece(board, witnessStep.pieceType());
            List<ActionPlanCandidates.PlannedCandidate> ranked =
                    ActionPlanCandidates.ranked(snapshot);
            if (ranked.isEmpty()) {
                throw new IllegalStateException(
                        "witness step has no reachable action-native candidates: " + (index + 1));
            }

            CandidateMatch witnessMatch = findCandidate(ranked, witnessStep.plan());
            ActionPlanCandidates.PlannedCandidate witnessCandidate = witnessMatch.candidate();
            PlacementCandidate survivalBaseline = ranked.getFirst().placement();
            ObjectiveRiskController.Decision riskDecision =
                    riskController.decide(survivalBaseline);
            ObjectiveSafetyBudget.Assessment safety =
                    riskDecision.profile().budget().assess(
                            survivalBaseline,
                            witnessCandidate.placement());
            HoleBreakdown survivalHoles =
                    classifyHoles(target, survivalBaseline.resultingBoard());
            HoleBreakdown witnessHoles =
                    classifyHoles(target, witnessCandidate.placement().resultingBoard());
            verifyHoleCount("survival baseline", survivalBaseline.holes(), survivalHoles);
            verifyHoleCount("witness", witnessCandidate.placement().holes(), witnessHoles);

            int forbiddenExcludedHolesDelta =
                    witnessHoles.nonForbiddenHoles() - survivalHoles.nonForbiddenHoles();
            boolean forbiddenExcludedSafetyAllowedCurrentProfile =
                    safetyAllowedWithHoleDelta(
                            riskDecision.profile().budget(),
                            safety,
                            forbiddenExcludedHolesDelta);
            ObjectiveRiskController.RiskLevel forbiddenExcludedRiskLevel =
                    ObjectiveRiskController.classify(
                            rows,
                            cols,
                            riskDecision.headroom(),
                            survivalHoles.nonForbiddenHoles());
            ObjectiveRiskProfile forbiddenExcludedRiskProfile =
                    ObjectiveRiskController.adaptiveProfile(forbiddenExcludedRiskLevel);
            boolean forbiddenExcludedSafetyAllowedAdjustedProfile =
                    safetyAllowedWithHoleDelta(
                            forbiddenExcludedRiskProfile.budget(),
                            safety,
                            forbiddenExcludedHolesDelta);

            ActionPlanProvenance.Classification provenance =
                    ActionPlanProvenance.classify(snapshot, ranked);
            boolean actionOnly = provenance.isActionOnly(witnessCandidate);
            boolean inTopFive =
                    witnessMatch.rank() <= BuildShapeActionPlanningAgent.MAX_CURRENT_CANDIDATES;
            boolean dangerSuppressed =
                    riskDecision.level() == ObjectiveRiskController.RiskLevel.DANGER
                            && witnessMatch.rank() != 1;

            AiPlan runtimePlan = runtimePlanner.plan(snapshot);
            CandidateMatch runtimeMatch = findCandidate(ranked, runtimePlan);
            boolean runtimeSelectedWitnessOutcome = Arrays.deepEquals(
                    runtimeMatch.candidate().placement().resultingBoard(),
                    witnessCandidate.placement().resultingBoard());

            Blocker blocker = firstBlocker(
                    inTopFive,
                    dangerSuppressed,
                    safety.allowed(),
                    runtimeSelectedWitnessOutcome);

            ShapeProgress before = target.progress(board);
            ShapeProgress after =
                    target.progress(witnessCandidate.placement().resultingBoard());
            ShapeProgress survivalProgress =
                    target.progress(survivalBaseline.resultingBoard());

            steps.add(new Step(
                    index + 1,
                    witnessStep.pieceType(),
                    witnessStep.plan(),
                    ranked.size(),
                    witnessMatch.rank(),
                    actionOnly,
                    inTopFive,
                    riskDecision.level(),
                    riskDecision.profile(),
                    riskDecision.headroom(),
                    riskDecision.holes(),
                    safety,
                    survivalHoles,
                    witnessHoles,
                    forbiddenExcludedHolesDelta,
                    forbiddenExcludedSafetyAllowedCurrentProfile,
                    forbiddenExcludedRiskLevel,
                    forbiddenExcludedRiskProfile,
                    forbiddenExcludedSafetyAllowedAdjustedProfile,
                    dangerSuppressed,
                    runtimeSelectedWitnessOutcome,
                    blocker,
                    before,
                    survivalProgress,
                    after));

            board = witnessCandidate.placement().resultingBoard();
        }

        ShapeProgress finalProgress = target.progress(board);
        if (!finalProgress.cleanCompletion()) {
            throw new IllegalArgumentException(
                    "witness audit requires a clean-completion witness");
        }
        return summarize(List.copyOf(steps), finalProgress);
    }

    static HoleBreakdown classifyHoles(ShapeTarget target, boolean[][] board) {
        BoardOccupancyMetrics.Analysis metrics =
                BoardOccupancyMetrics.analyzeWithHoleCells(board);
        int required = 0;
        int forbidden = 0;
        int supportAllowed = 0;
        int outsideTarget = 0;

        for (BoardPosition hole : metrics.holeCells()) {
            switch (target.roleAtBoardCell(
                    board.length,
                    board[0].length,
                    hole.row(),
                    hole.col())) {
                case REQUIRED -> required++;
                case FORBIDDEN -> forbidden++;
                case SUPPORT_ALLOWED -> supportAllowed++;
                case OUTSIDE_TARGET -> outsideTarget++;
            }
        }
        return new HoleBreakdown(
                metrics.holes(),
                required,
                forbidden,
                supportAllowed,
                outsideTarget);
    }

    private static void verifyHoleCount(
            String label,
            int placementHoles,
            HoleBreakdown breakdown) {
        if (placementHoles != breakdown.totalHoles()) {
            throw new IllegalStateException(
                    label + " hole classification disagrees with PlacementCandidate: "
                            + placementHoles + " != " + breakdown.totalHoles());
        }
    }

    private static boolean safetyAllowedWithHoleDelta(
            ObjectiveSafetyBudget budget,
            ObjectiveSafetyBudget.Assessment rawAssessment,
            int holeDelta) {
        return holeDelta <= budget.maxAdditionalHoles()
                && rawAssessment.aggregateHeightDelta() <= budget.maxAggregateHeightDelta()
                && rawAssessment.bumpinessDelta() <= budget.maxBumpinessDelta();
    }

    private static CandidateMatch findCandidate(
            List<ActionPlanCandidates.PlannedCandidate> ranked,
            AiPlan plan) {
        for (int index = 0; index < ranked.size(); index++) {
            ActionPlanCandidates.PlannedCandidate candidate = ranked.get(index);
            if (candidate.plan().equals(plan)) {
                return new CandidateMatch(index + 1, candidate);
            }
        }
        throw new IllegalArgumentException(
                "witness/runtime plan is not reachable from the supplied snapshot: " + plan);
    }

    private static Blocker firstBlocker(
            boolean inTopFive,
            boolean dangerSuppressed,
            boolean safetyAllowed,
            boolean runtimeSelectedWitnessOutcome) {
        if (!inTopFive) {
            return Blocker.TOP_FIVE;
        }
        if (dangerSuppressed) {
            return Blocker.DANGER_SUPPRESSION;
        }
        if (!safetyAllowed) {
            return Blocker.SAFETY_BUDGET;
        }
        if (!runtimeSelectedWitnessOutcome) {
            return Blocker.GREEDY_SELECTION;
        }
        return Blocker.NONE;
    }

    private static Result summarize(List<Step> steps, ShapeProgress finalProgress) {
        long actionOnlySteps = steps.stream().filter(Step::actionOnly).count();
        long outsideTopFive = steps.stream().filter(step -> !step.inTopFive()).count();
        long dangerSuppressed = steps.stream().filter(Step::dangerSuppressed).count();
        long safetyRejected = steps.stream().filter(step -> !step.safety().allowed()).count();
        long runtimeSelected = steps.stream().filter(Step::runtimeSelectedWitnessOutcome).count();
        long forbiddenExcludedAllowedCurrentProfile = steps.stream()
                .filter(Step::forbiddenExcludedSafetyAllowedCurrentProfile)
                .count();
        long forbiddenExcludedAllowedAdjustedProfile = steps.stream()
                .filter(Step::forbiddenExcludedSafetyAllowedAdjustedProfile)
                .count();
        long forbiddenExcludedDanger = steps.stream()
                .filter(step -> step.forbiddenExcludedRiskLevel()
                        == ObjectiveRiskController.RiskLevel.DANGER)
                .count();
        double averageRank = steps.stream().mapToInt(Step::survivalRank).average().orElse(0.0);
        int maxRank = steps.stream().mapToInt(Step::survivalRank).max().orElse(0);
        Step firstBlocked = steps.stream()
                .filter(step -> step.blocker() != Blocker.NONE)
                .findFirst()
                .orElse(null);

        return new Result(
                steps,
                finalProgress,
                actionOnlySteps,
                outsideTopFive,
                dangerSuppressed,
                safetyRejected,
                runtimeSelected,
                forbiddenExcludedAllowedCurrentProfile,
                forbiddenExcludedAllowedAdjustedProfile,
                forbiddenExcludedDanger,
                averageRank,
                maxRank,
                steps.getLast().witnessHoles(),
                firstBlocked == null ? 0 : firstBlocked.step(),
                firstBlocked == null ? Blocker.NONE : firstBlocked.blocker());
    }

    public enum Blocker {
        NONE,
        TOP_FIVE,
        DANGER_SUPPRESSION,
        SAFETY_BUDGET,
        GREEDY_SELECTION
    }

    /**
     * One successful witness step measured against current runtime policy.
     */
    public record Step(
            int step,
            xyz.xuminghai.tetris.core.TetrominoType pieceType,
            AiPlan witnessPlan,
            int reachableCandidates,
            int survivalRank,
            boolean actionOnly,
            boolean inTopFive,
            ObjectiveRiskController.RiskLevel riskLevel,
            ObjectiveRiskProfile riskProfile,
            int baselineHeadroom,
            int baselineHoles,
            ObjectiveSafetyBudget.Assessment safety,
            HoleBreakdown survivalHoles,
            HoleBreakdown witnessHoles,
            int forbiddenExcludedHolesDelta,
            boolean forbiddenExcludedSafetyAllowedCurrentProfile,
            ObjectiveRiskController.RiskLevel forbiddenExcludedRiskLevel,
            ObjectiveRiskProfile forbiddenExcludedRiskProfile,
            boolean forbiddenExcludedSafetyAllowedAdjustedProfile,
            boolean dangerSuppressed,
            boolean runtimeSelectedWitnessOutcome,
            Blocker blocker,
            ShapeProgress beforeProgress,
            ShapeProgress survivalProgress,
            ShapeProgress witnessProgress) {

        public Step {
            if (step <= 0
                    || reachableCandidates <= 0
                    || survivalRank <= 0
                    || survivalRank > reachableCandidates
                    || baselineHeadroom < 0
                    || baselineHoles < 0) {
                throw new IllegalArgumentException("invalid witness audit step");
            }
            Objects.requireNonNull(pieceType, "pieceType");
            Objects.requireNonNull(witnessPlan, "witnessPlan");
            Objects.requireNonNull(riskLevel, "riskLevel");
            Objects.requireNonNull(riskProfile, "riskProfile");
            Objects.requireNonNull(safety, "safety");
            Objects.requireNonNull(survivalHoles, "survivalHoles");
            Objects.requireNonNull(witnessHoles, "witnessHoles");
            Objects.requireNonNull(forbiddenExcludedRiskLevel, "forbiddenExcludedRiskLevel");
            Objects.requireNonNull(forbiddenExcludedRiskProfile, "forbiddenExcludedRiskProfile");
            Objects.requireNonNull(blocker, "blocker");
            Objects.requireNonNull(beforeProgress, "beforeProgress");
            Objects.requireNonNull(survivalProgress, "survivalProgress");
            Objects.requireNonNull(witnessProgress, "witnessProgress");
        }
    }

    /**
     * Aggregate blocker diagnostics for one clean-completion witness.
     */
    public record Result(
            List<Step> steps,
            ShapeProgress finalProgress,
            long actionOnlySteps,
            long outsideTopFiveSteps,
            long dangerSuppressedSteps,
            long safetyRejectedSteps,
            long runtimeSelectedWitnessSteps,
            long forbiddenExcludedAllowedCurrentProfileSteps,
            long forbiddenExcludedAllowedAdjustedProfileSteps,
            long forbiddenExcludedDangerSteps,
            double averageSurvivalRank,
            int maxSurvivalRank,
            HoleBreakdown finalWitnessHoles,
            int firstBlockedStep,
            Blocker firstBlocker) {

        public Result {
            steps = List.copyOf(Objects.requireNonNull(steps, "steps"));
            Objects.requireNonNull(finalProgress, "finalProgress");
            Objects.requireNonNull(finalWitnessHoles, "finalWitnessHoles");
            Objects.requireNonNull(firstBlocker, "firstBlocker");
            if (steps.isEmpty()
                    || actionOnlySteps < 0
                    || outsideTopFiveSteps < 0
                    || dangerSuppressedSteps < 0
                    || safetyRejectedSteps < 0
                    || runtimeSelectedWitnessSteps < 0
                    || forbiddenExcludedAllowedCurrentProfileSteps < 0
                    || forbiddenExcludedAllowedAdjustedProfileSteps < 0
                    || forbiddenExcludedDangerSteps < 0
                    || averageSurvivalRank < 0
                    || maxSurvivalRank <= 0
                    || firstBlockedStep < 0) {
                throw new IllegalArgumentException("invalid witness audit result");
            }
            if (!finalProgress.cleanCompletion()) {
                throw new IllegalArgumentException(
                        "witness audit result must end at clean completion");
            }
        }
    }

    /**
     * Raw survival holes partitioned by the semantic role of the empty board cell.
     *
     * <p>Only FORBIDDEN is excluded by the benchmark counterfactual because those cells are required
     * to stay empty for a clean silhouette. REQUIRED, SUPPORT_ALLOWED and OUTSIDE_TARGET remain
     * visible separately rather than being assumed safe.</p>
     */
    public record HoleBreakdown(
            int totalHoles,
            int requiredHoles,
            int forbiddenHoles,
            int supportAllowedHoles,
            int outsideTargetHoles) {

        public HoleBreakdown {
            if (totalHoles < 0
                    || requiredHoles < 0
                    || forbiddenHoles < 0
                    || supportAllowedHoles < 0
                    || outsideTargetHoles < 0
                    || requiredHoles + forbiddenHoles + supportAllowedHoles
                            + outsideTargetHoles != totalHoles) {
                throw new IllegalArgumentException("invalid shape hole breakdown");
            }
        }

        public int nonForbiddenHoles() {
            return totalHoles - forbiddenHoles;
        }
    }

    private record CandidateMatch(
            int rank,
            ActionPlanCandidates.PlannedCandidate candidate) {
    }
}
