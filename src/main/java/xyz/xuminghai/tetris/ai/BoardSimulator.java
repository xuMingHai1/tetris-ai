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
import xyz.xuminghai.tetris.core.TetrominoType;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class BoardSimulator {

    private BoardSimulator() {
    }

    /**
     * Enumerates every move reachable through the live rotate-then-horizontal-move control path.
     *
     * <p>Candidate order is deterministic: rotation count first, then horizontal shift from left to
     * right. Invalid paths are omitted instead of being represented by sentinel scores.</p>
     */
    public static List<PlacementCandidate> candidates(GameSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");

        List<PlacementCandidate> candidates = new ArrayList<>();
        for (int rotations = 0; rotations < snapshot.currentType().rotationStates(); rotations++) {
            for (int shift = -snapshot.cols(); shift <= snapshot.cols(); shift++) {
                PlacementCandidate candidate = simulate(snapshot, rotations, shift);
                if (candidate != null) {
                    candidates.add(candidate);
                }
            }
        }
        return List.copyOf(candidates);
    }

    /**
     * Enumerates legal placements for a known preview piece at the same spawn boundary used by
     * {@code GameWorld}: the piece is created at its normal factory coordinates and receives the
     * first automatic gravity move before candidate generation.
     *
     * <p>This keeps look-ahead inside the deterministic rules engine instead of teaching a strategy
     * how tetromino spawning, rotation or collision works.</p>
     */
    static List<PlacementCandidate> candidatesForSpawnedPiece(
            boolean[][] occupied,
            TetrominoType type) {
        Objects.requireNonNull(occupied, "occupied");
        Objects.requireNonNull(type, "type");
        if (occupied.length == 0 || occupied[0].length == 0) {
            throw new IllegalArgumentException("occupied board dimensions must be positive");
        }

        int rows = occupied.length;
        int cols = occupied[0].length;
        Tetris tetris = TetrisFactory.create(type);
        tetris.downMove();
        GameSnapshot snapshot = new GameSnapshot(
                rows,
                cols,
                occupied,
                type,
                positions(tetris));
        return candidates(snapshot);
    }

    private static PlacementCandidate simulate(GameSnapshot snapshot, int rotations, int horizontalShift) {
        boolean[][] occupied = snapshot.occupied();
        List<BoardPosition> cells = rotatedCells(snapshot, occupied, rotations);
        if (cells == null) {
            return null;
        }

        int direction = Integer.signum(horizontalShift);
        for (int step = 0; step < Math.abs(horizontalShift); step++) {
            cells = horizontal(cells, direction);
            if (!BoardRules.canPlace(occupied, snapshot.rows(), snapshot.cols(), cells)) {
                return null;
            }
        }

        List<BoardPosition> next = down(cells);
        while (BoardRules.canPlace(occupied, snapshot.rows(), snapshot.cols(), next)) {
            cells = next;
            next = down(cells);
        }

        if (cells.stream().anyMatch(cell -> cell.row() < 0)) {
            return null;
        }

        for (BoardPosition cell : cells) {
            occupied[cell.row()][cell.col()] = true;
        }

        int clearedLines = BoardRules.clearFullRows(occupied);
        return describe(new AiMove(rotations, horizontalShift), occupied, clearedLines);
    }

    static PlacementCandidate describeForPlan(boolean[][] board, int clearedLines) {
        return describe(AiMove.NONE, board, clearedLines);
    }

    private static PlacementCandidate describe(AiMove move, boolean[][] board, int clearedLines) {
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

        return new PlacementCandidate(move, board, clearedLines, aggregateHeight, holes, bumpiness);
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
}
