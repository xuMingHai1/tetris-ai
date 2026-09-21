/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import xyz.xuminghai.tetris.core.BoardPosition;
import xyz.xuminghai.tetris.core.BoardRules;
import xyz.xuminghai.tetris.core.Cell;
import xyz.xuminghai.tetris.core.Tetris;
import xyz.xuminghai.tetris.core.TetrisFactory;

import java.util.ArrayList;
import java.util.List;

final class BoardSimulator {

    private static final double CLEARED_LINE_WEIGHT = 0.760666;
    private static final double AGGREGATE_HEIGHT_WEIGHT = -0.510066;
    private static final double HOLE_WEIGHT = -0.35663;
    private static final double BUMPINESS_WEIGHT = -0.184483;

    private BoardSimulator() {
    }

    static double evaluate(GameSnapshot snapshot, int rotations, int horizontalShift) {
        boolean[][] occupied = snapshot.occupied();
        List<BoardPosition> cells = rotatedCells(snapshot, occupied, rotations);
        if (cells == null) {
            return Double.NEGATIVE_INFINITY;
        }

        int direction = Integer.signum(horizontalShift);
        for (int step = 0; step < Math.abs(horizontalShift); step++) {
            cells = horizontal(cells, direction);
            if (!BoardRules.canPlace(occupied, snapshot.rows(), snapshot.cols(), cells)) {
                return Double.NEGATIVE_INFINITY;
            }
        }

        List<BoardPosition> next = down(cells);
        while (BoardRules.canPlace(occupied, snapshot.rows(), snapshot.cols(), next)) {
            cells = next;
            next = down(cells);
        }

        if (cells.stream().anyMatch(cell -> cell.row() < 0)) {
            return Double.NEGATIVE_INFINITY;
        }

        for (BoardPosition cell : cells) {
            occupied[cell.row()][cell.col()] = true;
        }

        int clearedLines = BoardRules.clearFullRows(occupied);
        return score(occupied, clearedLines);
    }

    private static List<BoardPosition> rotatedCells(
            GameSnapshot snapshot, boolean[][] occupied, int rotations) {

        Tetris tetris = TetrisFactory.create(snapshot.currentType());
        Cell[] template = tetris.getCells();
        Cell[] cells = new Cell[template.length];
        for (int i = 0; i < cells.length; i++) {
            BoardPosition position = snapshot.currentCells().get(i);
            cells[i] = new Cell(position.row(), position.col(), template[i].getColor());
        }
        tetris.setCells(cells);

        List<BoardPosition> positions = positions(tetris);
        if (!BoardRules.canPlace(occupied, snapshot.rows(), snapshot.cols(), positions)) {
            return null;
        }

        for (int rotation = 0; rotation < rotations; rotation++) {
            tetris.rotateClockwise();
            positions = positions(tetris);
            if (!BoardRules.canPlace(occupied, snapshot.rows(), snapshot.cols(), positions)) {
                return null;
            }
        }
        return positions;
    }

    private static List<BoardPosition> positions(Tetris tetris) {
        List<BoardPosition> positions = new ArrayList<>(4);
        for (Cell cell : tetris.getCells()) {
            positions.add(new BoardPosition(cell.getRow(), cell.getCol()));
        }
        return positions;
    }

    private static List<BoardPosition> horizontal(List<BoardPosition> cells, int delta) {
        return cells.stream().map(cell -> cell.horizontal(delta)).toList();
    }

    private static List<BoardPosition> down(List<BoardPosition> cells) {
        return cells.stream().map(BoardPosition::down).toList();
    }

    private static double score(boolean[][] board, int clearedLines) {
        int rows = board.length;
        int cols = board[0].length;
        int[] heights = new int[cols];
        int aggregateHeight = 0;
        int holes = 0;

        for (int col = 0; col < cols; col++) {
            boolean blockSeen = false;
            for (int row = 0; row < rows; row++) {
                if (board[row][col]) {
                    if (!blockSeen) {
                        heights[col] = rows - row;
                        aggregateHeight += heights[col];
                        blockSeen = true;
                    }
                }
                else if (blockSeen) {
                    holes++;
                }
            }
        }

        int bumpiness = 0;
        for (int col = 0; col < cols - 1; col++) {
            bumpiness += Math.abs(heights[col] - heights[col + 1]);
        }

        return clearedLines * CLEARED_LINE_WEIGHT
                + aggregateHeight * AGGREGATE_HEIGHT_WEIGHT
                + holes * HOLE_WEIGHT
                + bumpiness * BUMPINESS_WEIGHT;
    }
}
