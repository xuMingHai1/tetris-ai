/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.game;

import javafx.scene.paint.Color;
import org.junit.jupiter.api.Test;
import xyz.xuminghai.tetris.ai.GameSnapshot;
import xyz.xuminghai.tetris.core.BoardPosition;
import xyz.xuminghai.tetris.core.Cell;
import xyz.xuminghai.tetris.core.TetrominoType;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameWorldAiSnapshotTest {

    @Test
    void acceptsOnlyTheExactPieceCoordinatesUsedByTheAiSnapshot() {
        GameSnapshot snapshot = new GameSnapshot(
                20,
                10,
                new boolean[20][10],
                TetrominoType.T,
                List.of(
                        new BoardPosition(0, 4),
                        new BoardPosition(1, 3),
                        new BoardPosition(1, 4),
                        new BoardPosition(1, 5)));

        Cell[] unchanged = {
                cell(0, 4),
                cell(1, 3),
                cell(1, 4),
                cell(1, 5)
        };
        Cell[] movedByGravity = {
                cell(1, 4),
                cell(2, 3),
                cell(2, 4),
                cell(2, 5)
        };
        Cell[] movedByPlayer = {
                cell(0, 5),
                cell(1, 4),
                cell(1, 5),
                cell(1, 6)
        };

        assertTrue(GameWorld.matchesSnapshotCells(snapshot, unchanged));
        assertFalse(GameWorld.matchesSnapshotCells(snapshot, movedByGravity));
        assertFalse(GameWorld.matchesSnapshotCells(snapshot, movedByPlayer));
    }

    private static Cell cell(int row, int col) {
        return new Cell(row, col, Color.RED);
    }
}
