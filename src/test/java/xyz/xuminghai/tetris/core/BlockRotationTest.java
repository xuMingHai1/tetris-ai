/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BlockRotationTest {

    @Test
    void twoStateBlocksReturnToOriginalGeometryAfterTwoRotations() {
        for (Supplier<Tetris> supplier : List.<Supplier<Tetris>>of(IBlock::new, SBlock::new, ZBlock::new)) {
            Tetris block = supplier.get();
            Set<String> original = coordinates(block);

            block.rotateClockwise();
            block.rotateClockwise();

            assertEquals(original, coordinates(block));
        }
    }

    @Test
    void fourStateBlocksReturnToOriginalGeometryAfterFourRotations() {
        for (Supplier<Tetris> supplier : List.<Supplier<Tetris>>of(JBlock::new, LBlock::new, TBlock::new)) {
            Tetris block = supplier.get();
            Set<String> original = coordinates(block);

            for (int i = 0; i < 4; i++) {
                block.rotateClockwise();
            }

            assertEquals(original, coordinates(block));
        }
    }

    @Test
    void clockwiseThenCounterClockwiseRestoresFourStateBlocks() {
        for (Supplier<Tetris> supplier : List.<Supplier<Tetris>>of(JBlock::new, LBlock::new, TBlock::new)) {
            Tetris block = supplier.get();
            Set<String> original = coordinates(block);

            block.rotateClockwise();
            block.rotateCounterClockwise();

            assertEquals(original, coordinates(block));
        }
    }

    @Test
    void squareRotationPreservesGeometry() {
        OBlock block = new OBlock();
        Set<String> original = coordinates(block);

        block.rotateClockwise();
        assertEquals(original, coordinates(block));

        block.rotateCounterClockwise();
        assertEquals(original, coordinates(block));
    }

    private static Set<String> coordinates(Tetris block) {
        return List.of(block.getCells()).stream()
                .map(cell -> cell.getRow() + ":" + cell.getCol())
                .collect(Collectors.toSet());
    }
}
