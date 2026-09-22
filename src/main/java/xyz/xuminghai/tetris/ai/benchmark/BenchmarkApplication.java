/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai.benchmark;

import xyz.xuminghai.tetris.ai.ActionProvenanceBenchmark;
import xyz.xuminghai.tetris.ai.AiPlanningAgent;
import xyz.xuminghai.tetris.ai.DeterministicActionPlanningAgent;
import xyz.xuminghai.tetris.ai.HeuristicTetrisAgent;
import xyz.xuminghai.tetris.ai.JevActionPlanningAgent;
import xyz.xuminghai.tetris.ai.JevDecisionObservation;
import xyz.xuminghai.tetris.ai.JevTetrisAgent;
import xyz.xuminghai.tetris.ai.NextPieceHeuristicTetrisAgent;
import xyz.xuminghai.tetris.ai.TetrisAgent;
import xyz.xuminghai.tetris.ai.TuckHunterActionPlanningAgent;
import xyz.xuminghai.tetris.ai.TuckHunterDecisionObservation;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Command-line entry point for reproducible headless AI evaluation.
 *
 * <p>Configuration intentionally uses environment variables so the same packaged module can run
 * locally or in CI without introducing a benchmark framework or committing provider credentials.</p>
 */
public final class BenchmarkApplication {

    static final String AGENT_ENV = "TETRIS_BENCHMARK_AGENT";
    static final String GAMES_ENV = "TETRIS_BENCHMARK_GAMES";
    static final String MAX_PIECES_ENV = "TETRIS_BENCHMARK_MAX_PIECES";
    static final String SEED_ENV = "TETRIS_BENCHMARK_SEED";
    static final String TYPESAFE_API_KEY_ENV = "TYPESAFE_API_KEY";

    private static final int DEFAULT_GAMES = 1;
    private static final int DEFAULT_MAX_PIECES = 50;
    private static final long DEFAULT_SEED = 1L;

    private BenchmarkApplication() {
    }

    public static void main(String[] args) {
        Configuration configuration = Configuration.fromEnvironment();
        JevTelemetry telemetry = new JevTelemetry();
        ActionProvenanceTelemetry provenanceTelemetry = new ActionProvenanceTelemetry();
        TuckHunterTelemetry tuckHunterTelemetry = new TuckHunterTelemetry();
        BenchmarkStrategy strategy =
                createStrategy(
                        configuration.agent(),
                        telemetry,
                        provenanceTelemetry,
                        tuckHunterTelemetry);
        HeadlessGameRunner runner = new HeadlessGameRunner();
        List<GameBenchmarkResult> results = new ArrayList<>(configuration.games());

        System.out.println(
                "agent,seed,piece_limit,pieces_placed,lines_cleared,decisions,primary_failures,"
                        + "fallback_decisions,avg_decision_ms,max_decision_ms,"
                        + "final_aggregate_height,avg_aggregate_height,max_aggregate_height,"
                        + "final_holes,avg_holes,max_holes,"
                        + "final_bumpiness,avg_bumpiness,max_bumpiness,reached_piece_limit");
        for (int game = 0; game < configuration.games(); game++) {
            long seed = configuration.seed() + game;
            telemetry.startGame(seed);
            provenanceTelemetry.startGame(seed);
            tuckHunterTelemetry.startGame(seed);
            GameBenchmarkResult result =
                    strategy.run(runner, seed, configuration.maxPieces());
            results.add(result);
            System.out.printf(
                    Locale.ROOT,
                    "%s,%d,%d,%d,%d,%d,%d,%d,%.3f,%.3f,%d,%.3f,%d,%d,%.3f,%d,%d,%.3f,%d,%s%n",
                    configuration.agent(),
                    result.seed(),
                    result.pieceLimit(),
                    result.piecesPlaced(),
                    result.linesCleared(),
                    result.decisions(),
                    result.primaryFailures(),
                    result.fallbackDecisions(),
                    result.averageDecisionMillis(),
                    result.maxDecisionMillis(),
                    result.boardHealth().finalAggregateHeight(),
                    result.boardHealth().averageAggregateHeight(),
                    result.boardHealth().maxAggregateHeight(),
                    result.boardHealth().finalHoles(),
                    result.boardHealth().averageHoles(),
                    result.boardHealth().maxHoles(),
                    result.boardHealth().finalBumpiness(),
                    result.boardHealth().averageBumpiness(),
                    result.boardHealth().maxBumpiness(),
                    result.reachedPieceLimit());
        }

        printSummary(
                configuration,
                results,
                telemetry,
                provenanceTelemetry,
                tuckHunterTelemetry);
    }

