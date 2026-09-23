/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai.benchmark;

import xyz.xuminghai.tetris.ai.ShapeConstructionFeasibilityBenchmark;
import xyz.xuminghai.tetris.ai.ShapeProgress;
import xyz.xuminghai.tetris.ai.ShapeTarget;
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

        System.out.println(
                "shape_feasibility,seed,search_depth,beam_width,clean_completion,"
                        + "pieces_to_completion,best_visual_error_cells,"
                        + "max_required_with_zero_forbidden,min_forbidden_at_full_required,"
                        + "best_matched_required_cells,best_forbidden_occupied_cells,"
                        + "best_support_occupied_cells,expanded_states,generated_placements,"
                        + "unique_states,max_frontier_size");

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
            }
        }

        printSummary(configuration, results);
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
