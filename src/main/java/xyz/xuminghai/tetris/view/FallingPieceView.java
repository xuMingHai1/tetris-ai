/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.view;

import javafx.animation.AnimationTimer;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import xyz.xuminghai.tetris.core.Cell;
import xyz.xuminghai.tetris.game.GameWorld;

import java.util.Arrays;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Read-only interpolation of published horizontal moves and a brief post-lock squash. */
final class FallingPieceView extends Canvas {

    private static final long MOVE_NANOS = 45_000_000L;
    private static final long LAND_NANOS = 110_000_000L;
    private static final Color BACKGROUND = Color.web("#ebeff5");

    private final GameWorld world;
    private final GameContextView board;
    private final BooleanSupplier reducedMotion;
    private final double pitch;
    private List<DrawCell> falling = List.of();
    private List<DrawCell> landed = List.of();
    private double horizontalOffset;
    private long moveStarted;
    private long landStarted;
    private final AnimationTimer timer = new AnimationTimer() {
        @Override
        public void handle(long now) {
            if (reducedMotion.getAsBoolean()) {
                finishMotion();
                return;
            }
            if (now - moveStarted >= MOVE_NANOS) horizontalOffset = 0;
            if (now - landStarted >= LAND_NANOS) landed = List.of();
            redraw(now);
            if (horizontalOffset == 0 && landed.isEmpty()) stop();
        }
    };

    private record DrawCell(int row, int col, Color color) { }

    FallingPieceView(GameWorld world, GameContextView board, BooleanSupplier reducedMotion) {
        super(board.getWidth(), board.getHeight());
        this.world = world;
        this.board = board;
        this.reducedMotion = reducedMotion;
        pitch = (board.getWidth() - 1) / world.getCols();
        setMouseTransparent(true);
        world.currentCellsProperty().addListener((_, oldCells, newCells) -> {
            long now = System.nanoTime();
            int shift = horizontalShift(oldCells, newCells);
            double previousOffset = offsetAt(now);
            falling = capture(newCells);
            if (newCells == null) {
                horizontalOffset = 0;
                if (oldCells != null && oldCells.length == 4 && world.getGameActive()
                        && !world.gameOverDisplayProperty().get() && !reducedMotion.getAsBoolean()) {
                    landed = capture(oldCells);
                    landStarted = now;
                    timer.start();
                }
            }
            else if (shift != 0 && !reducedMotion.getAsBoolean()) {
                // Start at the currently painted position when another real move arrives early.
                horizontalOffset = previousOffset - shift * pitch;
                moveStarted = now;
                timer.start();
            }
            else {
                horizontalOffset = 0;
            }
            redraw(now);
        });
        world.linesProperty().addListener((_, oldLines, newLines) -> {
            if (newLines.intValue() > oldLines.intValue()) finishMotion();
        });
        world.activeDisplayProperty().addListener((_, _, active) -> {
            if (!active) finishMotion();
        });
        world.gameOverDisplayProperty().addListener((_, _, over) -> {
            if (over) finishMotion();
        });
        world.aiEnabledProperty().addListener((_, _, _) -> finishMotion());
        world.aiDisplayProperty().addListener((_, _, state) -> {
            if (List.of("cancelled", "paused", "game-over", "closed", "failed").contains(state.phase())) {
                finishMotion();
            }
        });
    }

    /** Only a uniform horizontal translation is interpolated; gravity and rotation remain exact. */
    static int horizontalShift(Cell[] before, Cell[] after) {
        if (before == null || after == null || before.length == 0 || before.length != after.length) return 0;
        int shift = after[0].getCol() - before[0].getCol();
        for (int index = 0; index < before.length; index++) {
            if (before[index].getRow() != after[index].getRow()
                    || after[index].getCol() - before[index].getCol() != shift
                    || !before[index].getColor().equals(after[index].getColor())) return 0;
        }
        return shift;
    }

    private List<DrawCell> capture(Cell[] cells) {
        return cells == null ? List.of() : Arrays.stream(cells)
                .map(cell -> new DrawCell(cell.getRow(), cell.getCol(), board.displayColor(cell)))
                .toList();
    }

    private double offsetAt(long now) {
        double progress = Math.clamp((now - moveStarted) / (double) MOVE_NANOS, 0, 1);
        return horizontalOffset * Math.pow(1 - progress, 3);
    }

    private void redraw(long now) {
        GraphicsContext graphics = getGraphicsContext2D();
        graphics.clearRect(0, 0, getWidth(), getHeight());
        if (!landed.isEmpty()) {
            // Mask only these settled cell interiors; the model board and grid stay untouched.
            graphics.setFill(BACKGROUND);
            for (DrawCell cell : landed) {
                graphics.fillRect(1 + cell.col() * pitch, 1 + cell.row() * pitch, pitch - 1, pitch - 1);
            }
            double progress = Math.clamp((now - landStarted) / (double) LAND_NANOS, 0, 1);
            double scale = 1 - 0.045 * Math.sin(Math.PI * progress);
            double bottom = 1 + (landed.stream().mapToInt(DrawCell::row).max().orElse(0) + 1) * pitch - 1;
            for (DrawCell cell : landed) {
                double y = bottom + (1 + cell.row() * pitch - bottom) * scale;
                drawCell(graphics, cell, 1 + cell.col() * pitch, y, (pitch - 1) * scale);
            }
        }
        double offset = offsetAt(now);
        for (DrawCell cell : falling) {
            drawCell(graphics, cell, 1 + cell.col() * pitch + offset, 1 + cell.row() * pitch, pitch - 1);
        }
    }

    private void drawCell(GraphicsContext graphics, DrawCell cell, double x, double y, double height) {
        graphics.setFill(cell.color());
        graphics.fillRoundRect(x, y, pitch - 1, height, 2, 2);
        graphics.setFill(Color.rgb(255, 255, 255, 0.28));
        graphics.fillRect(x + 2, y + 1, pitch - 5, 1);
    }

    /** Finish at the latest real coordinates, including while paused or with reduced motion enabled. */
    void finishMotion() {
        timer.stop();
        horizontalOffset = 0;
        landed = List.of();
        falling = capture(world.currentCellsProperty().get());
        redraw(System.nanoTime());
    }
}
