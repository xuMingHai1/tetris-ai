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
import javafx.stage.Stage;
import javafx.util.Duration;
import xyz.xuminghai.tetris.ai.AiPlanningAgentFactory;
import xyz.xuminghai.tetris.ai.PreviewRescueActionPlanningAgent;
import xyz.xuminghai.tetris.game.GameWorld;
import xyz.xuminghai.tetris.view.GameView;

import java.util.Locale;

/** Runs the ordinary desktop world and view for a bounded JavaFX timing capture. */
public final class DesktopAiTimingApplication extends Application {

    private static final String DURATION_ENV = "TETRIS_AI_TRACE_SECONDS";

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        int seconds = Integer.parseInt(System.getenv().getOrDefault(DURATION_ENV, "120"));
        if (seconds < 1 || seconds > 900) {
            throw new IllegalArgumentException(DURATION_ENV + " must be between 1 and 900");
        }
        // Capture computed planner decisions; the factory still enforces the runtime configuration.
        GameWorld world = new GameWorld(
                AiPlanningAgentFactory.fromEnvironment(
                        DesktopAiTimingApplication::tracePreviewRescue), false);
        stage.setScene(new Scene(new GameView(world, getHostServices())));
        stage.show();

        world.toggleAi();
        world.startOrPauseGame();
        System.out.printf("AI_TRACE_SESSION seconds=%d%n", seconds);

        PauseTransition capture = new PauseTransition(Duration.seconds(seconds));
        capture.setOnFinished(event -> {
            if (world.getGameActive()) {
                world.startOrPauseGame();
            }
            stage.close();
            Platform.exit();
        });
        capture.play();
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