    private static BenchmarkStrategy createStrategy(
            String agent,
            JevTelemetry telemetry,
            ActionProvenanceTelemetry provenanceTelemetry,
            TuckHunterTelemetry tuckHunterTelemetry) {
        return switch (agent) {
            case "heuristic" -> {
                TetrisAgent primary = new HeuristicTetrisAgent();
                yield (runner, seed, pieceLimit) -> runner.run(seed, pieceLimit, primary);
            }
            case "lookahead" -> {
                TetrisAgent primary = new NextPieceHeuristicTetrisAgent();
                yield (runner, seed, pieceLimit) -> runner.run(seed, pieceLimit, primary);
            }
            case "jev" -> {
                TetrisAgent primary = new JevTetrisAgent(requireApiKey(), telemetry::record);
                TetrisAgent fallback = new HeuristicTetrisAgent();
                yield (runner, seed, pieceLimit) ->
                        runner.run(seed, pieceLimit, primary, fallback);
            }
            case "action" -> {
                AiPlanningAgent primary = new DeterministicActionPlanningAgent();
                yield (runner, seed, pieceLimit) ->
                        runner.runPlanning(seed, pieceLimit, primary);
            }
            case "tuck-hunter" -> {
                AiPlanningAgent primary =
                        new TuckHunterActionPlanningAgent(tuckHunterTelemetry::record);
                yield (runner, seed, pieceLimit) ->
                        runner.runPlanning(seed, pieceLimit, primary);
            }
            case "action-provenance" -> {
                AiPlanningAgent primary = snapshot -> {
                    ActionProvenanceBenchmark.Observation observation =
                            ActionProvenanceBenchmark.evaluate(snapshot);
                    provenanceTelemetry.record(observation);
                    return observation.selectedPlan();
                };
                yield (runner, seed, pieceLimit) ->
                        runner.runPlanning(seed, pieceLimit, primary);
            }
            case "jev-action" -> {
                AiPlanningAgent primary =
                        new JevActionPlanningAgent(requireApiKey(), telemetry::record);
                AiPlanningAgent fallback =
                        AiPlanningAgent.fromPlacementAgent(new HeuristicTetrisAgent());
                yield (runner, seed, pieceLimit) ->
                        runner.runPlanning(seed, pieceLimit, primary, fallback);
            }
            default -> throw new IllegalArgumentException(
                    "Unsupported " + AGENT_ENV + " value: " + agent
                            + ". Expected heuristic, lookahead, jev, action, tuck-hunter, action-provenance or jev-action.");
        };
    }

