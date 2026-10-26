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
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Artificial small covers and fixed short action fixtures; never runs the registered HEART search. */
class TerminalCertificateOrderTest {
    private static BigInteger mask(int... bits) {
        var mask = BigInteger.ZERO;
        for (int bit : bits) mask = mask.setBit(bit);
        return mask;
    }

    private static TerminalCertificateGeometry.Domain fixture() {
        var cells = mask(0, 1, 8, 9, 11, 12, 13, 14);
        return new TerminalCertificateGeometry.Domain(2, 8, cells, cells);
    }

    private static List<TerminalCertificateGeometry.Tile> cover(TerminalCertificateGeometry.Domain domain) {
        return TerminalCertificateGeometry.tiles(domain);
    }

    @Test
    void sortsTilesAndReplaysAnArtificialCoverFromEmptyBoard() {
        var domain = fixture();
        var reversed = new ArrayList<>(cover(domain));
        Collections.reverse(reversed);
        var result = new TerminalCertificateOrder().search(domain, reversed);
        assertEquals(TerminalCertificateOrder.Stop.ORDER_FOUND, result.stop());
        assertEquals(2, result.enumerations());
        assertEquals(2, result.replays());
        var steps = result.order().orElseThrow();
        assertEquals(List.of(TetrominoType.I, TetrominoType.O), steps.stream().map(TerminalCertificateStep::type).toList());
        var checked = TerminalCertificateChecker.check(domain, steps);
        assertEquals(2, checked.replays());
        assertEquals(8, checked.finalBoard().bitCount());
        assertFalse(checked.terminal().accepted(), "small cover must not be called a HEART certificate");
        assertEquals(steps, new TerminalCertificateOrder().search(domain, cover(domain)).order().orElseThrow());
        assertThrows(UnsupportedOperationException.class, () -> steps.clear());
    }

    @Test
    void floatingGeometryHasNoOrderAndVerticalFactoryTileRequiresRotation() {
        var floating = new TerminalCertificateGeometry.Domain(3, 4, mask(0, 1, 4, 5), mask(0, 1, 4, 5));
        var miss = new TerminalCertificateOrder().search(floating, cover(floating));
        assertEquals(TerminalCertificateOrder.Stop.EXHAUSTED, miss.stop());
        assertTrue(miss.order().isEmpty());
        assertEquals(1, miss.enumerations());
        assertEquals(0, miss.replays());
        var vertical = new TerminalCertificateGeometry.Domain(4, 5, mask(0, 5, 10, 15), mask(0, 5, 10, 15));
        var rotated = new TerminalCertificateOrder().search(vertical, cover(vertical)).order().orElseThrow();
        assertTrue(rotated.getFirst().plan().actions().contains(AiAction.ROTATE_CLOCKWISE));
        assertEquals(1, TerminalCertificateChecker.check(vertical, rotated).replays());
    }

    @Test
    void capIsSharedAcrossCoversAndLastEnumerationCanOnlyFinishCurrentEdge() {
        var domain = fixture();
        var budget = new TerminalCertificateOrder(1);
        var first = budget.search(domain, cover(domain));
        assertEquals(TerminalCertificateOrder.Stop.ENUMERATION_LIMIT, first.stop());
        assertEquals(1, first.enumerations());
        assertEquals(1, first.replays());
        assertTrue(first.order().isEmpty());
        assertEquals(first, budget.search(domain, cover(domain)), "no fresh allowance per decomposition");
        var finalEdge = new TerminalCertificateOrder(2).search(domain, cover(domain));
        assertEquals(TerminalCertificateOrder.Stop.ENUMERATION_LIMIT, finalEdge.stop());
        assertEquals(2, TerminalCertificateChecker.check(domain, finalEdge.order().orElseThrow()).replays());
        assertThrows(IllegalArgumentException.class, () -> new TerminalCertificateOrder(2_001));
        assertThrows(IllegalArgumentException.class, () -> new TerminalCertificateOrder(0));
    }

