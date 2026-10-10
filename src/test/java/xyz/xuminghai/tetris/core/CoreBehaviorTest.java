/*
 * Copyright (C) 2024-2026 xuMingHai
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.core;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class CoreBehaviorTest {
    @Test
    void everyIntermediateOrientationHasExpectedOrderedCellsInBothDirections() {
        // Fixtures preserve cell identity as well as occupancy: full-cycle equality alone
        // misses broken intermediate states that later rotations overwrite.
        assertOrientations(new JBlock(), new int[][][]{
                {{-2,4},{-2,5},{-2,6},{-1,6}},
                {{-3,5},{-2,5},{-1,5},{-1,4}},
                {{-2,6},{-2,5},{-2,4},{-3,4}},
                {{-1,5},{-2,5},{-3,5},{-3,6}}});
        assertOrientations(new LBlock(), new int[][][]{
                {{-1,4},{-2,4},{-2,5},{-2,6}},
                {{-3,4},{-3,5},{-2,5},{-1,5}},
                {{-3,6},{-2,6},{-2,5},{-2,4}},
                {{-1,6},{-1,5},{-2,5},{-3,5}}});
        assertOrientations(new TBlock(), new int[][][]{
                {{-2,4},{-2,5},{-2,6},{-1,5}},
                {{-1,5},{-2,5},{-3,5},{-2,4}},
                {{-2,6},{-2,5},{-2,4},{-3,5}},
                {{-1,5},{-2,5},{-3,5},{-2,6}}});
        assertOrientations(new IBlock(), new int[][][]{
                {{-1,3},{-1,4},{-1,5},{-1,6}},
                {{-3,5},{-2,5},{-1,5},{0,5}}});
        assertOrientations(new SBlock(), new int[][][]{
                {{-1,4},{-1,5},{-2,5},{-2,6}},
                {{-3,5},{-2,5},{-2,6},{-1,6}}});
        assertOrientations(new ZBlock(), new int[][][]{
                {{-2,4},{-2,5},{-1,5},{-1,6}},
                {{-3,6},{-2,6},{-2,5},{-1,5}}});
        assertOrientations(new OBlock(), new int[][][]{
                {{-2,4},{-2,5},{-1,5},{-1,4}}});
    }

    private static void assertOrientations(Tetris piece, int[][][] states) {
        assertEquals(states.length, TetrominoType.from(piece).rotationStates());
        for (int cycle = 0; cycle < 2; cycle++) {
            for (int state = 0; state < states.length; state++) {
                assertState(piece, state, states[state]);
                assertSame(piece.getCells(), piece.rotateClockwise());
            }
            for (int step = 0; step < states.length; step++) {
                int state = (states.length - step) % states.length;
                assertState(piece, state, states[state]);
                assertSame(piece.getCells(), piece.rotateCounterClockwise());
            }
        }
        // Replanning uses translated live pieces, not only spawn coordinates.
        assertSame(piece.getCells(), piece.downMove());
        assertSame(piece.getCells(), piece.leftMove());
        assertSame(piece.getCells(), piece.rightMove());
        assertSame(piece.getCells(), piece.rightMove());
        for (int state = 0; state < states.length; state++) {
            int[][] translated = Arrays.stream(states[state])
                    .map(cell -> new int[]{cell[0] + 1, cell[1] + 1}).toArray(int[][]::new);
            assertState(piece, state, translated);
            piece.rotateClockwise();
        }
    }

    private static void assertState(Tetris piece, int state, int[][] expected) {
        assertEquals(state, piece.rotationState(), piece.getClass().getSimpleName());
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i][0], piece.getCells()[i].getRow(), piece.getClass() + " row " + i);
            assertEquals(expected[i][1], piece.getCells()[i].getCol(), piece.getClass() + " col " + i);
        }
    }

    @Test
    void placementChecksTopRowAndInclusiveLastVisibleBoundaries() {
        boolean[][] board = new boolean[3][3];
        assertTrue(BoardRules.canPlace(board, 3, 3, List.of(new BoardPosition(2, 2))));
        board[0][0] = true;
        assertFalse(BoardRules.canPlace(board, 3, 3, List.of(new BoardPosition(0, 0))));
        assertTrue(BoardRules.canPlace(board, 3, 3, List.of(new BoardPosition(-1, 0))));
        assertFalse(BoardRules.canPlace(board, 3, 3, List.of(new BoardPosition(-1, -1))));
        assertFalse(BoardRules.canPlace(board, 3, 3, List.of(new BoardPosition(-1, 3))));
    }

    @Test
    void fullRowsFiltersInvalidCandidatesAndChecksBothEdgeRows() {
        boolean[][] board = {{true,true}, {true,false}, {true,true}};
        assertEquals(List.of(2,0), BoardRules.fullRows(board, Arrays.asList(null,-1,3,2,1,0)));
        assertEquals(List.of(), BoardRules.fullRows(board, List.of(1)));
    }

    @Test
    void rowClearingHandlesConsecutiveSeparatedAllAndNoFullRows() {
        boolean[][] separated = {{true,false},{true,true},{false,true},{true,true}};
        assertEquals(2, BoardRules.clearFullRows(separated));
        assertArrayEquals(new boolean[][]{{false,false},{false,false},{true,false},{false,true}}, separated);
        boolean[][] consecutive = {{true,false},{false,true},{true,true},{true,true}};
        assertEquals(2, BoardRules.clearFullRows(consecutive));
        assertArrayEquals(new boolean[][]{{false,false},{false,false},{true,false},{false,true}}, consecutive);
        boolean[][] all = {{true,true},{true,true}};
        assertEquals(2, BoardRules.clearFullRows(all));
        assertArrayEquals(new boolean[][]{{false,false},{false,false}}, all);
        boolean[][] none = {{true,false},{false,true}};
        assertEquals(0, BoardRules.clearFullRows(none));
        assertArrayEquals(new boolean[][]{{true,false},{false,true}}, none);
    }

    @Test
    void malformedBoardDimensionsAreRejectedBeforePlacementOrClearing() {
        assertThrows(IllegalArgumentException.class,
                () -> BoardRules.canPlace(new boolean[0][1], 0, 1, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> BoardRules.canPlace(new boolean[1][0], 1, 0, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> BoardRules.canPlace(new boolean[1][1], 2, 1, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> BoardRules.canPlace(new boolean[][]{{false},{false,false}}, 2, 1, List.of()));
        for (boolean[][] malformed : List.of(new boolean[0][0], new boolean[1][0],
                new boolean[][]{{false},{false,false}})) {
            assertThrows(IllegalArgumentException.class, () -> BoardRules.clearFullRows(malformed));
            assertThrows(IllegalArgumentException.class, () -> BoardRules.fullRows(malformed, List.of()));
        }
    }

    @Test
    void cellEqualityRejectsNullOtherTypesAndDifferentRows() {
        Cell cell = new Cell(1, 2, null);
        assertEquals(cell, cell);
        assertNotEquals(cell, null);
        assertNotEquals(cell, new BoardPosition(1, 2));
        assertNotEquals(cell, new Cell(2, 2, null));
        assertEquals(cell, new Cell(1, 2, null));
        assertEquals(cell.hashCode(), new Cell(1, 2, null).hashCode());
    }

    @Test
    void immutablePositionsTranslateWithoutChangingOriginal() {
        BoardPosition position = new BoardPosition(-1, 2);
        assertEquals(new BoardPosition(0, 2), position.down());
        assertEquals(new BoardPosition(-1, -1), position.horizontal(-3));
        assertEquals(new BoardPosition(-1, 2), position);
    }
}
