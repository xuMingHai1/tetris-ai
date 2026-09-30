/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.game;

import org.junit.jupiter.api.Test;
import xyz.xuminghai.tetris.core.BoardPosition;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GameWorldGhostTest {

    @Test
    void projectsToTheLastLegalRowAboveSettledCells() {
        boolean[][] occupied = new boolean[6][4];
        occupied[5][1] = true;
        List<BoardPosition> piece = List.of(new BoardPosition(-1, 1),
                new BoardPosition(0, 1));

        assertEquals(List.of(new BoardPosition(3, 1), new BoardPosition(4, 1)),
                GameWorld.landingGhost(occupied, 6, 4, piece));
    }

    @Test
    void hidesTheOutlineWhenThePieceCannotMoveDown() {
        boolean[][] occupied = new boolean[6][4];
        occupied[2][1] = true;

        assertEquals(List.of(), GameWorld.landingGhost(occupied, 6, 4,
                List.of(new BoardPosition(0, 1), new BoardPosition(1, 1))));
    }
}