    @Test
    void duplicateTypesCannotPassAnIncompleteBag() {
        var cells = mask(0, 1, 6, 7, 4, 5, 10, 11);
        var domain = new TerminalCertificateGeometry.Domain(2, 6, cells, cells);
        var result = new TerminalCertificateOrder().search(domain, cover(domain));
        assertTrue(result.order().isEmpty());
        assertEquals(TerminalCertificateOrder.Stop.EXHAUSTED, result.stop());
        assertEquals(2, result.enumerations());
        var repeated = List.of(step(new boolean[20][10], TetrominoType.O, new AiMove(0, -4)),
                step(boardAfter(new boolean[20][10], TetrominoType.O, new AiMove(0, -4)),
                        TetrominoType.O, new AiMove(0, 0)));
        assertRejects("7-bag duplicate", roomy(), repeated);
    }

    @Test
    void checkerResetsBagOnlyAfterSevenDistinctTypes() {
        var steps = new ArrayList<TerminalCertificateStep>();
        var board = new boolean[20][10];
        var types = new ArrayList<>(List.of(TetrominoType.values()));
        types.add(TetrominoType.I);
        for (var type : types) {
            var snapshot = BoardSimulator.snapshotForSpawnedPiece(board, type);
            var candidate = ActionPlanCandidates.ranked(snapshot).stream()
                    .filter(c -> c.placement().clearedLines() == 0
                            && BoardOccupancyMetrics.analyzeWithHoleCells(c.placement().resultingBoard())
                            .maxColumnHeight() <= 8).findFirst().orElseThrow();
            steps.add(claim(snapshot, candidate.plan()));
            board = candidate.placement().resultingBoard();
        }
        var domain = new TerminalCertificateGeometry.Domain(8, 10,
                TerminalCertificateStep.boardBits(board).shiftRight(120),
                BigInteger.ONE.shiftLeft(80).subtract(BigInteger.ONE));
        assertEquals(8, TerminalCertificateChecker.check(domain, steps).replays());
    }

    @Test
    void checkerRejectsWrongSpawnActionsCellsBoardAndTruncation() {
        var domain = fixture();
        var steps = new TerminalCertificateOrder().search(domain, cover(domain)).order().orElseThrow();
        var first = steps.getFirst();
        var wrongSpawn = new TerminalCertificateStep(first.type(), first.spawnCells().stream()
                .map(BoardPosition::down).toList(), first.spawnRotation(), first.cells(), first.plan(), first.postBoard(), 0);
        assertRejects("spawn-gravity boundary", domain, List.of(wrongSpawn, steps.getLast()));
        var wrongRotation = new TerminalCertificateStep(first.type(), first.spawnCells(), 1,
                first.cells(), first.plan(), first.postBoard(), 0);
        assertRejects("spawn-gravity boundary", domain, List.of(wrongRotation));
        assertRejects("mismatch", domain, List.of(new TerminalCertificateStep(first.type(), first.spawnCells(), 0,
                first.cells(), new AiPlan(List.of(AiAction.LEFT, AiAction.HARD_DROP)), first.postBoard(), 0)));
        assertRejects("mismatch", domain, List.of(new TerminalCertificateStep(first.type(), first.spawnCells(), 0,
                first.cells(), first.plan(), BigInteger.ZERO, 0)));
        var repeatedCells = Collections.nCopies(4, first.cells().getFirst());
        assertRejects("four distinct", domain, List.of(new TerminalCertificateStep(first.type(), first.spawnCells(), 0,
                repeatedCells, first.plan(), first.postBoard(), 0)));
        assertRejects("missing required", domain, steps.subList(0, 1));
        var illegal = new AiPlan(java.util.stream.Stream.concat(Collections.nCopies(20, AiAction.LEFT).stream(),
                java.util.stream.Stream.of(AiAction.HARD_DROP)).toList());
        assertThrows(IllegalStateException.class, () -> TerminalCertificateChecker.check(domain, List.of(
                new TerminalCertificateStep(first.type(), first.spawnCells(), 0,
                        first.cells(), illegal, first.postBoard(), 0))));
        assertThrows(IllegalArgumentException.class, () -> TerminalCertificateChecker.check(domain, List.of()));
    }

