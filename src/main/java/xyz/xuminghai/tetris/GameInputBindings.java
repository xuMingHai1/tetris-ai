/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris;

import javafx.scene.Scene;
import javafx.scene.control.Control;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Window;
import xyz.xuminghai.tetris.game.GameKeyCodeAction;
import xyz.xuminghai.tetris.game.GameWorld;

import java.util.EnumSet;
import java.util.Set;

/** Shared desktop input assembly, also exercised by the bounded JavaFX interaction check. */
final class GameInputBindings {
    private GameInputBindings() { }

    static Scene install(Scene scene, GameWorld world, GameKeyCodeAction input) {
        Set<KeyCode> heldShortcuts = EnumSet.noneOf(KeyCode.class);
        scene.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            KeyCode code = event.getCode();
            if (code == KeyCode.TAB && event.isControlDown()
                    && !event.isAltDown() && !event.isMetaDown() && !event.isShiftDown()) {
                once(heldShortcuts, code, world::switchLanguage);
                event.consume();
                return;
            }
            if (event.isControlDown() || event.isAltDown() || event.isMetaDown()) return;
            if (!event.isShiftDown() && (code == KeyCode.ESCAPE || code == KeyCode.F2)) {
                once(heldShortcuts, code,
                        code == KeyCode.F2 ? world::toggleAi : world::startOrPauseGame);
                event.consume();
                return;
            }
            // Native controls retain Space activation and arrow/Tab navigation.
            if (scene.getFocusOwner() instanceof Control) return;
            if (code == KeyCode.SPACE && !event.isShiftDown()) {
                once(heldShortcuts, code, world::startOrPauseGame);
                event.consume();
            }
            else if (code == KeyCode.EQUALS || code == KeyCode.ADD) {
                world.levelPlus();
                event.consume();
            }
            else if (!event.isShiftDown() && (code == KeyCode.MINUS || code == KeyCode.SUBTRACT)) {
                world.levelMinus();
                event.consume();
            }
            else if (world.getGameActive() && !event.isShiftDown()) {
                switch (code) {
                    case A, S, D -> input.keyCodePressed(code);
                    case LEFT -> world.rotateCounterClockwise();
                    case RIGHT -> world.rotateClockwise();
                    default -> { return; }
                }
                event.consume();
            }
        });
        // Releases must be observed even when a control skin consumes the event.
        scene.addEventFilter(KeyEvent.KEY_RELEASED, event -> {
            heldShortcuts.remove(event.getCode());
            input.keyCodeReleased(event.getCode());
        });
        scene.focusOwnerProperty().addListener((_, _, _) -> input.resetInput());
        world.activeDisplayProperty().addListener((_, _, active) -> {
            if (!active) input.resetInput();
        });
        world.gameOverDisplayProperty().addListener((_, _, over) -> {
            if (over) input.resetInput();
        });
        scene.windowProperty().addListener((_, _, window) -> {
            if (window != null) resetOnFocusLoss(window, input, heldShortcuts);
        });
        if (scene.getWindow() != null) resetOnFocusLoss(scene.getWindow(), input, heldShortcuts);
        return scene;
    }

    private static void once(Set<KeyCode> heldShortcuts, KeyCode code, Runnable action) {
        // Pausing resets movement input, but the shortcut remains held until its release.
        if (heldShortcuts.add(code)) action.run();
    }

    private static void resetOnFocusLoss(Window window, GameKeyCodeAction input,
                                         Set<KeyCode> heldShortcuts) {
        window.focusedProperty().addListener((_, _, focused) -> {
            if (!focused) {
                input.resetInput();
                heldShortcuts.clear();
            }
        });
    }
}
