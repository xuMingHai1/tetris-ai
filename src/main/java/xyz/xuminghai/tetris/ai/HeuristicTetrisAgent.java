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

    @Override
    public AiMove decide(GameSnapshot snapshot) {
        AiMove bestMove = AiMove.NONE;
        double bestScore = Double.NEGATIVE_INFINITY;

        for (int rotations = 0; rotations < snapshot.currentType().rotationStates(); rotations++) {
            for (int shift = -snapshot.cols(); shift <= snapshot.cols(); shift++) {
                double score = BoardSimulator.evaluate(snapshot, rotations, shift);
                if (score > bestScore
                        || (Double.compare(score, bestScore) == 0 && betterTieBreak(rotations, shift, bestMove))) {
                    bestScore = score;
                    bestMove = new AiMove(rotations, shift);
                }
            }
        }

        return bestScore == Double.NEGATIVE_INFINITY ? AiMove.NONE : bestMove;
    }

    private static boolean betterTieBreak(int rotations, int shift, AiMove current) {
        int shiftComparison = Integer.compare(Math.abs(shift), Math.abs(current.horizontalShift()));
        if (shiftComparison != 0) {
            return shiftComparison < 0;
        }
        return rotations < current.clockwiseRotations();
    }
}
