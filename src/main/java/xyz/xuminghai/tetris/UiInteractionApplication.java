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
import javafx.event.EventType;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TitledPane;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.text.Font;
import javafx.stage.Stage;
import javafx.util.Duration;
import xyz.xuminghai.tetris.ai.AiPlanningAgent;
import xyz.xuminghai.tetris.ai.HeuristicTetrisAgent;
import xyz.xuminghai.tetris.core.BagPieceGenerator;
import xyz.xuminghai.tetris.core.BoardPosition;
import xyz.xuminghai.tetris.game.GameKeyCodeAction;
import xyz.xuminghai.tetris.game.GameWorld;
import xyz.xuminghai.tetris.view.DashboardView;

import java.util.Locale;
import java.util.Arrays;
import java.util.List;
import java.nio.file.Path;

/** Bounded check of actual control skins, scene key dispatch and live dashboard layout. */
public final class UiInteractionApplication extends Application {
    private GameWorld world;
    private Scene scene;
    private GameKeyCodeAction input;
    private static int failures;
    private double takeoverY;
    private double minimumY = Double.POSITIVE_INFINITY;
    private double maximumY = Double.NEGATIVE_INFINITY;
    private int observedPlans;

    public static void main(String[] args) {
        launch(args);
        if (failures > 0) throw new IllegalStateException("UI interaction failures: " + failures);
    }

    @Override
    public void start(Stage stage) {
        String captureFont = System.getenv("TETRIS_UI_CAPTURE_FONT");
        if (captureFont != null && !captureFont.isBlank()
                && Font.loadFont(Path.of(captureFont).toUri().toString(), 14) == null) {
            throw new IllegalArgumentException("Unable to load TETRIS_UI_CAPTURE_FONT");
        }
        world = new GameWorld(AiPlanningAgent.fromPlacementAgent(new HeuristicTetrisAgent()),
                false, new BagPieceGenerator(1000));
        input = new GameKeyCodeAction(world);
        int width = Integer.parseInt(System.getenv().getOrDefault("TETRIS_UI_CAPTURE_WIDTH", "950"));
        int height = Integer.parseInt(System.getenv().getOrDefault("TETRIS_UI_CAPTURE_HEIGHT", "850"));
        DashboardView view = DashboardView.fromEnvironment(world, getHostServices(), width, height);
        scene = GameInputBindings.install(new Scene(view, width, height), world, input);
        stage.setScene(scene);
        stage.show();
        view.requestGameFocus();
        later(300, () -> {
            pressHeld(KeyCode.SPACE, false);
            check(world.getGameActive(), "held Space starts once without immediately pausing");
            press(KeyCode.SPACE);
            check(!world.getGameActive(), "next Space press pauses");
            Button start = (Button) scene.lookup(".primary-button");
            start.requestFocus();
            start.fire();
            check(scene.getFocusOwner() == scene.lookup(".board-frame"), "start returns focus to board");
            press(KeyCode.SPACE);
            check(!world.getGameActive(), "Space pauses after clicking start");
            if (world.getGameActive()) world.startOrPauseGame();
            Button plus = scene.getRoot().lookupAll(".stepper-button").stream()
                    .map(Button.class::cast).filter(button -> button.getText().equals("+"))
                    .findFirst().orElseThrow();
            plus.requestFocus();
            plus.fire();
            int level = world.levelProperty().get();
            press(KeyCode.SPACE);
            check(world.getGameActive() && world.levelProperty().get() == level,
                    "Space resumes after adjusting level, without changing level again");
            press(KeyCode.SPACE);
            key(KeyEvent.KEY_PRESSED, KeyCode.EQUALS, true);
            key(KeyEvent.KEY_RELEASED, KeyCode.EQUALS, true);
            check(world.levelProperty().get() == level + 1, "physical Shift + equals raises level");
            press(KeyCode.SUBTRACT);
            check(world.levelProperty().get() == level, "numpad minus lowers level");
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
            pressHeld(KeyCode.ESCAPE, false);
            check(world.getGameActive(), "Escape works from a focused control, once per hold");
            press(KeyCode.ESCAPE);
            check(!world.getGameActive(), "Escape pauses from a focused control");
            shortcuts.setExpanded(false);
            view.requestGameFocus();
            start.requestFocus();
            press(KeyCode.ENTER);
            check(world.getGameActive() && scene.getFocusOwner() == scene.lookup(".board-frame"),
                    "Enter activates Start once and returns focus to the board");
            later(700, () -> verifyLiveMovement(stage, view));
        });
    }

