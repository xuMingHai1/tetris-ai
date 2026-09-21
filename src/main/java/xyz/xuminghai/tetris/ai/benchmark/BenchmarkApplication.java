/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai.benchmark;

import xyz.xuminghai.tetris.ai.HeuristicTetrisAgent;
import xyz.xuminghai.tetris.ai.JevDecisionObservation;
import xyz.xuminghai.tetris.ai.JevTetrisAgent;
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
        TetrisAgent primary = createPrimary(configuration.agent(), telemetry);
        TetrisAgent fallback = "jev".equals(configuration.agent()) ? new HeuristicTetrisAgent() : null;

        HeadlessGameRunner runner = new HeadlessGameRunner();
        List<GameBenchmarkResult> results = new ArrayList<>(configuration.games());

        System.out.println(
                "agent,seed,piece_limit,pieces_placed,lines_cleared,decisions,primary_failures,"
                        + "fallback_decisions,avg_decision_ms,max_decision_ms,reached_piece_limit");
        for (int game = 0; game < configuration.games(); game++) {
            long seed = configuration.seed() + game;
            GameBenchmarkResult result =
                    runner.run(seed, configuration.maxPieces(), primary, fallback);
            results.add(result);
            System.out.printf(
                    Locale.ROOT,
                    "%s,%d,%d,%d,%d,%d,%d,%d,%.3f,%.3f,%s%n",
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
                    result.reachedPieceLimit());
        }

        printSummary(configuration, results, telemetry);
    }

    private static TetrisAgent createPrimary(String agent, JevTelemetry telemetry) {
        return switch (agent) {
            case "heuristic" -> new HeuristicTetrisAgent();
            case "jev" -> new JevTetrisAgent(requireApiKey(), telemetry::record);
            default -> throw new IllegalArgumentException(
                    "Unsupported " + AGENT_ENV + " value: " + agent + ". Expected heuristic or jev.");
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

        double averageMillis = decisions == 0 ? 0.0 : decisionNanos / 1_000_000.0 / decisions;
        System.out.printf(
                Locale.ROOT,
                "# summary agent=%s games=%d pieces=%d lines=%d decisions=%d primary_failures=%d "
                        + "fallbacks=%d avg_decision_ms=%.3f max_decision_ms=%.3f%n",
                configuration.agent(),
                configuration.games(),
                pieces,
                lines,
                decisions,
                failures,
                fallbacks,
                averageMillis,
                maxDecisionNanos / 1_000_000.0);

        if (telemetry.samples > 0) {
            System.out.printf(
                    Locale.ROOT,
                    "# jev samples=%d avg_confidence=%.4f input_tokens=%d output_tokens=%d "
                            + "avg_candidates=%.2f%n",
                    telemetry.samples,
                    telemetry.confidenceSum / telemetry.samples,
                    telemetry.inputTokens,
                    telemetry.outputTokens,
                    (double) telemetry.candidateCount / telemetry.samples);
        }
    }

    private static final class JevTelemetry {

        private long samples;
        private double confidenceSum;
        private long inputTokens;
        private long outputTokens;
        private long candidateCount;

        void record(JevDecisionObservation observation) {
            samples++;
            confidenceSum += observation.confidence();
            inputTokens += observation.inputTokens();
            outputTokens += observation.outputTokens();
            candidateCount += observation.candidateCount();
        }
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