    private static String requireApiKey() {
        String apiKey = System.getenv(TYPESAFE_API_KEY_ENV);
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(TYPESAFE_API_KEY_ENV + " is required for Jev benchmark runs");
        }
        return apiKey;
    }

    private static void printSummary(
            Configuration configuration,
            List<GameBenchmarkResult> results,
            JevTelemetry telemetry,
            ActionProvenanceTelemetry provenanceTelemetry,
            TuckHunterTelemetry tuckHunterTelemetry) {
        long pieces = results.stream().mapToLong(GameBenchmarkResult::piecesPlaced).sum();
        long lines = results.stream().mapToLong(GameBenchmarkResult::linesCleared).sum();
        long decisions = results.stream().mapToLong(GameBenchmarkResult::decisions).sum();
        long failures = results.stream().mapToLong(GameBenchmarkResult::primaryFailures).sum();
        long fallbacks = results.stream().mapToLong(GameBenchmarkResult::fallbackDecisions).sum();
        long decisionNanos = results.stream().mapToLong(GameBenchmarkResult::totalDecisionNanos).sum();
        long maxDecisionNanos = results.stream().mapToLong(GameBenchmarkResult::maxDecisionNanos).max().orElse(0L);
        double aggregateHeightSum = results.stream()
                .mapToDouble(result -> result.boardHealth().averageAggregateHeight() * result.piecesPlaced())
                .sum();
        double holesSum = results.stream()
                .mapToDouble(result -> result.boardHealth().averageHoles() * result.piecesPlaced())
                .sum();
        double bumpinessSum = results.stream()
                .mapToDouble(result -> result.boardHealth().averageBumpiness() * result.piecesPlaced())
                .sum();
        int maxAggregateHeight = results.stream()
                .mapToInt(result -> result.boardHealth().maxAggregateHeight())
                .max()
                .orElse(0);
        int maxHoles = results.stream()
                .mapToInt(result -> result.boardHealth().maxHoles())
                .max()
                .orElse(0);
        int maxBumpiness = results.stream()
                .mapToInt(result -> result.boardHealth().maxBumpiness())
                .max()
                .orElse(0);

        double averageMillis = decisions == 0 ? 0.0 : decisionNanos / 1_000_000.0 / decisions;
        double averageAggregateHeight = pieces == 0 ? 0.0 : aggregateHeightSum / pieces;
        double averageHoles = pieces == 0 ? 0.0 : holesSum / pieces;
        double averageBumpiness = pieces == 0 ? 0.0 : bumpinessSum / pieces;
        System.out.printf(
                Locale.ROOT,
                "# summary agent=%s games=%d pieces=%d lines=%d decisions=%d primary_failures=%d "
                        + "fallbacks=%d avg_decision_ms=%.3f max_decision_ms=%.3f "
                        + "avg_aggregate_height=%.3f max_aggregate_height=%d "
                        + "avg_holes=%.3f max_holes=%d "
                        + "avg_bumpiness=%.3f max_bumpiness=%d%n",
                configuration.agent(),
                configuration.games(),
                pieces,
                lines,
                decisions,
                failures,
                fallbacks,
                averageMillis,
                maxDecisionNanos / 1_000_000.0,
                averageAggregateHeight,
                maxAggregateHeight,
                averageHoles,
                maxHoles,
                averageBumpiness,
                maxBumpiness);

        if (telemetry.samples > 0) {
            System.out.printf(
                    Locale.ROOT,
                    "# jev samples=%d avg_confidence=%.4f min_confidence=%.4f max_confidence=%.4f "
                            + "input_tokens=%d output_tokens=%d avg_candidates=%.2f "
                            + "avg_selected_rank=%.2f top1_rate=%.4f "
                            + "avg_cleared_lines_delta=%.3f avg_aggregate_height_delta=%.3f "
                            + "avg_holes_delta=%.3f avg_bumpiness_delta=%.3f "
                            + "action_only_candidates=%d action_only_candidate_rate=%.4f "
                            + "selected_action_only=%d action_only_selection_rate=%.4f%n",
                    telemetry.samples,
                    telemetry.confidenceSum / telemetry.samples,
                    telemetry.minConfidence,
                    telemetry.maxConfidence,
                    telemetry.inputTokens,
                    telemetry.outputTokens,
                    (double) telemetry.candidateCount / telemetry.samples,
                    (double) telemetry.selectedRankSum / telemetry.samples,
                    (double) telemetry.top1Selections / telemetry.samples,
                    (double) telemetry.clearedLinesDeltaSum / telemetry.samples,
                    (double) telemetry.aggregateHeightDeltaSum / telemetry.samples,
                    (double) telemetry.holesDeltaSum / telemetry.samples,
                    (double) telemetry.bumpinessDeltaSum / telemetry.samples,
                    telemetry.actionOnlyCandidateCount,
                    telemetry.candidateCount == 0
                            ? 0.0
                            : (double) telemetry.actionOnlyCandidateCount / telemetry.candidateCount,
                    telemetry.selectedActionOnly,
                    (double) telemetry.selectedActionOnly / telemetry.samples);

            System.out.println(
                    "jev_decision,seed,decision,confidence,input_tokens,output_tokens,candidate_count,"
                            + "selected_rank,cleared_lines_delta,aggregate_height_delta,holes_delta,bumpiness_delta,"
                            + "action_only_candidate_count,selected_action_only");
            for (JevTrace trace : telemetry.traces) {
                JevDecisionObservation observation = trace.observation();
                System.out.printf(
                        Locale.ROOT,
                        "jev_decision,%d,%d,%.4f,%d,%d,%d,%d,%d,%d,%d,%d,%d,%s%n",
                        trace.seed(),
                        trace.decision(),
                        observation.confidence(),
                        observation.inputTokens(),
                        observation.outputTokens(),
                        observation.candidateCount(),
                        observation.selectedRank(),
                        observation.clearedLinesDelta(),
                        observation.aggregateHeightDelta(),
                        observation.holesDelta(),
                        observation.bumpinessDelta(),
                        observation.actionOnlyCandidateCount(),
                        observation.selectedActionOnly());
            }
        }

        if (tuckHunterTelemetry.samples > 0) {
            double setupRate =
                    (double) tuckHunterTelemetry.setupAvailable / tuckHunterTelemetry.samples;
            double objectiveAppliedRate =
                    (double) tuckHunterTelemetry.objectiveApplied / tuckHunterTelemetry.samples;
            double createdOpportunityRate =
                    (double) tuckHunterTelemetry.createdTopFiveOpportunity / tuckHunterTelemetry.samples;
            double averageSelectedRank =
                    (double) tuckHunterTelemetry.selectedRankSum / tuckHunterTelemetry.samples;
            double averageBestFutureRank =
                    tuckHunterTelemetry.createdTopFiveOpportunity == 0
                            ? 0.0
                            : (double) tuckHunterTelemetry.bestFutureActionOnlyRankSum
                                    / tuckHunterTelemetry.createdTopFiveOpportunity;

            System.out.printf(
                    Locale.ROOT,
                    "# tuck_hunter samples=%d executed_action_only=%d executed_action_only_rate=%.4f "
                            + "setup_available=%d setup_rate=%.4f "
                            + "objective_applied=%d objective_applied_rate=%.4f "
                            + "created_top5_opportunity=%d created_top5_opportunity_rate=%.4f "
                            + "avg_selected_rank=%.2f avg_best_future_action_only_rank=%.2f "
                            + "future_action_only_candidates=%d future_top5_action_only_candidates=%d%n",
                    tuckHunterTelemetry.samples,
                    tuckHunterTelemetry.executedActionOnly,
                    (double) tuckHunterTelemetry.executedActionOnly / tuckHunterTelemetry.samples,
                    tuckHunterTelemetry.setupAvailable,
                    setupRate,
                    tuckHunterTelemetry.objectiveApplied,
                    objectiveAppliedRate,
                    tuckHunterTelemetry.createdTopFiveOpportunity,
                    createdOpportunityRate,
                    averageSelectedRank,
                    averageBestFutureRank,
                    tuckHunterTelemetry.futureActionOnlyCandidates,
                    tuckHunterTelemetry.futureTopFiveActionOnlyCandidates);

            System.out.println(
                    "tuck_hunter_decision,seed,decision,candidate_count,selected_rank,"
                            + "selected_current_action_only,setup_candidates,future_action_only_candidates,"
                            + "future_top5_action_only_candidates,best_future_action_only_rank");
            for (TuckHunterTrace trace : tuckHunterTelemetry.traces) {
                TuckHunterDecisionObservation observation = trace.observation();
                System.out.printf(
                        Locale.ROOT,
                        "tuck_hunter_decision,%d,%d,%d,%d,%s,%d,%d,%d,%d%n",
                        trace.seed(),
                        trace.decision(),
                        observation.candidateCount(),
                        observation.selectedRank(),
                        observation.selectedCurrentActionOnly(),
                        observation.setupCandidates(),
                        observation.selectedFutureActionOnlyCandidates(),
                        observation.selectedFutureTopFiveActionOnlyCandidates(),
                        observation.selectedBestFutureActionOnlyRank());
            }
        }

        if (provenanceTelemetry.samples > 0) {
            double stateRate =
                    (double) provenanceTelemetry.statesWithActionOnly / provenanceTelemetry.samples;
            double candidateRate =
                    provenanceTelemetry.totalActionCandidates == 0
                            ? 0.0
                            : (double) provenanceTelemetry.totalActionOnlyCandidates
                                    / provenanceTelemetry.totalActionCandidates;
            double averageBestRank =
                    provenanceTelemetry.statesWithActionOnly == 0
                            ? 0.0
                            : (double) provenanceTelemetry.bestActionOnlyRankSum
                                    / provenanceTelemetry.statesWithActionOnly;
            double topFiveStateRate =
                    (double) provenanceTelemetry.statesWithTopFiveActionOnly
                            / provenanceTelemetry.samples;

            System.out.printf(
                    Locale.ROOT,
                    "# action_provenance samples=%d states_with_action_only=%d state_rate=%.4f "
                            + "total_action_candidates=%d total_action_only_candidates=%d "
                            + "candidate_rate=%.4f avg_best_action_only_rank=%.2f "
                            + "states_with_top5_action_only=%d top5_state_rate=%.4f "
                            + "top5_action_only_candidates=%d%n",
                    provenanceTelemetry.samples,
                    provenanceTelemetry.statesWithActionOnly,
                    stateRate,
                    provenanceTelemetry.totalActionCandidates,
                    provenanceTelemetry.totalActionOnlyCandidates,
                    candidateRate,
                    averageBestRank,
                    provenanceTelemetry.statesWithTopFiveActionOnly,
                    topFiveStateRate,
                    provenanceTelemetry.topFiveActionOnlyCandidates);

            System.out.println(
                    "action_provenance,seed,decision,total_action_candidates,"
                            + "total_action_only_candidates,best_action_only_rank,"
                            + "top5_action_only_candidates");
            for (ActionProvenanceTrace trace : provenanceTelemetry.traces) {
                ActionProvenanceBenchmark.Observation observation = trace.observation();
                System.out.printf(
                        Locale.ROOT,
                        "action_provenance,%d,%d,%d,%d,%d,%d%n",
                        trace.seed(),
                        trace.decision(),
                        observation.totalActionCandidates(),
                        observation.totalActionOnlyCandidates(),
                        observation.bestActionOnlyRank(),
                        observation.topFiveActionOnlyCandidates());
            }
        }
    }

    private static final class JevTelemetry {

        private final List<JevTrace> traces = new ArrayList<>();
        private long currentSeed;
        private int currentDecision;
        private long samples;
        private double confidenceSum;
        private double minConfidence = Double.POSITIVE_INFINITY;
        private double maxConfidence = Double.NEGATIVE_INFINITY;
        private long inputTokens;
        private long outputTokens;
        private long candidateCount;
        private long selectedRankSum;
        private long top1Selections;
        private long clearedLinesDeltaSum;
        private long aggregateHeightDeltaSum;
        private long holesDeltaSum;
        private long bumpinessDeltaSum;
        private long actionOnlyCandidateCount;
        private long selectedActionOnly;

        void startGame(long seed) {
            currentSeed = seed;
            currentDecision = 0;
        }

        void record(JevDecisionObservation observation) {
            samples++;
            currentDecision++;
            confidenceSum += observation.confidence();
            minConfidence = Math.min(minConfidence, observation.confidence());
            maxConfidence = Math.max(maxConfidence, observation.confidence());
            inputTokens += observation.inputTokens();
            outputTokens += observation.outputTokens();
            candidateCount += observation.candidateCount();
            selectedRankSum += observation.selectedRank();
            if (observation.selectedRank() == 1) {
                top1Selections++;
            }
            clearedLinesDeltaSum += observation.clearedLinesDelta();
            aggregateHeightDeltaSum += observation.aggregateHeightDelta();
            holesDeltaSum += observation.holesDelta();
            bumpinessDeltaSum += observation.bumpinessDelta();
            actionOnlyCandidateCount += observation.actionOnlyCandidateCount();
            if (observation.selectedActionOnly()) {
                selectedActionOnly++;
            }
            traces.add(new JevTrace(currentSeed, currentDecision, observation));
        }
    }

    private static final class ActionProvenanceTelemetry {

        private final List<ActionProvenanceTrace> traces = new ArrayList<>();
        private long currentSeed;
        private int currentDecision;
        private long samples;
        private long statesWithActionOnly;
        private long totalActionCandidates;
        private long totalActionOnlyCandidates;
        private long bestActionOnlyRankSum;
        private long statesWithTopFiveActionOnly;
        private long topFiveActionOnlyCandidates;

        void startGame(long seed) {
            currentSeed = seed;
            currentDecision = 0;
        }

        void record(ActionProvenanceBenchmark.Observation observation) {
            samples++;
            currentDecision++;
            totalActionCandidates += observation.totalActionCandidates();
            totalActionOnlyCandidates += observation.totalActionOnlyCandidates();
            if (observation.hasActionOnlyCandidate()) {
                statesWithActionOnly++;
                bestActionOnlyRankSum += observation.bestActionOnlyRank();
            }
            if (observation.hasTopFiveActionOnlyCandidate()) {
                statesWithTopFiveActionOnly++;
            }
            topFiveActionOnlyCandidates += observation.topFiveActionOnlyCandidates();
            traces.add(new ActionProvenanceTrace(currentSeed, currentDecision, observation));
        }
    }

    private static final class TuckHunterTelemetry {

        private final List<TuckHunterTrace> traces = new ArrayList<>();
        private long currentSeed;
        private int currentDecision;
        private long samples;
        private long executedActionOnly;
        private long setupAvailable;
        private long objectiveApplied;
        private long createdTopFiveOpportunity;
        private long selectedRankSum;
        private long bestFutureActionOnlyRankSum;
        private long futureActionOnlyCandidates;
        private long futureTopFiveActionOnlyCandidates;

        void startGame(long seed) {
            currentSeed = seed;
            currentDecision = 0;
        }

        void record(TuckHunterDecisionObservation observation) {
            samples++;
            currentDecision++;
            if (observation.selectedCurrentActionOnly()) {
                executedActionOnly++;
            }
            if (observation.setupCandidates() > 0) {
                setupAvailable++;
            }
            if (observation.objectiveApplied()) {
                objectiveApplied++;
            }
            if (observation.createsTopFiveOpportunity()) {
                createdTopFiveOpportunity++;
                bestFutureActionOnlyRankSum += observation.selectedBestFutureActionOnlyRank();
            }
            selectedRankSum += observation.selectedRank();
            futureActionOnlyCandidates += observation.selectedFutureActionOnlyCandidates();
            futureTopFiveActionOnlyCandidates += observation.selectedFutureTopFiveActionOnlyCandidates();
            traces.add(new TuckHunterTrace(currentSeed, currentDecision, observation));
        }
    }

    @FunctionalInterface
    private interface BenchmarkStrategy {

        GameBenchmarkResult run(HeadlessGameRunner runner, long seed, int pieceLimit);
    }

    private record JevTrace(long seed, int decision, JevDecisionObservation observation) {
    }

    private record ActionProvenanceTrace(
            long seed,
            int decision,
            ActionProvenanceBenchmark.Observation observation) {
    }

    private record TuckHunterTrace(
            long seed,
            int decision,
            TuckHunterDecisionObservation observation) {
    }

    private record Configuration(String agent, int games, int maxPieces, long seed) {

        static Configuration fromEnvironment() {
            String agent = System.getenv().getOrDefault(AGENT_ENV, "heuristic")
                    .trim()
                    .toLowerCase(Locale.ROOT);
            return new Configuration(
                    agent,
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
            return value == null || value.isBlank() ? defaultValue : Long.parseLong(value);
        }
    }
}
