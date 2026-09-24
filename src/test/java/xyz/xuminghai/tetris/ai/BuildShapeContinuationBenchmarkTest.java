/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import org.junit.jupiter.api.Test;
import xyz.xuminghai.tetris.core.TetrominoType;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuildShapeContinuationBenchmarkTest {

    @Test
    void replaysProductionBuildShapeWithDeterministicPreviewSuffix() {
        var result = BuildShapeContinuationBenchmark.rollout(
                ShapeTarget.HEART,
                new boolean[20][10],
                List.of(
                        TetrominoType.T,
                        TetrominoType.I,
                        TetrominoType.O,
                        TetrominoType.S),
                3);

        assertEquals(3, result.requestedDepth());
        assertEquals(3, result.survivedDepth());
        assertTrue(result.reachedHorizon());
        assertEquals(TetrominoType.T, result.steps().getFirst().currentType());
        assertEquals(TetrominoType.I, result.steps().getFirst().nextType());
        assertEquals(TetrominoType.S, result.steps().getLast().nextType());
    }
}
