/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

/**
 * Objective progress for one shape target against a resulting board.
 *
 * <p>{@code matchedCells} counts occupied target cells. {@code intrusionCells} counts occupied
 * cells inside the target bounding box where the target expects empty space. Cells outside the
 * target bounding box are deliberately ignored by shape scoring and remain governed by the
 * survival heuristic and objective safety budget.</p>
 */
public record ShapeProgress(
        int targetCells,
        int matchedCells,
        int intrusionCells) {

    public ShapeProgress {
        if (targetCells <= 0) {
            throw new IllegalArgumentException("targetCells must be positive");
        }
        if (matchedCells < 0 || matchedCells > targetCells) {
            throw new IllegalArgumentException("matchedCells must be within targetCells");
        }
        if (intrusionCells < 0) {
            throw new IllegalArgumentException("intrusionCells must not be negative");
        }
    }

    /**
     * Simple silhouette score: target occupancy gained minus wrong occupancy inside the mask.
     */
    public int netScore() {
        return matchedCells - intrusionCells;
    }

    public double completionRate() {
        return (double) matchedCells / targetCells;
    }
}
