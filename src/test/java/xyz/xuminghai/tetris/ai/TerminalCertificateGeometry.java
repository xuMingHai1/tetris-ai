/*
 * Copyright (C) 2024-2026 xuMingHai
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import xyz.xuminghai.tetris.core.BoardPosition;
import xyz.xuminghai.tetris.core.TetrisFactory;
import xyz.xuminghai.tetris.core.TetrominoType;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Test-only static covers, never a physics engine or a construction certificate.
 * Coordinates are local to the domain (HEART row zero maps to board row 12).
 * A consumer must independently check terminal Q, bag order and real production replay.
 */
final class TerminalCertificateGeometry {
    static final int NODE_LIMIT = 200_000;
    static final int DECOMPOSITION_LIMIT = 128;
    static final int TILE_LIMIT = 16;
    private static final Comparator<BoardPosition> CELL_ORDER = Comparator
            .comparingInt(BoardPosition::row).thenComparingInt(BoardPosition::col);
    private static final Comparator<Tile> TILE_ORDER = (left, right) -> {
        int type = left.type().compareTo(right.type());
        if (type != 0) return type;
        for (int i = 0; i < 4; i++) {
            int cell = CELL_ORDER.compare(left.cells().get(i), right.cells().get(i));
            if (cell != 0) return cell;
        }
        return 0;
    };

    private TerminalCertificateGeometry() { }

    /** Masks describe geometry only; allowed includes required and all optional cells. */
    record Domain(int rows, int cols, BigInteger required, BigInteger allowed) {
        Domain {
            Objects.requireNonNull(required, "required");
            Objects.requireNonNull(allowed, "allowed");
            if (rows < 1 || rows > 8 || cols < 1 || cols > 10
                    || required.signum() <= 0 || allowed.signum() < 0
                    || allowed.bitLength() > rows * cols || required.andNot(allowed).signum() != 0) {
                throw new IllegalArgumentException("invalid static domain");
            }
        }

        static Domain heart() {
            BigInteger required = BigInteger.ZERO;
            BigInteger allowed = BigInteger.ZERO;
            for (int row = 0; row < 8; row++) {
                for (int col = 0; col < 10; col++) {
                    var role = ShapeTarget.HEART.roleAtBoardCell(20, 10, row + 12, col);
                    int bit = row * 10 + col;
                    if (role == ShapeTarget.CellRole.REQUIRED) required = required.setBit(bit);
                    if (role != ShapeTarget.CellRole.FORBIDDEN) allowed = allowed.setBit(bit);
                }
            }
            return new Domain(8, 10, required, allowed);
        }
    }

    record Tile(TetrominoType type, List<BoardPosition> cells, BigInteger mask) {
        Tile {
            Objects.requireNonNull(type, "type");
            cells = List.copyOf(cells);
            Objects.requireNonNull(mask, "mask");
        }
    }

    enum Stop { EXHAUSTED, NODE_LIMIT, DECOMPOSITION_LIMIT, CONSUMER_STOP }
    record Result(int nodes, int decompositions, Stop stop) { }

    /** Four factory rotations are normalized and deduplicated before translation. */
    static List<Tile> tiles(Domain domain) {
        List<Tile> tiles = new ArrayList<>();
        for (var type : TetrominoType.values()) {
            var piece = TetrisFactory.create(type);
            var orientations = new HashSet<List<BoardPosition>>();
            for (int rotation = 0; rotation < 4; rotation++) {
                var cells = piece.getCells();
                int minRow = java.util.Arrays.stream(cells).mapToInt(c -> c.getRow()).min().orElseThrow();
                int minCol = java.util.Arrays.stream(cells).mapToInt(c -> c.getCol()).min().orElseThrow();
                var normalized = java.util.Arrays.stream(cells)
                        .map(c -> new BoardPosition(c.getRow() - minRow, c.getCol() - minCol))
                        .sorted(CELL_ORDER).toList();
                if (orientations.add(normalized)) {
                    int height = normalized.stream().mapToInt(BoardPosition::row).max().orElseThrow() + 1;
                    int width = normalized.stream().mapToInt(BoardPosition::col).max().orElseThrow() + 1;
                    for (int row = 0; row <= domain.rows() - height; row++) {
                        for (int col = 0; col <= domain.cols() - width; col++) {
                            List<BoardPosition> translated = new ArrayList<>();
                            BigInteger mask = BigInteger.ZERO;
                            for (var cell : normalized) {
                                var position = new BoardPosition(row + cell.row(), col + cell.col());
                                translated.add(position);
                                mask = mask.setBit(position.row() * domain.cols() + position.col());
                            }
                            if (mask.andNot(domain.allowed()).signum() == 0) {
                                tiles.add(new Tile(type, translated, mask));
                            }
                        }
                    }
                }
                piece.rotateClockwise();
            }
        }
        tiles.sort(TILE_ORDER);
        return List.copyOf(tiles);
    }

