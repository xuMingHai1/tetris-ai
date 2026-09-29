package xyz.xuminghai.tetris.view;

import javafx.animation.AnimationTimer;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import xyz.xuminghai.tetris.core.BoardPosition;
import xyz.xuminghai.tetris.core.Cell;
import xyz.xuminghai.tetris.game.GameWorld;
import xyz.xuminghai.tetris.ai.ShapeTarget;
import xyz.xuminghai.tetris.ai.AiAction;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Draws the read-only landing coordinates supplied by the live game rules. */
final class GhostLandingView extends Canvas {

    private final GameWorld world;
    private final int cellSize;
    private final boolean showTarget;
    private final BooleanSupplier reducedMotion;
    private final List<TrailCell> trail = new ArrayList<>();
    private final AnimationTimer trailTimer = new AnimationTimer() {
        @Override
        public void handle(long now) {
            if (reducedMotion.getAsBoolean()) {
                clearTrail();
                return;
            }
            trail.removeIf(cell -> now - cell.createdAt() >= 240_000_000L);
            redraw();
            if (trail.isEmpty()) stop();
        }
    };

    private record TrailCell(BoardPosition position, long createdAt) { }

    GhostLandingView(GameWorld world, GameContextView board, boolean showTarget,
                     BooleanSupplier reducedMotion) {
        super(board.getWidth(), board.getHeight());
        this.world = world;
        this.cellSize = (int) (board.getWidth() - 1) / world.getCols();
        this.showTarget = showTarget;
        this.reducedMotion = reducedMotion;
        setMouseTransparent(true);
        world.ghostCellsProperty().addListener((_, _, _) -> redraw());
        world.currentCellsProperty().addListener((_, oldCells, newCells) -> {
            if (newCells != null && oldCells != null
                    && isHardDropInProgress() && !reducedMotion.getAsBoolean()) {
                long now = System.nanoTime();
                for (Cell cell : oldCells) {
                    trail.add(new TrailCell(new BoardPosition(cell.getRow(), cell.getCol()), now));
                }
                trailTimer.start();
            }
            redraw();
        });
        world.activeDisplayProperty().addListener((_, _, active) -> {
            if (!active) clearTrail();
        });
        world.aiEnabledProperty().addListener((_, _, enabled) -> {
            if (!enabled) clearTrail();
        });
        world.aiDisplayProperty().addListener((_, _, state) -> {
            if (state.phase().equals("cancelled") || state.phase().equals("paused")
                    || state.phase().equals("game-over")) clearTrail();
        });
        redraw();
    }

    private boolean isHardDropInProgress() {
        GameWorld.AiDisplay display = world.aiDisplayProperty().get();
        return display.phase().equals("executing")
                && display.completedActions() < display.actions().size()
                && display.actions().get(display.completedActions()) == AiAction.HARD_DROP;
    }

    private void clearTrail() {
        trailTimer.stop();
        trail.clear();
        redraw();
    }

    private void redraw() {
        GraphicsContext graphics = getGraphicsContext2D();
        graphics.clearRect(0, 0, getWidth(), getHeight());
        if (showTarget) {
            ShapeTarget target = ShapeTarget.HEART;
            int rowOffset = world.getRows() - target.height();
            int colOffset = (world.getCols() - target.width()) / 2;
            graphics.setStroke(Color.web("#b4a6c9", 0.65));
            graphics.setLineWidth(1);
            graphics.setLineDashes(2, 3);
            target.requiredLocalCells().forEach(cell -> graphics.strokeRoundRect(
                    3 + (cell.col() + colOffset) * cellSize,
                    3 + (cell.row() + rowOffset) * cellSize,
                    cellSize - 5, cellSize - 5, 2, 2));
            graphics.setLineDashes();
        }
        List<BoardPosition> active = Arrays.stream(
                        world.currentCellsProperty().get() == null
                                ? new Cell[0] : world.currentCellsProperty().get())
                .map(cell -> new BoardPosition(cell.getRow(), cell.getCol()))
                .toList();
        long now = System.nanoTime();
        for (TrailCell cell : trail) {
            if (active.contains(cell.position())) continue;
            double remaining = Math.max(0, 1 - (now - cell.createdAt()) / 240_000_000.0);
            graphics.setFill(Color.rgb(134, 162, 196, 0.22 * remaining));
            graphics.fillRoundRect(2 + cell.position().col() * cellSize,
                    2 + cell.position().row() * cellSize,
                    cellSize - 3, cellSize - 3, 2, 2);
        }
        graphics.setStroke(Color.web("#8ca4c5"));
        graphics.setLineWidth(1.5);
        for (BoardPosition cell : world.ghostCellsProperty().get()) {
            if (active.contains(cell)) continue;
            double x = 1 + cell.col() * cellSize;
            double y = 1 + cell.row() * cellSize;
            graphics.strokeRoundRect(x + 2, y + 2, cellSize - 5, cellSize - 5, 2, 2);
        }
    }
}
