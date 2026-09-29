package xyz.xuminghai.tetris.view;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import xyz.xuminghai.tetris.core.BoardPosition;
import xyz.xuminghai.tetris.core.Cell;
import xyz.xuminghai.tetris.game.GameWorld;
import xyz.xuminghai.tetris.ai.ShapeTarget;

import java.util.Arrays;
import java.util.List;

/** Draws the read-only landing coordinates supplied by the live game rules. */
final class GhostLandingView extends Canvas {

    private final GameWorld world;
    private final int cellSize;
    private final boolean showTarget;

    GhostLandingView(GameWorld world, GameContextView board, boolean showTarget) {
        super(board.getWidth(), board.getHeight());
        this.world = world;
        this.cellSize = (int) (board.getWidth() - 1) / world.getCols();
        this.showTarget = showTarget;
        setMouseTransparent(true);
        world.ghostCellsProperty().addListener((_, _, _) -> redraw());
        world.currentCellsProperty().addListener((_, _, _) -> redraw());
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
        graphics.setStroke(Color.web("#8ca4c5"));
        graphics.setLineWidth(1.5);
        List<BoardPosition> active = Arrays.stream(
                        world.currentCellsProperty().get() == null
                                ? new Cell[0] : world.currentCellsProperty().get())
                .map(cell -> new BoardPosition(cell.getRow(), cell.getCol()))
                .toList();
        for (BoardPosition cell : world.ghostCellsProperty().get()) {
            if (active.contains(cell)) continue;
            double x = 1 + cell.col() * cellSize;
            double y = 1 + cell.row() * cellSize;
            graphics.strokeRoundRect(x + 2, y + 2, cellSize - 5, cellSize - 5, 2, 2);
        }
    }
}
