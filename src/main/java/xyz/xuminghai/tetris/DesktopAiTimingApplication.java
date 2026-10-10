/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris;

import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TitledPane;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.text.Font;
import javafx.stage.Stage;
import javafx.util.Duration;
import xyz.xuminghai.tetris.ai.AiPlanningAgentFactory;
import xyz.xuminghai.tetris.ai.PreviewRescueActionPlanningAgent;
import xyz.xuminghai.tetris.core.BagPieceGenerator;
import xyz.xuminghai.tetris.game.GameWorld;
import xyz.xuminghai.tetris.view.DashboardView;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

/** Runs the ordinary desktop world and view for a bounded JavaFX timing capture. */
public final class DesktopAiTimingApplication extends Application {

    private static final String DURATION_ENV = "TETRIS_AI_TRACE_SECONDS";
    private static final String SEED_ENV = "TETRIS_AI_TRACE_SEED";

    private boolean awaitingRowShift;
    private boolean rowShiftCaptured;
    private boolean moveCaptured;
    private boolean landingCaptured;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        int seconds = Integer.parseInt(System.getenv().getOrDefault(DURATION_ENV, "120"));
        if (seconds < 1 || seconds > 900) {
            throw new IllegalArgumentException(DURATION_ENV + " must be between 1 and 900");
        }
        long seed = Long.parseLong(System.getenv().getOrDefault(SEED_ENV, "1000"));
        String captureFont = System.getenv("TETRIS_UI_CAPTURE_FONT");
        if (captureFont != null && !captureFont.isBlank()
                && Font.loadFont(Path.of(captureFont).toUri().toString(), 14) == null) {
            throw new IllegalArgumentException("Unable to load TETRIS_UI_CAPTURE_FONT");
        }
        // Capture computed planner decisions; the factory still enforces the runtime configuration.
        GameWorld world = new GameWorld(
                AiPlanningAgentFactory.fromEnvironment(
                        DesktopAiTimingApplication::tracePreviewRescue), false,
                new BagPieceGenerator(seed));
        int width = Integer.parseInt(System.getenv().getOrDefault("TETRIS_UI_CAPTURE_WIDTH", "950"));
        int height = Integer.parseInt(System.getenv().getOrDefault("TETRIS_UI_CAPTURE_HEIGHT", "850"));
        Scene scene = new Scene(DashboardView.fromEnvironment(world, getHostServices(), width, height),
                width, height);
        stage.setScene(scene);
        stage.show();
        String reducedMotion = System.getenv().getOrDefault("TETRIS_UI_CAPTURE_REDUCED_MOTION", "false");
        if (!reducedMotion.equals("true") && !reducedMotion.equals("false")) {
            throw new IllegalArgumentException("TETRIS_UI_CAPTURE_REDUCED_MOTION must be true or false");
        }
        if (Boolean.parseBoolean(reducedMotion)) {
            ((CheckBox) scene.lookup("#reduced-motion")).fire();
        }

        if (System.getenv("TETRIS_UI_SCREENSHOT_DIR") != null) {
            world.currentCellsProperty().addListener((_, before, after) -> {
                if (!moveCaptured && before != null && after != null && before.length == after.length
                        && before.length > 0 && before[0].getRow() == after[0].getRow()
                        && before[0].getCol() != after[0].getCol()) {
                    moveCaptured = true;
                    PauseTransition midway = new PauseTransition(Duration.millis(15));
                    midway.setOnFinished(_ -> snapshot(scene, "ui-v1-move.png"));
                    midway.play();
                }
                if (!landingCaptured && before != null && before.length == 4 && after == null) {
                    landingCaptured = true;
                    PauseTransition midway = new PauseTransition(Duration.millis(55));
                    midway.setOnFinished(_ -> snapshot(scene, "ui-v1-landing.png"));
                    midway.play();
                }
            });
            world.linesProperty().addListener((_, oldLines, newLines) -> {
                if (!rowShiftCaptured && newLines.intValue() > oldLines.intValue()) {
                    awaitingRowShift = true;
                }
            });
            world.renderCellProperty().addListener((_, _, cells) -> {
                if (!awaitingRowShift || cells == null) return;
                awaitingRowShift = false;
                if (cells.isEmpty()) return;
                rowShiftCaptured = true;
                PauseTransition midway = new PauseTransition(Duration.millis(120));
                midway.setOnFinished(_ -> snapshot(scene, "ui-v1-row-shift.png"));
                midway.play();
            });
        }

        PauseTransition ready = new PauseTransition(Duration.millis(300));
        ready.setOnFinished(event -> {
            snapshot(scene, "ui-v1-ready.png");
            world.toggleAi();
            world.startOrPauseGame();
            System.out.printf(Locale.ROOT, "AI_TRACE_SESSION seconds=%d seed=%d%n", seconds, seed);

            PauseTransition playing = new PauseTransition(Duration.seconds(2));
            playing.setOnFinished(_ -> snapshot(scene, "ui-v1-playing.png"));
            playing.play();

            PauseTransition capture = new PauseTransition(Duration.seconds(seconds));
            capture.setOnFinished(_ -> {
                if (world.getGameActive()) {
                    world.startOrPauseGame();
                }
                if (!world.gameOverDisplayProperty().get()) snapshot(scene, "ui-v1-paused.png");
                if (world.aiEnabledProperty().get()) world.toggleAi();
                snapshot(scene, "ui-v1-manual.png");
                TitledPane shortcuts = (TitledPane) scene.lookup(".shortcuts");
                shortcuts.setAnimated(false);
                shortcuts.setExpanded(true);
                ScrollPane mainScroll = (ScrollPane) scene.lookup(".main-scroll");
                mainScroll.setVvalue(1);
                scene.getRoot().applyCss();
                scene.getRoot().layout();
                snapshot(scene, "ui-v1-bottom.png");
                world.shutdown();
                stage.close();
                Platform.exit();
            });
            capture.play();
        });
        ready.play();
    }

    private static void snapshot(Scene scene, String filename) {
        String directory = System.getenv("TETRIS_UI_SCREENSHOT_DIR");
        if (directory == null || directory.isBlank()) return;
        WritableImage image = scene.snapshot(null);
        PixelReader reader = Objects.requireNonNull(image.getPixelReader());
        BufferedImage pixels = new BufferedImage((int) image.getWidth(),
                (int) image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int row = 0; row < pixels.getHeight(); row++) {
            for (int col = 0; col < pixels.getWidth(); col++) {
                pixels.setRGB(col, row, reader.getArgb(col, row));
            }
        }
        try {
            Path outputDirectory = Path.of(directory);
            Files.createDirectories(outputDirectory);
            Path output = outputDirectory.resolve(filename);
            ImageIO.write(pixels, "png", output.toFile());
        }
        catch (IOException exception) {
            throw new IllegalStateException("Unable to write UI screenshot", exception);
        }
    }

    private static void tracePreviewRescue(PreviewRescueActionPlanningAgent.Observation o) {
        System.out.printf(Locale.ROOT,
                "AI_PREVIEW_RESCUE rank1_evaluated=%s preview_unrecoverable=%s "
                        + "replaced=%s replacement_rank=%d candidates_probed=%d "
                        + "detection_ms=%.3f search_ms=%.3f%n",
                o.rank1Evaluated(), o.previewUnrecoverable(), o.replaced(),
                o.replacementRank(), o.candidatesProbed(),
                o.detectionNanos() / 1_000_000.0, o.searchNanos() / 1_000_000.0);
    }
}
