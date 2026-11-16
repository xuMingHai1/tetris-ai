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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** Bounded check of actual control skins, scene key dispatch and live dashboard layout. */
public final class UiInteractionApplication extends Application {
    private GameWorld world;
    private Scene scene;
    private GameKeyCodeAction input;
    private static final AtomicInteger FAILURES = new AtomicInteger();
    private double takeoverY;
    private double minimumY = Double.POSITIVE_INFINITY;
    private double maximumY = Double.NEGATIVE_INFINITY;
    private int observedPlans;

    public static void main(String[] args) {
        launch(args);
        if (FAILURES.get() > 0) throw new IllegalStateException("UI interaction failures: " + FAILURES.get());
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
            double y = scene.lookup("#auto-play-toggle").localToScene(0, 0).getY();
            minimumY = Math.min(minimumY, y);
            maximumY = Math.max(maximumY, y);
            if (!display.actions().isEmpty()) observedPlans++;
        });
        layout();
        takeoverY = scene.lookup("#auto-play-toggle").localToScene(0, 0).getY();
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
            verifyCoaching(stage);
        });
    }

    private void verifyCoaching(Stage stage) {
        world.toggleAi();
        Button hints = (Button) scene.lookup("#coach-toggle");
        Button place = (Button) scene.lookup("#coach-place");
        hints.fire();
        check(world.coachEnabledProperty().get() && !world.aiEnabledProperty().get()
                && place.isDisabled(), "coaching stays manual and cannot execute while paused");
        world.startOrPauseGame();
        await(() -> world.coachAdviceProperty().get() != null, 100, () -> {
            GameWorld.CoachAdvice original = world.coachAdviceProperty().get();
            check(original.landing().size() == 4 && !place.isDisabled(),
                    "coaching publishes a four-cell landing and enables one-piece execution");
            press(KeyCode.A);
            check(world.coachAdviceProperty().get() == null && place.isDisabled(),
                    "manual movement immediately invalidates advice and its execute button");
            await(() -> world.coachAdviceProperty().get() != null, 100, () -> {
                check(world.coachAdviceProperty().get() != original, "movement obtains fresh advice");
                world.startOrPauseGame();
                check(world.coachAdviceProperty().get() == null && place.isDisabled(),
                        "pause clears advice and disables execution");
                world.startOrPauseGame();
                hints.fire();
                later(150, () -> {
                    check(world.coachAdviceProperty().get() == null,
                            "disabled hints reject an in-flight completion");
                    hints.fire();
                    await(() -> world.coachAdviceProperty().get() != null, 100, () -> {
                        place.fire();
                        check(!world.aiEnabledProperty().get() && world.coachAdviceProperty().get() == null,
                                "one-piece execution consumes advice without enabling auto-play");
                        press(KeyCode.D);
                        check(!world.aiDisplayProperty().get().phase().equals("executing"),
                                "manual input interrupts coached playback immediately");
                        await(() -> world.coachAdviceProperty().get() != null, 100,
                                () -> verifyCoachedLanding(stage, place));
                    });
                });
            });
        });
    }

    private void verifyCoachedLanding(Stage stage, Button place) {
        boolean[] locked = {false};
        GameWorld.CoachAdvice advice = world.coachAdviceProperty().get();
        int initialLines = world.linesProperty().get();
        world.currentCellsProperty().addListener((_, previous, cells) -> {
            if (cells == null && !locked[0]) {
                locked[0] = true;
                List<BoardPosition> landed = Arrays.stream(previous)
                        .map(cell -> new BoardPosition(cell.getRow(), cell.getCol())).toList();
                check(new java.util.HashSet<>(landed).equals(new java.util.HashSet<>(advice.landing())),
                        "actual coached landing matches the displayed recommendation");
            }
        });
        place.fire();
        await(() -> locked[0] && world.currentCellsProperty().get() != null, 160, () -> {
            check(world.linesProperty().get() - initialLines == advice.clearedLines(),
                    "actual cleared lines match the displayed simulation");
            check(!world.aiEnabledProperty().get() && world.coachEnabledProperty().get()
                            && !world.aiDisplayProperty().get().phase().equals("executing"),
                    "coached piece locks normally and the next piece returns to manual control");
            world.toggleAi();
            check(!world.coachEnabledProperty().get() && world.coachAdviceProperty().get() == null,
                    "switching to auto-play clears coaching");
            world.startOrPauseGame();
            System.out.printf(Locale.ROOT, "UI_INTERACTION_RESULT failures=%d plan_updates=%d%n",
                    FAILURES.get(), observedPlans);
            stage.close();
            Platform.exit();
        });
    }

    /** Poll asynchronous FX completions with a fixed deadline; a timeout fails the capture. */
    private void await(BooleanSupplier condition, int remaining, Runnable action) {
        if (condition.getAsBoolean()) {
            action.run();
        }
        else if (remaining == 0) {
            check(false, "timed out waiting for coaching state");
            Platform.exit();
        }
        else {
            later(25, () -> await(condition, remaining - 1, action));
        }
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
        if (!passed) FAILURES.incrementAndGet();
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
                FAILURES.incrementAndGet();
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
