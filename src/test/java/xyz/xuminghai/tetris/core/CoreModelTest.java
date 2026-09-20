/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.core;

import javafx.scene.paint.Color;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CoreModelTest {

    @Test
    void cellEqualityIncludesPositionAndColor() {
        Cell cell = new Cell(2, 3, Color.RED);
        Cell same = new Cell(2, 3, Color.RED);
        Cell differentPosition = new Cell(2, 4, Color.RED);
        Cell differentColor = new Cell(2, 3, Color.BLUE);

        assertEquals(cell, same);
        assertEquals(cell.hashCode(), same.hashCode());
        assertNotEquals(cell, differentPosition);
        assertNotEquals(cell, differentColor);
        assertTrue(cell.toString().contains("row=2"));
        assertTrue(cell.toString().contains("col=3"));
    }

    @Test
    void blockMovementChangesEveryCellByOnePosition() {
        IBlock block = new IBlock();
        Cell[] initial = block.copy();

        block.downMove();
        block.leftMove();

        Cell[] moved = block.getCells();
        for (int i = 0; i < moved.length; i++) {
            assertEquals(initial[i].getRow() + 1, moved[i].getRow());
            assertEquals(initial[i].getCol() - 1, moved[i].getCol());
        }

        block.rightMove();
        for (int i = 0; i < moved.length; i++) {
            assertEquals(initial[i].getCol(), moved[i].getCol());
        }
    }

    @Test
    void copyCreatesIndependentCells() {
        IBlock block = new IBlock();
        Cell[] copy = block.copy();

        assertNotSame(block.getCells(), copy);
        for (int i = 0; i < copy.length; i++) {
            assertNotSame(block.getCells()[i], copy[i]);
            assertEquals(block.getCells()[i], copy[i]);
        }

        block.downMove();
        assertNotEquals(block.getCells()[0].getRow(), copy[0].getRow());
    }

    @Test
    void iBlockRotationAlternatesBetweenVerticalAndHorizontalStates() {
        IBlock block = new IBlock();

        block.rotateClockwise();
        assertCell(block.getCells()[0], -3, 5);
        assertCell(block.getCells()[1], -2, 5);
        assertCell(block.getCells()[2], -1, 5);
        assertCell(block.getCells()[3], 0, 5);

        block.rotateCounterClockwise();
        assertCell(block.getCells()[0], -1, 3);
        assertCell(block.getCells()[1], -1, 4);
        assertCell(block.getCells()[2], -1, 5);
        assertCell(block.getCells()[3], -1, 6);
    }

    private static void assertCell(Cell cell, int row, int col) {
        assertEquals(row, cell.getRow());
        assertEquals(col, cell.getCol());
    }
}
