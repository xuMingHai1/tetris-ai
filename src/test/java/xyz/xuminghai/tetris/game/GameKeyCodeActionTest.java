/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.game;

import javafx.scene.input.KeyCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class GameKeyCodeActionTest {

    @ParameterizedTest
    @EnumSource(value = KeyCode.class, names = {"A", "S", "D"})
    void resetStopsActiveCompensationAndAllowsFreshKeys(KeyCode heldKey) {
        InputHarness input = new InputHarness();
        KeyCode otherKey = heldKey == KeyCode.S ? KeyCode.A : KeyCode.S;
        input.keys.keyCodePressed(heldKey);
        input.keys.keyCodePressed(otherKey);
        input.keys.keyCodeReleased(otherKey);
        assertNotNull(input.timer);
        input.tick();
        int beforeReset = input.moves();

        input.keys.resetInput();
        input.keys.resetInput();
        assertNull(input.timer);
        // Even a retained timer reference must be harmless after clearing its callback.
        input.keys.handle(Long.MAX_VALUE);
        input.tick();
        input.keys.keyCodeReleased(otherKey);
        assertNull(input.timer);
        assertEquals(beforeReset, input.moves());

        input.keys.keyCodePressed(heldKey);
        assertEquals(beforeReset + 1, input.moves());
        input.keys.keyCodeReleased(heldKey);
        assertNull(input.timer);
    }

    @Test
    void resetDiscardsAllHeldKeysBeforeNewCombination() {
        InputHarness input = new InputHarness();
        input.keys.keyCodePressed(KeyCode.A);
        input.keys.keyCodePressed(KeyCode.S);
        input.keys.keyCodePressed(KeyCode.D);
        input.keys.keyCodeReleased(KeyCode.D);
        assertNotNull(input.timer);
        input.keys.resetInput();
        int leftBefore = input.left;
        int downBefore = input.down;
        int rightBefore = input.right;

        input.keys.keyCodePressed(KeyCode.D);
        input.keys.keyCodePressed(KeyCode.D);
        input.keys.keyCodeReleased(KeyCode.D);
        input.keys.keyCodeReleased(KeyCode.S);
        input.keys.keyCodeReleased(KeyCode.A);
        input.tick();
        assertEquals(leftBefore, input.left);
        assertEquals(downBefore, input.down);
        assertEquals(rightBefore + 2, input.right);
        assertNull(input.timer);
    }

    @Test
    void preservesCombinationRepeatAndDeliveredRelease() {
        InputHarness input = new InputHarness();
        input.keys.keyCodePressed(KeyCode.A);
        input.keys.keyCodePressed(KeyCode.S);
        input.keys.keyCodePressed(KeyCode.S);
        assertEquals(2, input.left);
        assertEquals(2, input.down);
        input.keys.keyCodeReleased(KeyCode.S);
        input.tick();
        assertEquals(3, input.left);
        assertEquals(2, input.down);
        input.keys.keyCodeReleased(KeyCode.A);
        input.tick();
        assertEquals(3, input.left);
        assertNull(input.timer);
    }

    @Test
    void freshCompensationAfterResetWaitsForItsOwnRepeatDelay() {
        InputHarness input = new InputHarness();
        input.keys.keyCodePressed(KeyCode.A);
        input.keys.keyCodeReleased(KeyCode.RIGHT);
        input.tick();
        input.keys.resetInput();
        input.keys.keyCodePressed(KeyCode.D);
        long beforeRegistration = System.nanoTime();
        input.keys.keyCodeReleased(KeyCode.RIGHT);
        input.keys.handle(beforeRegistration);
        assertEquals(1, input.right);
        input.tick();
        assertEquals(2, input.right);
    }

    private static final class InputHarness {
        int left, down, right;
        GameTimer timer;
        final GameKeyCodeAction keys = new GameKeyCodeAction(
                () -> left++, () -> down++, () -> right++, value -> timer = value);

        void tick() {
            if (timer != null) {
                timer.handle(System.nanoTime() + TimeUnit.SECONDS.toNanos(1));
            }
        }

        int moves() {
            return left + down + right;
        }
    }
}
