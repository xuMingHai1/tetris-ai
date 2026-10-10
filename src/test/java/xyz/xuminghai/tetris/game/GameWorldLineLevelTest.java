/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.game;

import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GameWorldLineLevelTest {

    @Test
    void resetsAutomaticProgressAcrossConsecutiveGames() {
        IntegerProperty level = new SimpleIntegerProperty();
        IntegerProperty lines = GameWorld.createLinesProperty(this, () -> level.set(level.get() + 1));

        for (int game = 0; game < 3; game++) {
            for (int threshold = 1; threshold <= 3; threshold++) {
                lines.set(threshold * 10 - 1);
                assertEquals(threshold - 1, level.get());
                lines.set(threshold * 10);
                assertEquals(threshold, level.get());
            }
            // Same reset order as GameOverAnimation.gameOver().
            lines.set(0);
            assertEquals(3, level.get());
            level.set(0);
        }
    }

    @Test
    void preservesManualLevelsAndCurrentGameProgress() {
        IntegerProperty level = new SimpleIntegerProperty(5);
        IntegerProperty lines = GameWorld.createLinesProperty(this, () -> level.set(level.get() + 1));

        lines.set(10);
        assertEquals(6, level.get());
        level.set(2);
        lines.set(11);
        lines.set(19);
        assertEquals(2, level.get());
        // Pause/resume does not write lines; reading/reassigning the count must not upgrade twice.
        lines.set(lines.get());
        assertEquals(2, level.get());
        lines.set(20);
        assertEquals(3, level.get());
        lines.set(0);
        level.set(7);
        lines.set(10);
        assertEquals(8, level.get());
    }
}
