/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import java.util.ArrayList;
import java.util.List;

/**
 * One-ply heuristic Tetris agent.
 *
 * <p>The agent rewards cleared lines and penalizes aggregate height, holes and surface bumpiness.
 * The deterministic ranking is also reused as the safety shortlist for remote strategies.</p>
 */
public final class HeuristicTetrisAgent implements TetrisAgent {

    private static final double CLEARED_LINE_WEIGHT = 0.760666;
    private static final double AGGREGATE_HEIGHT_WEIGHT = -0.510066;
    private static final double HOLE_WEIGHT = -0.35663;
    private static final double BUMPINESS_WEIGHT = -0.184483;

    @Override
    public AiMove decide(GameSnapshot snapshot) {
        List<PlacementCandidate> ranked = rankCandidates(snapshot);
        return ranked.isEmpty() ? AiMove.NONE : ranked.getFirst().move();
    }

    /**
     * Returns all legal candidates ordered by this strategy's score and deterministic tie-break.
     *
     * <p>The ranking is package-private so another strategy can reuse the existing safety signal
     * without duplicating the heuristic weights or tie-break semantics.</p>
     */
    static List<PlacementCandidate> rankCandidates(GameSnapshot snapshot) {
        List<PlacementCandidate> candidates = new ArrayList<>(BoardSimulator.candidates(snapshot));
        candidates.sort(HeuristicTetrisAgent::compareCandidates);
        return List.copyOf(candidates);
    }

    private static int compareCandidates(PlacementCandidate left, PlacementCandidate right) {
        int scoreComparison = Double.compare(score(right), score(left));
        if (scoreComparison != 0) {
            return scoreComparison;
        }

        int shiftComparison = Integer.compare(
                Math.abs(left.move().horizontalShift()),
                Math.abs(right.move().horizontalShift()));
        if (shiftComparison != 0) {
            return shiftComparison;
        }
        return Integer.compare(
                left.move().clockwiseRotations(),
                right.move().clockwiseRotations());
    }

    private static double score(PlacementCandidate candidate) {
        return candidate.clearedLines() * CLEARED_LINE_WEIGHT
                + candidate.aggregateHeight() * AGGREGATE_HEIGHT_WEIGHT
                + candidate.holes() * HOLE_WEIGHT
                + candidate.bumpiness() * BUMPINESS_WEIGHT;
    }
}
