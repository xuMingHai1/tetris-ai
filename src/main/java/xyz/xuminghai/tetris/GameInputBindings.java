/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris;

import javafx.collections.ObservableMap;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import xyz.xuminghai.tetris.game.GameKeyCodeAction;
import xyz.xuminghai.tetris.game.GameWorld;

/** Shared desktop input assembly, also exercised by the bounded JavaFX interaction check. */
final class GameInputBindings {
    private GameInputBindings() { }

    static Scene install(Scene scene, GameWorld world, GameKeyCodeAction input) {
        // 移动按键键入
        scene.setOnKeyPressed(event -> {
            if (world.getGameActive()) {
                input.keyCodePressed(event.getCode());
            }
        });
        // 移动按键释放
        scene.setOnKeyReleased(event -> input.keyCodeReleased(event.getCode()));

        final ObservableMap<KeyCombination, Runnable> accelerators = scene.getAccelerators();
        // 逆时针旋转
        accelerators.put(new KeyCodeCombination(KeyCode.LEFT), () -> {
            if (world.getGameActive()) {
                world.rotateCounterClockwise();
            }
        });
        // 顺时针旋转
        accelerators.put(new KeyCodeCombination(KeyCode.RIGHT), () -> {
            if (world.getGameActive()) {
                world.rotateClockwise();
            }
        });
        accelerators.put(new KeyCodeCombination(KeyCode.EQUALS), world::levelPlus);
        accelerators.put(new KeyCodeCombination(KeyCode.MINUS), world::levelMinus);
        accelerators.put(new KeyCodeCombination(KeyCode.SPACE), world::startOrPauseGame);
        accelerators.put(new KeyCodeCombination(KeyCode.F2), world::toggleAi);
        accelerators.put(new KeyCodeCombination(KeyCode.TAB, KeyCombination.CONTROL_DOWN), world::switchLanguage);
        return scene;
    }
}
