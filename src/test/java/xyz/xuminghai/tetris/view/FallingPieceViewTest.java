/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.view;

import javafx.scene.paint.Color;
import org.junit.jupiter.api.Test;
import xyz.xuminghai.tetris.core.Cell;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FallingPieceViewTest {

    @Test
    void interpolatesOnlyHorizontalTranslationOfTheSamePublishedCells() {
        Cell[] before = {cell(0, 3), cell(1, 3)};
        assertEquals(1, FallingPieceView.horizontalShift(before, new Cell[]{cell(0, 4), cell(1, 4)}));
        assertEquals(-1, FallingPieceView.horizontalShift(before, new Cell[]{cell(0, 2), cell(1, 2)}));
        assertEquals(0, FallingPieceView.horizontalShift(before, new Cell[]{cell(1, 4), cell(2, 4)}));
        assertEquals(0, FallingPieceView.horizontalShift(before, new Cell[]{cell(0, 4), cell(0, 5)}));
        assertEquals(0, FallingPieceView.horizontalShift(before, new Cell[]{cell(0, 4)}));
        assertEquals(0, FallingPieceView.horizontalShift(before,
                new Cell[]{new Cell(0, 4, Color.BLUE), new Cell(1, 4, Color.BLUE)}));
        assertEquals(0, FallingPieceView.horizontalShift(null, before));
        assertEquals(0, FallingPieceView.horizontalShift(before, null));
        // Classifying a visual transition never rewrites the supplied model coordinates.
        assertEquals(3, before[0].getCol());
        assertEquals(0, before[0].getRow());
    }

    private static Cell cell(int row, int col) {
        return new Cell(row, col, Color.RED);
    }
}
