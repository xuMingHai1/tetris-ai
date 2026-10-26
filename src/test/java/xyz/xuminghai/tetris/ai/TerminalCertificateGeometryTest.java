/*
 * Copyright (C) 2024-2026 xuMingHai
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import org.junit.jupiter.api.Test;
import xyz.xuminghai.tetris.core.BoardPosition;
import xyz.xuminghai.tetris.core.TetrominoType;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Tiny artificial static domains only: no registered HEART search, game or population. */
class TerminalCertificateGeometryTest {
    private static final BigInteger LEFT_O = mask(0, 1, 5, 6);
    private static final BigInteger RIGHT_O = mask(3, 4, 8, 9);

    private static BigInteger mask(int... bits) {
        var result = BigInteger.ZERO;
        for (int bit : bits) result = result.setBit(bit);
        return result;
    }

    private static TerminalCertificateGeometry.Domain fixture() {
        return new TerminalCertificateGeometry.Domain(2, 5, LEFT_O, LEFT_O.or(RIGHT_O));
    }

    @Test
    void heartDomainReadsProductionRolesWithoutStartingSearch() {
        var domain = TerminalCertificateGeometry.Domain.heart();
        assertEquals(32, domain.required().bitCount());
        assertEquals(64, domain.allowed().bitCount());
        for (int row = 0; row < 8; row++) {
            for (int col = 0; col < 10; col++) {
                var role = ShapeTarget.HEART.roleAtBoardCell(20, 10, row + 12, col);
                assertEquals(role == ShapeTarget.CellRole.REQUIRED,
                        domain.required().testBit(row * 10 + col));
                assertEquals(role != ShapeTarget.CellRole.FORBIDDEN,
                        domain.allowed().testBit(row * 10 + col));
            }
        }
    }

    @Test
    void factoryCatalogDeduplicatesRotationsAndRespectsForbiddenCells() {
        var tiles = TerminalCertificateGeometry.tiles(fixture());
        assertEquals(2, tiles.size());
        assertEquals(List.of(LEFT_O, RIGHT_O), tiles.stream().map(t -> t.mask()).toList());
        assertTrue(tiles.stream().allMatch(t -> t.type() == TetrominoType.O));
        assertEquals(List.of(new BoardPosition(0, 0), new BoardPosition(0, 1),
                new BoardPosition(1, 0), new BoardPosition(1, 1)), tiles.getFirst().cells());
        // A roomy catalog checks every production type/orientation, without enumerating covers.
        var roomy = new TerminalCertificateGeometry.Domain(8, 10, BigInteger.ONE,
                BigInteger.ONE.shiftLeft(80).subtract(BigInteger.ONE));
        var orientations = new HashSet<String>();
        for (var tile : TerminalCertificateGeometry.tiles(roomy)) {
            assertEquals(4, tile.mask().bitCount());
            assertEquals(4, new HashSet<>(tile.cells()).size());
            int minRow = tile.cells().stream().mapToInt(BoardPosition::row).min().orElseThrow();
            int minCol = tile.cells().stream().mapToInt(BoardPosition::col).min().orElseThrow();
            orientations.add(tile.type() + ":" + tile.cells().stream()
                    .map(c -> new BoardPosition(c.row() - minRow, c.col() - minCol)).toList());
        }
        assertEquals(19, orientations.size());
    }

    @Test
    void optionalOnlyTileIsVisitedAfterBareCoverExactlyOnce() {
        List<List<TerminalCertificateGeometry.Tile>> covers = new ArrayList<>();
        var result = TerminalCertificateGeometry.enumerate(fixture(), 100, 10, 16, cover -> {
            covers.add(cover);
            return false;
        });
        assertEquals(new TerminalCertificateGeometry.Result(3, 2,
                TerminalCertificateGeometry.Stop.EXHAUSTED), result);
        assertEquals(List.of(1, 2), covers.stream().map(List::size).toList());
        assertEquals(List.of(LEFT_O, RIGHT_O), covers.getLast().stream().map(t -> t.mask()).toList());
        assertThrows(UnsupportedOperationException.class, () -> covers.getLast().clear());
        assertThrows(UnsupportedOperationException.class, () -> covers.getFirst().getFirst().cells().clear());
        var repeated = new ArrayList<List<TerminalCertificateGeometry.Tile>>();
        TerminalCertificateGeometry.enumerate(fixture(), 100, 10, 16, c -> {
            repeated.add(c);
            return false;
        });
        assertEquals(covers, repeated);
    }

    @Test
    void nodeAndDecompositionCapsStopAllBranchesAtExactCounts() {
        assertEquals(new TerminalCertificateGeometry.Result(1, 0,
                        TerminalCertificateGeometry.Stop.NODE_LIMIT),
                TerminalCertificateGeometry.enumerate(fixture(), 1, 10, 16, c -> fail("not reached")));
        assertEquals(new TerminalCertificateGeometry.Result(2, 1,
                        TerminalCertificateGeometry.Stop.NODE_LIMIT),
                TerminalCertificateGeometry.enumerate(fixture(), 2, 10, 16, c -> false));
        assertEquals(new TerminalCertificateGeometry.Result(2, 1,
                        TerminalCertificateGeometry.Stop.DECOMPOSITION_LIMIT),
                TerminalCertificateGeometry.enumerate(fixture(), 2, 1, 16, c -> true));
        assertEquals(new TerminalCertificateGeometry.Result(2, 1,
                        TerminalCertificateGeometry.Stop.CONSUMER_STOP),
                TerminalCertificateGeometry.enumerate(fixture(), 100, 10, 16, c -> true));
        assertEquals(new TerminalCertificateGeometry.Result(2, 1,
                        TerminalCertificateGeometry.Stop.EXHAUSTED),
                TerminalCertificateGeometry.enumerate(fixture(), 100, 10, 1, c -> false));
    }

    @Test
    void fullRowsAndMissingRequiredNeverBecomeCovers() {
        var full = new TerminalCertificateGeometry.Domain(2, 4, mask(0, 1, 2, 3, 4, 5, 6, 7),
                mask(0, 1, 2, 3, 4, 5, 6, 7));
        var result = TerminalCertificateGeometry.enumerate(full, 100, 10, 16,
                c -> fail("full row is outside the no-clear family"));
        assertEquals(0, result.decompositions());
        assertEquals(TerminalCertificateGeometry.Stop.EXHAUSTED, result.stop());
        var impossible = new TerminalCertificateGeometry.Domain(1, 5, mask(0), mask(0));
        assertEquals(0, TerminalCertificateGeometry.enumerate(impossible, 100, 10, 16,
                c -> fail("a single allowed cell cannot hold a tile")).decompositions());
    }

    @Test
    void callbackErrorsPropagateAndLimitsCannotBeRaised() {
        var error = new IllegalStateException("checker failed");
        assertSame(error, assertThrows(IllegalStateException.class,
                () -> TerminalCertificateGeometry.enumerate(fixture(), 100, 10, 16, c -> { throw error; })));
        assertThrows(IllegalArgumentException.class,
                () -> TerminalCertificateGeometry.enumerate(fixture(), 200_001, 10, 16, c -> false));
        assertThrows(IllegalArgumentException.class,
                () -> TerminalCertificateGeometry.enumerate(fixture(), 100, 129, 16, c -> false));
        assertThrows(IllegalArgumentException.class,
                () -> TerminalCertificateGeometry.enumerate(fixture(), 100, 10, 17, c -> false));
        assertThrows(IllegalArgumentException.class,
                () -> new TerminalCertificateGeometry.Domain(2, 5, LEFT_O, RIGHT_O));
    }
}
