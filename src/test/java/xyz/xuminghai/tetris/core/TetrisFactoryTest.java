/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.core;

import javafx.scene.paint.Color;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TetrisFactoryTest {

    @Test
    void bagContainsEveryTetrominoExactlyOnce() {
        Set<Class<?>> generatedTypes = new HashSet<>();

        for (int i = 0; i < 7; i++) {
            generatedTypes.add(TetrisFactory.randomCreateTetris().getClass());
        }

        assertEquals(Set.of(
                IBlock.class,
                JBlock.class,
                LBlock.class,
                OBlock.class,
                SBlock.class,
                TBlock.class,
                ZBlock.class), generatedTypes);
    }

    @Test
    void randomColorUsesBrightRgbRange() {
        for (int i = 0; i < 100; i++) {
            Color color = TetrisFactory.randomColor();

            assertTrue(color.getRed() >= 0.5 && color.getRed() <= 1.0);
            assertTrue(color.getGreen() >= 0.5 && color.getGreen() <= 1.0);
            assertTrue(color.getBlue() >= 0.5 && color.getBlue() <= 1.0);
        }
    }
}
