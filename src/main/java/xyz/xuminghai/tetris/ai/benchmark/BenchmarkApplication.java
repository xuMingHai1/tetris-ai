/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai.benchmark;

import xyz.xuminghai.tetris.ai.AiPlanningAgent;
import xyz.xuminghai.tetris.ai.DeterministicActionPlanningAgent;
import xyz.xuminghai.tetris.ai.HeuristicTetrisAgent;
import xyz.xuminghai.tetris.ai.JevActionPlanningAgent;
import xyz.xuminghai.tetris.ai.JevDecisionObservation;
import xyz.xuminghai.tetris.ai.JevTetrisAgent;
import xyz.xuminghai.tetris.ai.NextPieceHeuristicTetrisAgent;
import xyz.xuminghai.tetris.ai.TetrisAgent;

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
        BenchmarkStrategy strategy = createStrategy(configuration.agent(), telemetry);
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

        printSummary(configuration, results, telemetry);
    }

    private static BenchmarkStrategy createStrategy(String agent, JevTelemetry telemetry) {
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
                            + ". Expected heuristic, lookahead, jev, action or jev-action.");
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
            JevTelemetry telemetry) {
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
                            + "avg_holes_delta=%.3f avg_bumpiness_delta=%.3f%n",
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
                    (double) telemetry.bumpinessDeltaSum / telemetry.samples);

            System.out.println(
                    "jev_decision,seed,decision,confidence,input_tokens,output_tokens,candidate_count,"
                            + "selected_rank,cleared_lines_delta,aggregate_height_delta,holes_delta,bumpiness_delta");
            for (JevTrace trace : telemetry.traces) {
                JevDecisionObservation observation = trace.observation();
                System.out.printf(
                        Locale.ROOT,
                        "jev_decision,%d,%d,%.4f,%d,%d,%d,%d,%d,%d,%d,%d%n",
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
                        observation.bumpinessDelta());
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
            traces.add(new JevTrace(currentSeed, currentDecision, observation));
        }
    }

    @FunctionalInterface
    private interface BenchmarkStrategy {

        GameBenchmarkResult run(HeadlessGameRunner runner, long seed, int pieceLimit);
    }

    private record JevTrace(long seed, int decision, JevDecisionObservation observation) {
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
