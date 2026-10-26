/*
 * Copyright (C) 2024-2026 xuMingHai
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import xyz.xuminghai.tetris.core.BoardPosition;
import xyz.xuminghai.tetris.core.TetrominoType;

import java.math.BigInteger;
import java.util.List;
import java.util.Objects;

/** Immutable replay claim. Board bits use absolute 20x10 row-major coordinates. */
record TerminalCertificateStep(TetrominoType type, List<BoardPosition> spawnCells,
                               int spawnRotation, List<BoardPosition> cells, AiPlan plan,
                               BigInteger postBoard, int clearedLines) {
    TerminalCertificateStep {
        Objects.requireNonNull(type, "type");
        spawnCells = List.copyOf(spawnCells);
        cells = List.copyOf(cells);
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(postBoard, "postBoard");
    }

    static BigInteger boardBits(boolean[][] board) {
        if (board.length != 20 || java.util.Arrays.stream(board).anyMatch(r -> r.length != 10)) {
            throw new IllegalArgumentException("certificate requires a 20x10 board");
        }
        var bits = BigInteger.ZERO;
        for (int row = 0; row < 20; row++) {
            for (int col = 0; col < 10; col++) {
                if (board[row][col]) bits = bits.setBit(row * 10 + col);
            }
        }
        return bits;
    }
}