    /**
     * Streams static covers synchronously. True from the consumer stops globally; it does not
     * mean success. This method neither starts a JVM nor exposes a full-diagnostic CLI.
     * Fixture limits may only reduce the registered maxima.
     */
    static Result enumerate(Domain domain, int nodeLimit, int decompositionLimit, int tileLimit,
                            Predicate<List<Tile>> consumer) {
        if (nodeLimit < 1 || nodeLimit > NODE_LIMIT
                || decompositionLimit < 1 || decompositionLimit > DECOMPOSITION_LIMIT
                || tileLimit < 1 || tileLimit > TILE_LIMIT) {
            throw new IllegalArgumentException("limits must be within the registered maxima");
        }
        var search = new Search(domain, nodeLimit, decompositionLimit, tileLimit,
                Objects.requireNonNull(consumer, "consumer"));
        search.visit(BigInteger.ZERO, new ArrayList<>(), 0);
        return new Result(search.nodes, search.decompositions,
                search.stop == null ? Stop.EXHAUSTED : search.stop);
    }

    private static final class Search {
        private final Domain domain;
        private final int nodeLimit;
        private final int decompositionLimit;
        private final int tileLimit;
        private final Predicate<List<Tile>> consumer;
        private final List<Tile> tiles;
        private int nodes;
        private int decompositions;
        private Stop stop;

        private Search(Domain domain, int nodeLimit, int decompositionLimit, int tileLimit,
                       Predicate<List<Tile>> consumer) {
            this.domain = domain;
            this.nodeLimit = nodeLimit;
            this.decompositionLimit = decompositionLimit;
            this.tileLimit = tileLimit;
            this.consumer = consumer;
            this.tiles = tiles(domain);
        }

        private void visit(BigInteger occupied, List<Tile> selected, int optionalStart) {
            nodes++;
            var missing = domain.required().andNot(occupied);
            if (missing.signum() == 0) {
                decompositions++;
                boolean consumerStop = consumer.test(List.copyOf(selected));
                // A reached count cap wins over a simultaneous consumer stop. Exceptions propagate.
                if (decompositions == decompositionLimit) stop = Stop.DECOMPOSITION_LIMIT;
                else if (consumerStop) stop = Stop.CONSUMER_STOP;
            }
            if (stop == null && nodes == nodeLimit) stop = Stop.NODE_LIMIT;
            if (stop != null || selected.size() == tileLimit) return;
            int anchor = missing.getLowestSetBit();
            for (int i = missing.signum() == 0 ? optionalStart : 0; i < tiles.size(); i++) {
                var tile = tiles.get(i);
                if (anchor >= 0 && !tile.mask().testBit(anchor)) continue;
                if (anchor < 0 && tile.mask().and(domain.required()).signum() != 0) continue;
                if (tile.mask().and(occupied).signum() != 0) continue;
                var union = occupied.or(tile.mask());
                if (hasFullRow(union)) continue;
                selected.add(tile);
                visit(union, selected, anchor < 0 ? i + 1 : 0);
                selected.removeLast();
                if (stop != null) return;
            }
        }

        private boolean hasFullRow(BigInteger occupied) {
            var rowMask = BigInteger.ONE.shiftLeft(domain.cols()).subtract(BigInteger.ONE);
            for (int row = 0; row < domain.rows(); row++) {
                if (occupied.shiftRight(row * domain.cols()).and(rowMask).equals(rowMask)) return true;
            }
            return false;
        }
    }
}
