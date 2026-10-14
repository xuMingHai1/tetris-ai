/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai.benchmark;

import xyz.xuminghai.tetris.ai.HeartPrefixDomain;
import xyz.xuminghai.tetris.ai.ShapeConstructionFeasibilityBenchmark;
import xyz.xuminghai.tetris.core.BagPieceGenerator;
import xyz.xuminghai.tetris.core.TetrominoType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/** One isolated, offline seed; the external supervisor owns the 60-second process budget. */
public final class HeartPrefixDiagnosticApplication {

    private HeartPrefixDiagnosticApplication() {
    }

    public static void main(String[] args) {
        long seed = Long.parseLong(args[0]);
        if (seed < 20000 || seed > 20019) {
            throw new IllegalArgumentException("Only registered development seeds 20000..20019 are allowed");
        }
        BagPieceGenerator generator = new BagPieceGenerator(seed);
        List<TetrominoType> pieces = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            pieces.add(TetrominoType.from(generator.next()));
        }
        System.out.printf("# sequence seed=%d pieces=%s%n", seed,
                pieces.stream().map(Enum::name).collect(Collectors.joining(">")));
        System.out.flush();
        long start = System.nanoTime();
        var result = ShapeConstructionFeasibilityBenchmark.searchHeartPrefixes(pieces, row -> {
            System.out.printf("prefix_depth,%d,%d,%d,%d,%d,%d,%d,%d,%s%n",
                    seed, row.depth(), row.expandedRoots(), row.generated(), row.admitted(),
                    row.unique(), row.retained(), row.pruned(), row.clean());
            System.out.flush();
        });
        if (result.expandedStates() > 2945) {
            throw new IllegalStateException("Registered root bound exceeded");
        }
        replay(result.witness(), seed);
        System.out.printf(Locale.ROOT,
                "prefix_result,%d,%s,%d,%d,%d,%d,%.3f%n", seed,
                result.cleanCompletionFound() ? "REPLAYED_CLEAN" : "BOUNDED_MISS",
                result.searchedPieces(), result.expandedStates(), result.generatedPlacements(),
                result.bestVisualErrorCells(), (System.nanoTime() - start) / 1_000_000.0);
    }

    /** Replays every action from the production spawn-gravity boundary, then checks the fixed anchor. */
    public static List<HeartPrefixDomain.Observation> replay(
            List<ShapeConstructionFeasibilityBenchmark.WitnessStep> witness, long seed) {
        boolean[][] board = new boolean[20][10];
        List<HeartPrefixDomain.Observation> prefixes = new ArrayList<>();
        int index = 0;
        for (var step : witness) {
            var placement = HeartPrefixDomain.replayStep(board, step.pieceType(), step.plan());
            board = placement.resultingBoard();
            var observed = HeartPrefixDomain.observe(board);
            if (!observed.allowed()) {
                throw new IllegalStateException("Witness left registered domain at prefix " + (index + 1));
            }
            prefixes.add(observed);
            var holes = observed.holes();
            System.out.printf("prefix_witness,%d,%d,%s,%s,%d,%d,%d,%d,%d,%d,%d,%d,%d%n",
                    seed, ++index, step.pieceType(),
                    step.plan().actions().stream().map(Enum::name).collect(Collectors.joining(">")),
                    placement.clearedLines(), holes.totalHoles(), holes.requiredHoles(),
                    holes.forbiddenHoles(), holes.supportAllowedHoles(), holes.outsideTargetHoles(),
                    observed.maxColumnHeight(), observed.progress().matchedRequiredCells(),
                    observed.progress().forbiddenOccupiedCells());
        }
        if (!witness.isEmpty() && !prefixes.getLast().progress().cleanCompletion()) {
            throw new IllegalStateException("Witness replay did not complete HEART");
        }
        return List.copyOf(prefixes);
    }
}
