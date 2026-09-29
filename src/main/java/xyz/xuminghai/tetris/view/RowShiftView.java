package xyz.xuminghai.tetris.view;

import javafx.animation.AnimationTimer;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import xyz.xuminghai.tetris.core.Cell;
import xyz.xuminghai.tetris.game.GameWorld;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/** Interpolates the row-clear result using the before/after cells published by the game. */
final class RowShiftView extends Canvas {

    private static final long DURATION_NANOS = 240_000_000L;
    private static final Color BACKGROUND = Color.web("#ebeff5");

    private final GameContextView board;
    private final BooleanSupplier reducedMotion;
    private final double pitch;
    private final GraphicsContext graphics;
    private final AnimationTimer timer = new AnimationTimer() {
        @Override
        public void handle(long now) {
            if (reducedMotion.getAsBoolean()) {
                clear();
            }
            else if (now - startedAt >= DURATION_NANOS) {
                clear();
            }
            else {
                redraw((now - startedAt) / (double) DURATION_NANOS);
            }
        }
    };

    private boolean awaitingShift;
    private List<Cell> before = List.of();
    private List<ShiftCell> moving = List.of();
    private long startedAt;

    record ShiftCell(int fromRow, int toRow, int col, Color color) { }

    RowShiftView(GameWorld world, GameContextView board, BooleanSupplier reducedMotion) {
        super(board.getWidth(), board.getHeight());
        this.board = board;
        this.reducedMotion = reducedMotion;
        this.pitch = (board.getWidth() - 1) / world.getCols();
        this.graphics = getGraphicsContext2D();
        setMouseTransparent(true);

        world.linesProperty().addListener((_, oldLines, newLines) -> {
            if (newLines.intValue() > oldLines.intValue()) {
                clear();
                awaitingShift = true;
            }
        });
        world.clearCellProperty().addListener((_, _, cells) -> {
            if (awaitingShift && cells != null) before = List.copyOf(cells);
        });
        world.renderCellProperty().addListener((_, _, cells) -> {
            if (!awaitingShift || cells == null) return;
            awaitingShift = false;
            if (reducedMotion.getAsBoolean()) return;
            List<ShiftCell> shifts = shifts(before, cells, board::displayColor);
            if (shifts.isEmpty()) return;
            clear();
            moving = shifts;
            startedAt = System.nanoTime();
            redraw(0);
            timer.start();
        });
        world.currentCellsProperty().addListener((_, _, cells) -> {
            if (cells != null && overlapsActivePiece(cells)) clear();
        });
        world.activeDisplayProperty().addListener((_, _, active) -> {
            if (!active) clear();
        });
        world.gameOverDisplayProperty().addListener((_, _, over) -> {
            if (over) clear();
        });
    }

    static List<ShiftCell> shifts(List<Cell> before, List<Cell> after,
                                  Function<Cell, Color> displayColor) {
        if (before.isEmpty() || before.size() != after.size()) return List.of();
        List<ShiftCell> result = new ArrayList<>(before.size());
        for (int index = 0; index < before.size(); index++) {
            Cell oldCell = before.get(index);
            Cell newCell = after.get(index);
            if (oldCell.getCol() != newCell.getCol()
                    || !oldCell.getColor().equals(newCell.getColor())
                    || newCell.getRow() <= oldCell.getRow()) return List.of();
            result.add(new ShiftCell(oldCell.getRow(), newCell.getRow(),
                    oldCell.getCol(), displayColor.apply(oldCell)));
        }
        return List.copyOf(result);
    }

    private boolean overlapsActivePiece(Cell[] cells) {
        for (Cell active : cells) {
            for (ShiftCell shift : moving) {
                if (active.getCol() == shift.col()
                        && active.getRow() >= shift.fromRow()
                        && active.getRow() <= shift.toRow()) return true;
            }
        }
        return false;
    }

    private void redraw(double progress) {
        graphics.clearRect(0, 0, getWidth(), getHeight());
        graphics.setFill(BACKGROUND);
        for (ShiftCell cell : moving) {
            graphics.fillRect(1 + cell.col() * pitch, 1 + cell.toRow() * pitch,
                    pitch - 1, pitch - 1);
        }
        double eased = 1 - Math.pow(1 - progress, 3);
        for (ShiftCell cell : moving) {
            double x = 1 + cell.col() * pitch;
            double y = 1 + (cell.fromRow()
                    + (cell.toRow() - cell.fromRow()) * eased) * pitch;
            graphics.setFill(cell.color());
            graphics.fillRoundRect(x, y, pitch - 1, pitch - 1, 2, 2);
            graphics.setFill(Color.rgb(255, 255, 255, 0.28));
            graphics.fillRect(x + 2, y + 1, pitch - 5, 1);
        }
    }

    void clear() {
        timer.stop();
        awaitingShift = false;
        before = List.of();
        moving = List.of();
        graphics.clearRect(0, 0, getWidth(), getHeight());
    }
}
