/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

/**
 * One-ply heuristic Tetris agent.
 *
 * <p>The agent rewards cleared lines and penalizes aggregate height, holes and surface bumpiness.</p>
 */
public final class HeuristicTetrisAgent implements TetrisAgent {

    private static final double CLEARED_LINE_WEIGHT = 0.760666;
    private static final double AGGREGATE_HEIGHT_WEIGHT = -0.510066;
    private static final double HOLE_WEIGHT = -0.35663;
    private static final double BUMPINESS_WEIGHT = -0.184483;

    @Override
    public AiMove decide(GameSnapshot snapshot) {
        AiMove bestMove = AiMove.NONE;
        double bestScore = Double.NEGATIVE_INFINITY;

        for (PlacementCandidate candidate : BoardSimulator.candidates(snapshot)) {
            double score = score(candidate);
            if (score > bestScore
                    || (Double.compare(score, bestScore) == 0 && betterTieBreak(candidate.move(), bestMove))) {
                bestScore = score;
                bestMove = candidate.move();
            }
        }

        return bestScore == Double.NEGATIVE_INFINITY ? AiMove.NONE : bestMove;
    }

    private static double score(PlacementCandidate candidate) {
        return candidate.clearedLines() * CLEARED_LINE_WEIGHT
                + candidate.aggregateHeight() * AGGREGATE_HEIGHT_WEIGHT
                + candidate.holes() * HOLE_WEIGHT
                + candidate.bumpiness() * BUMPINESS_WEIGHT;
    }

    private static boolean betterTieBreak(AiMove candidate, AiMove current) {
        int shiftComparison =
                Integer.compare(Math.abs(candidate.horizontalShift()), Math.abs(current.horizontalShift()));
        if (shiftComparison != 0) {
            return shiftComparison < 0;
        }
        return candidate.clockwiseRotations() < current.clockwiseRotations();
    }
}
