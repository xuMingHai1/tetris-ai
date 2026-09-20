/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.game;

import javafx.scene.paint.Color;
import org.junit.jupiter.api.Test;
import xyz.xuminghai.tetris.core.Cell;

import static org.junit.jupiter.api.Assertions.*;

class GameGridTest {

    @Test
    void constructorRejectsNonPositiveDimensions() {
        assertThrows(IllegalArgumentException.class, () -> new GameGrid(0, 10));
        assertThrows(IllegalArgumentException.class, () -> new GameGrid(20, 0));
    }

    @Test
    void checkCellsHidesCellsAboveBoardAndRejectsInvalidColumns() {
        GameGrid grid = new GameGrid(20, 10);
        Cell visible = cell(0, 4);
        Cell hidden = cell(-1, 5);

        Cell[] checked = grid.checkCells(new Cell[]{hidden, visible});
        assertArrayEquals(new Cell[]{visible}, checked);

        assertEquals(0, grid.checkCells(new Cell[]{cell(-1, -1)}).length);
        assertEquals(0, grid.checkCells(new Cell[]{cell(-1, 10)}).length);
    }

    @Test
    void saveCellsRejectsCollisionAndOutOfBoundsPosition() {
        GameGrid grid = new GameGrid(2, 2);
        Cell occupied = cell(0, 0);

        assertTrue(grid.saveCellsData(new Cell[]{occupied}));
        assertFalse(grid.saveCellsData(new Cell[]{cell(0, 0)}));
        assertFalse(grid.saveCellsData(new Cell[]{cell(2, 0)}));
        assertFalse(grid.saveCellsData(new Cell[]{cell(0, -1)}));
        assertFalse(grid.saveCellsData(new Cell[]{cell(0, 2)}));
        assertFalse(grid.saveCellsData(new Cell[0]));
    }

    @Test
    void fullRowsAreDetectedAndCanBeCleared() {
        GameGrid grid = new GameGrid(2, 4);
        Cell[] fullRow = {
                cell(1, 0),
                cell(1, 1),
                cell(1, 2),
                cell(1, 3)
        };

        assertTrue(grid.saveCellsData(fullRow));
        assertEquals(1, grid.getEliminatableRows(fullRow).size());

        grid.clearCells(fullRow);
        assertTrue(grid.getEliminatableRows(fullRow).isEmpty());
    }

    private static Cell cell(int row, int col) {
        return new Cell(row, col, Color.RED);
    }
}
