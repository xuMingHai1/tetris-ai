/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

/**
 * Frozen benchmark-only geometric domain from the HEART prefix diagnostic design.
 * This predicate is not a survival certificate and is never used by a runtime agent.
 */
public final class HeartPrefixDomain {

    private HeartPrefixDomain() {
    }

    /** Uses the same post-spawn gravity and action replay as the existing feasibility evaluator. */
    public static PlacementCandidate replayStep(
            boolean[][] board, xyz.xuminghai.tetris.core.TetrominoType piece, AiPlan plan) {
        return ActionPlanSimulator.requireTerminalPlacement(
                BoardSimulator.snapshotForSpawnedPiece(board, piece), plan);
    }

    /** Recomputes roles at the fixed anchor on a production post-row-clear board. */
    public static Observation observe(boolean[][] board) {
        if (board.length != 20 || board[0].length != 10) {
            throw new IllegalArgumentException("HEART diagnostic requires 20x10");
        }
        ShapeProgress progress = ShapeTarget.HEART.progress(board);
        var metrics = BoardOccupancyMetrics.analyzeWithHoleCells(board);
        var holes = ShapeWitnessConstraintAudit.classifyHoles(ShapeTarget.HEART, board);
        return new Observation(progress, holes, metrics.maxColumnHeight());
    }

    public record Observation(
            ShapeProgress progress,
            ShapeWitnessConstraintAudit.HoleBreakdown holes,
            int maxColumnHeight) {

        public boolean allowed() {
            return progress.forbiddenOccupiedCells() == 0
                    && holes.requiredHoles() == 0
                    && holes.supportAllowedHoles() == 0
                    && holes.outsideTargetHoles() == 0
                    && maxColumnHeight <= ShapeTarget.HEART.height();
        }
    }
}