    private void verifyLiveMovement(Stage stage, DashboardView view) {
        check(world.currentCellsProperty().get() != null, "manual game spawned a live piece");
        int[] columns = columns();
        press(KeyCode.A);
        int[] shifted = columns();
        check(Arrays.equals(Arrays.stream(columns).map(col -> col - 1).toArray(), shifted),
                "A moves the real piece left through scene dispatch");
        press(KeyCode.D);
        check(Arrays.equals(columns, columns()),
                "D moves the real piece right through scene dispatch");
        List<BoardPosition> beforeRotation = positions();
        press(KeyCode.LEFT);
        check(!beforeRotation.equals(positions()), "left arrow rotates the real piece");
        press(KeyCode.RIGHT);
        check(beforeRotation.equals(positions()), "right arrow reverses the rotation");
        press(KeyCode.S);
        check(positions().equals(beforeRotation.stream()
                        .map(cell -> new BoardPosition(cell.row() + 1, cell.col())).toList()),
                "S soft-drops the real piece by one row");
        List<BoardPosition> beforeModifiedKey = positions();
        key(KeyEvent.KEY_PRESSED, KeyCode.A, false, true);
        key(KeyEvent.KEY_RELEASED, KeyCode.A, false, true);
        check(beforeModifiedKey.equals(positions()), "Ctrl + A does not move the piece");
        TitledPane shortcuts = (TitledPane) scene.lookup(".shortcuts");
        shortcuts.setExpanded(true);
        layout();
        ((CheckBox) scene.lookup("#reduced-motion")).requestFocus();
        press(KeyCode.LEFT);
        check(beforeModifiedKey.equals(positions()), "control navigation does not rotate the piece");
        shortcuts.setExpanded(false);
        view.requestGameFocus();
        world.startOrPauseGame();
        pressHeld(KeyCode.F2, false);
        check(world.aiEnabledProperty().get(), "held F2 enables AI once");
        press(KeyCode.F2);
        check(!world.aiEnabledProperty().get(), "next F2 disables AI");
        key(KeyEvent.KEY_PRESSED, KeyCode.TAB, false, true);
        key(KeyEvent.KEY_PRESSED, KeyCode.TAB, false, true);
        key(KeyEvent.KEY_RELEASED, KeyCode.TAB, false, true);
        check(world.languageProperty().get().equals(Locale.ENGLISH), "held Ctrl + Tab switches language once");
        world.switchLanguage();
        view.requestGameFocus();
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
            stage.close();
            Platform.exit();
        });
    }

    private int[] columns() {
        return Arrays.stream(world.currentCellsProperty().get()).mapToInt(cell -> cell.getCol()).toArray();
    }

    private List<BoardPosition> positions() {
        return Arrays.stream(world.currentCellsProperty().get())
                .map(cell -> new BoardPosition(cell.getRow(), cell.getCol())).toList();
    }

    private String label(String selector) {
        return ((Label) scene.lookup(selector)).getText();
    }

    private void press(KeyCode code) {
        key(KeyEvent.KEY_PRESSED, code, false);
        key(KeyEvent.KEY_RELEASED, code, false);
    }

    private void pressHeld(KeyCode code, boolean shift) {
        key(KeyEvent.KEY_PRESSED, code, shift);
        key(KeyEvent.KEY_PRESSED, code, shift);
        key(KeyEvent.KEY_RELEASED, code, shift);
    }

    private void key(EventType<KeyEvent> type, KeyCode code, boolean shift) {
        key(type, code, shift, false);
    }

    private void key(EventType<KeyEvent> type, KeyCode code, boolean shift, boolean control) {
        Node target = scene.getFocusOwner();
        if (target == null) target = scene.getRoot();
        Event.fireEvent(target, new KeyEvent(type, "", "", code,
                shift, control, false, false));
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
        pause.setOnFinished(_ -> {
            try {
                action.run();
            }
            catch (RuntimeException exception) {
                failures++;
                exception.printStackTrace();
                Platform.exit();
            }
        });
        pause.play();
    }

    @Override
    public void stop() {
        if (input != null) input.resetInput();
        if (world != null) world.shutdown();
    }
}
