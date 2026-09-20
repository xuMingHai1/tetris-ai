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

    private final MoveCandidateGenerator candidateGenerator = new MoveCandidateGenerator();

    @Override
    public AiMove decide(GameSnapshot snapshot) {
        MoveCandidate best = null;
        for (MoveCandidate candidate : candidateGenerator.generate(snapshot)) {
            if (best == null
                    || candidate.heuristicScore() > best.heuristicScore()
                    || (Double.compare(candidate.heuristicScore(), best.heuristicScore()) == 0
                    && betterTieBreak(candidate.move(), best.move()))) {
                best = candidate;
            }
        }
        return best == null ? AiMove.NONE : best.move();
    }

    private static boolean betterTieBreak(AiMove candidate, AiMove current) {
        int shiftComparison = Integer.compare(
                Math.abs(candidate.horizontalShift()),
                Math.abs(current.horizontalShift()));
        if (shiftComparison != 0) {
            return shiftComparison < 0;
        }
        return candidate.clockwiseRotations() < current.clockwiseRotations();
    }
}