    @Test
    void checkerRejectsForbiddenOverlapAndClaimedOrActualRowClears() {
        var domain = fixture();
        var steps = new TerminalCertificateOrder().search(domain, cover(domain)).order().orElseThrow();
        var first = steps.getFirst();
        var restricted = new TerminalCertificateGeometry.Domain(2, 8, BigInteger.ONE,
                domain.allowed().clearBit(11));
        assertRejects("outside allowed", restricted, steps);
        var second = steps.getLast();
        var overlap = new TerminalCertificateStep(second.type(), second.spawnCells(), 0,
                first.cells(), second.plan(), second.postBoard(), 0);
        assertRejects("overlapping", domain, List.of(first, overlap));
        assertRejects("row clear", domain, List.of(new TerminalCertificateStep(first.type(), first.spawnCells(), 0,
                first.cells(), first.plan(), first.postBoard(), 1)));

        // Three fixed, distinct types complete the bottom row. No solver/HEART search is involved.
        var row = new boolean[20][10];
        var clearSteps = new ArrayList<TerminalCertificateStep>();
        for (var spec : List.of(new Object[]{TetrominoType.I, 0, -3},
                new Object[]{TetrominoType.J, 2, 0}, new Object[]{TetrominoType.L, 2, 3})) {
            var snapshot = BoardSimulator.snapshotForSpawnedPiece(row, (TetrominoType) spec[0]);
            var plan = AiPlan.fromPlacement(new AiMove((Integer) spec[1], (Integer) spec[2]));
            var placement = ActionPlanSimulator.requireTerminalPlacement(snapshot, plan);
            if (placement.clearedLines() == 0) {
                clearSteps.add(claim(snapshot, plan));
            } else {
                // Cells are a geometric claim; replay, rather than claimed clear count, must reject it.
                clearSteps.add(new TerminalCertificateStep(snapshot.currentType(), snapshot.currentCells(), 0,
                        List.of(new BoardPosition(19, 7), new BoardPosition(19, 8),
                                new BoardPosition(19, 9), new BoardPosition(18, 9)), plan,
                        TerminalCertificateStep.boardBits(placement.resultingBoard()), 0));
            }
            row = placement.resultingBoard();
        }
        assertRejects("row clear", roomy(), clearSteps);
    }

    @Test
    void malformedCoversCannotConsumeTheSearchBudget() {
        var domain = fixture();
        var tiles = cover(domain);
        var search = new TerminalCertificateOrder();
        assertThrows(IllegalArgumentException.class, () -> search.search(domain, List.of(tiles.getFirst())));
        assertThrows(IllegalArgumentException.class, () -> search.search(domain, List.of(tiles.getFirst(), tiles.getFirst())));
        var forged = new TerminalCertificateGeometry.Tile(tiles.getFirst().type(), tiles.getFirst().cells(), BigInteger.ONE);
        assertThrows(IllegalArgumentException.class, () -> search.search(domain, List.of(forged)));
        assertEquals(2, search.search(domain, tiles).enumerations());
    }

    private static TerminalCertificateGeometry.Domain roomy() {
        return new TerminalCertificateGeometry.Domain(8, 10, BigInteger.ONE,
                BigInteger.ONE.shiftLeft(80).subtract(BigInteger.ONE));
    }

    private static TerminalCertificateStep step(boolean[][] board, TetrominoType type, AiMove move) {
        return claim(BoardSimulator.snapshotForSpawnedPiece(board, type), AiPlan.fromPlacement(move));
    }

    private static boolean[][] boardAfter(boolean[][] board, TetrominoType type, AiMove move) {
        return ActionPlanSimulator.requireTerminalPlacement(BoardSimulator.snapshotForSpawnedPiece(board, type),
                AiPlan.fromPlacement(move)).resultingBoard();
    }

    private static TerminalCertificateStep claim(GameSnapshot snapshot, AiPlan plan) {
        var placement = ActionPlanSimulator.requireTerminalPlacement(snapshot, plan);
        assertEquals(0, placement.clearedLines());
        var after = placement.resultingBoard();
        var before = snapshot.occupied();
        var cells = new ArrayList<BoardPosition>();
        for (int row = 0; row < 20; row++) {
            for (int col = 0; col < 10; col++) {
                if (after[row][col] && !before[row][col]) cells.add(new BoardPosition(row, col));
            }
        }
        return new TerminalCertificateStep(snapshot.currentType(), snapshot.currentCells(), snapshot.currentRotation(),
                cells, plan, TerminalCertificateStep.boardBits(after), 0);
    }

    private static void assertRejects(String reason, TerminalCertificateGeometry.Domain domain,
                                      List<TerminalCertificateStep> steps) {
        var error = assertThrows(IllegalArgumentException.class, () -> TerminalCertificateChecker.check(domain, steps));
        assertTrue(error.getMessage().contains(reason), error.getMessage());
    }
}
