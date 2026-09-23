/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

/**
 * Visual progress for one Tetris-aware creative target.
 *
 * <p>Required cells define the visible silhouette. Forbidden cells are visible background that
 * should stay empty. Support cells may be occupied without affecting visual correctness so the
 * silhouette can be physically supported by normal Tetris gravity.</p>
 */
public record ShapeProgress(
        int requiredCells,
        int matchedRequiredCells,
        int forbiddenCells,
        int forbiddenOccupiedCells,
        int supportOccupiedCells) {

    public ShapeProgress {
        if (requiredCells <= 0) {
            throw new IllegalArgumentException("requiredCells must be positive");
        }
        if (matchedRequiredCells < 0 || matchedRequiredCells > requiredCells) {
            throw new IllegalArgumentException(
                    "matchedRequiredCells must be within requiredCells");
        }
        if (forbiddenCells < 0) {
            throw new IllegalArgumentException("forbiddenCells must not be negative");
        }
        if (forbiddenOccupiedCells < 0 || forbiddenOccupiedCells > forbiddenCells) {
            throw new IllegalArgumentException(
                    "forbiddenOccupiedCells must be within forbiddenCells");
        }
        if (supportOccupiedCells < 0) {
            throw new IllegalArgumentException("supportOccupiedCells must not be negative");
        }
    }

    /**
     * Missing required cells plus occupied forbidden cells.
     */
    public int visualErrorCells() {
        return requiredCells - matchedRequiredCells + forbiddenOccupiedCells;
    }

    /**
     * Higher is better. This is equivalent to required cells minus visual errors.
     */
    public int netScore() {
        return matchedRequiredCells - forbiddenOccupiedCells;
    }

    /**
     * Required-cell coverage only. A value of 1.0 is not a clean completion when forbidden cells
     * are occupied.
     */
    public double requiredCompletionRate() {
        return (double) matchedRequiredCells / requiredCells;
    }

    /**
     * True only when the visible silhouette is exact.
     */
    public boolean cleanCompletion() {
        return matchedRequiredCells == requiredCells && forbiddenOccupiedCells == 0;
    }
}
