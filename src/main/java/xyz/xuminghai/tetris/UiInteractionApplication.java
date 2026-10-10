/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris;

import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TitledPane;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;
import javafx.util.Duration;
import xyz.xuminghai.tetris.ai.AiPlanningAgent;
import xyz.xuminghai.tetris.ai.HeuristicTetrisAgent;
import xyz.xuminghai.tetris.core.BagPieceGenerator;
import xyz.xuminghai.tetris.game.GameKeyCodeAction;
import xyz.xuminghai.tetris.game.GameWorld;
import xyz.xuminghai.tetris.view.DashboardView;

import java.util.Locale;

/** Bounded check of actual control skins, scene key dispatch and live dashboard layout. */
public final class UiInteractionApplication extends Application {
    private GameWorld world;
    private Scene scene;
    private GameKeyCodeAction input;
    private int failures;
    private double takeoverY;
    private double minimumY = Double.POSITIVE_INFINITY;
    private double maximumY = Double.NEGATIVE_INFINITY;
    private int observedPlans;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        world = new GameWorld(AiPlanningAgent.fromPlacementAgent(new HeuristicTetrisAgent()),
                false, new BagPieceGenerator(1000));
        input = new GameKeyCodeAction(world);
        scene = GameInputBindings.install(new Scene(DashboardView.fromEnvironment(
                world, getHostServices(), 950, 850), 950, 850), world, input);
        stage.setScene(scene);
        stage.show();
        later(300, () -> {
            Button start = (Button) scene.lookup(".primary-button");
            start.requestFocus();
            start.fire();
            check(scene.getFocusOwner() == scene.lookup(".board-frame"), "start returns focus to board");
            press(KeyCode.SPACE);
            check(!world.getGameActive(), "Space pauses after clicking start");
            if (world.getGameActive()) world.startOrPauseGame();
            check(label(".ai-status").equals("手动控制"), "manual pause does not report AI paused");
            world.toggleAi();
            check(label(".ai-status").equals("AI 已暂停"), "enabling AI while paused reports paused");
            world.toggleAi();

            TitledPane shortcuts = (TitledPane) scene.lookup(".shortcuts");
            shortcuts.setAnimated(false);
            shortcuts.setExpanded(true);
            layout();
            CheckBox motion = (CheckBox) scene.lookup("#reduced-motion");
            motion.requestFocus();
            press(KeyCode.SPACE);
            check(motion.isSelected() && !world.getGameActive(), "Space toggles focused checkbox only");
            shortcuts.setExpanded(false);

            world.aiDisplayProperty().addListener((_, _, display) -> {
                layout();
                double y = scene.lookup(".takeover-button").localToScene(0, 0).getY();
                minimumY = Math.min(minimumY, y);
                maximumY = Math.max(maximumY, y);
                if (!display.actions().isEmpty()) observedPlans++;
            });
            layout();
            takeoverY = scene.lookup(".takeover-button").localToScene(0, 0).getY();
            world.toggleAi();
            world.startOrPauseGame();
            later(4000, () -> {
                world.startOrPauseGame();
                check(observedPlans > 0, "observed real AI plans");
                check(maximumY - minimumY < 1 && Math.abs(takeoverY - minimumY) < 1,
                        "takeover stays stationary through thinking, plans and pause; range="
                                + (maximumY - minimumY));
                world.toggleAi();
                world.switchLanguage();
                check(label(".ai-status").equals("Manual control"), "English manual status is truthful");
                world.toggleAi();
                check(label(".ai-status").equals("AI paused"), "English paused AI status is truthful");
                System.out.printf(Locale.ROOT, "UI_INTERACTION_RESULT failures=%d plan_updates=%d%n",
                        failures, observedPlans);
                input.resetInput();
                world.shutdown();
                stage.close();
                Platform.exit();
                if (failures > 0) System.exit(1);
            });
        });
    }

    private String label(String selector) {
        return ((Label) scene.lookup(selector)).getText();
    }

    private void press(KeyCode code) {
        Node target = scene.getFocusOwner();
        if (target == null) target = scene.getRoot();
        Event.fireEvent(target, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code,
                false, false, false, false));
        Event.fireEvent(target, new KeyEvent(KeyEvent.KEY_RELEASED, "", "", code,
                false, false, false, false));
    }

    private void check(boolean passed, String message) {
        System.out.println("UI_INTERACTION " + (passed ? "PASS " : "FAIL ") + message);
        if (!passed) failures++;
    }

    private void layout() {
        scene.getRoot().applyCss();
        scene.getRoot().layout();
    }

    private static void later(int milliseconds, Runnable action) {
        PauseTransition pause = new PauseTransition(Duration.millis(milliseconds));
        pause.setOnFinished(_ -> action.run());
        pause.play();
    }
}
