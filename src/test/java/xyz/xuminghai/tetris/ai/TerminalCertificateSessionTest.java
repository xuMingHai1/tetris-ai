/*
 * Copyright (C) 2024-2026 xuMingHai
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import org.junit.jupiter.api.Test;
import xyz.xuminghai.tetris.core.TetrominoType;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Artificial domains only: never starts the registered HEART diagnostic. */
class TerminalCertificateSessionTest {
    private static BigInteger mask(int... cells) {
        var bits = BigInteger.ZERO;
        for (int cell : cells) bits = bits.setBit(cell);
        return bits;
    }

    private static TerminalCertificateGeometry.Domain fixture(boolean optional) {
        var square = mask(0, 1, 8, 9);
        var line = mask(11, 12, 13, 14);
        return new TerminalCertificateGeometry.Domain(2, 8,
                optional ? square : square.or(line), square.or(line));
    }

    @Test
    void checksCompletedOrderBeforeSimultaneousCountStopsAndNeverPromotesArtificialQ() {
        var attempts = new ArrayList<TerminalCertificateSession.Attempt>();
        var result = TerminalCertificateSession.search(fixture(false),
                new TerminalCertificateSession.Limits(3, 1, 2, 2), attempts::add);
        assertEquals(TerminalCertificateSession.Status.BOUNDED_MISS, result.status());
        assertEquals(TerminalCertificateGeometry.Stop.DECOMPOSITION_LIMIT, result.geometry().stop());
        assertEquals(3, result.geometry().nodes());
        assertEquals(1, result.geometry().decompositions());
        assertTrue(result.enumerationLimitReached());
        assertEquals(2, result.enumerations());
        assertEquals(2, result.solverReplays());
        assertEquals(2, result.checkerReplays());
        assertTrue(result.certificate().isEmpty());
        assertEquals(1, attempts.size());
        assertFalse(attempts.getFirst().checked().orElseThrow().terminal().accepted());
        assertEquals(2, attempts.getFirst().order().facts().size());
    }

    @Test
    void optionalCoversShareOneBudgetAndCannotBorrowEnumerationsToRecordFacts() {
        var attempts = new ArrayList<TerminalCertificateSession.Attempt>();
        var result = TerminalCertificateSession.search(fixture(true),
                new TerminalCertificateSession.Limits(20, 10, 2, 2), attempts::add);
        assertEquals(2, attempts.size());
        assertEquals(1, attempts.getFirst().order().enumerations());
        assertTrue(attempts.getFirst().checked().isPresent());
        assertEquals(2, attempts.getLast().order().enumerations());
        assertTrue(attempts.getLast().order().order().isEmpty());
        assertTrue(attempts.getLast().order().facts().isEmpty());
        assertEquals(2, result.enumerations());
        assertEquals(1, result.checkerReplays());
        assertEquals(TerminalCertificateGeometry.Stop.CONSUMER_STOP, result.geometry().stop());
        assertEquals(TerminalCertificateSession.Status.BOUNDED_MISS, result.status());
    }

    @Test
    void factsDescribeActualRankGuardBagAndRoleHolesWithoutChangingSelection() {
        var domain = fixture(false);
        var searched = new TerminalCertificateOrder(2).search(domain, TerminalCertificateGeometry.tiles(domain));
        var steps = searched.order().orElseThrow();
        var board = new boolean[20][10];
        int bag = 0;
        for (int i = 0; i < steps.size(); i++) {
            var step = steps.get(i);
            var fact = searched.facts().get(i);
            var snapshot = BoardSimulator.snapshotForSpawnedPiece(board, step.type());
            var ranked = ActionPlanCandidates.ranked(snapshot);
            var selected = ranked.get(fact.survivalRank() - 1);
            assertEquals(step.plan(), selected.plan());
            assertEquals(i, fact.index());
            assertEquals(bag, fact.bagBefore());
            bag |= 1 << step.type().ordinal();
            assertEquals(bag, fact.bagAfter());
            var baseline = ranked.getFirst().placement();
            assertEquals(GoalWindowCandidate.guard(fact.survivalRank(), baseline, selected.placement(),
                    ObjectiveRiskController.adaptive().decide(baseline)), fact.originalGuard());
            board = ActionPlanSimulator.requireTerminalPlacement(snapshot, step.plan()).resultingBoard();
            assertEquals(GoalWindowCandidate.terminal(board), fact.resultingFacts());
        }
        assertEquals(List.of(TetrominoType.I, TetrominoType.O), steps.stream()
                .map(TerminalCertificateStep::type).toList());
        assertThrows(UnsupportedOperationException.class, () -> searched.facts().clear());
        var danger = new GoalWindowCandidate.Guard(false, true, false, 1, 1, 1, "DANGER", "STRICT");
        assertTrue(new TerminalCertificateOrder.StepFacts(0, 0, 1, 1, danger,
                searched.facts().getFirst().resultingFacts()).ordinaryAllowed());
        assertFalse(new TerminalCertificateOrder.StepFacts(0, 0, 1, 2, danger,
                searched.facts().getFirst().resultingFacts()).ordinaryAllowed());
    }

    @Test
    void nodeStopAndUnreachableGeometryRemainMissesWithExactWorkCounts() {
        var rootOnly = TerminalCertificateSession.search(fixture(false),
                new TerminalCertificateSession.Limits(1, 10, 20, 2), ignored -> fail("no cover"));
        assertEquals(TerminalCertificateGeometry.Stop.NODE_LIMIT, rootOnly.geometry().stop());
        assertEquals(0, rootOnly.enumerations());
        assertEquals(0, rootOnly.checkerReplays());
        var floatingBits = mask(0, 1, 4, 5);
        var floating = new TerminalCertificateGeometry.Domain(3, 4, floatingBits, floatingBits);
        var miss = TerminalCertificateSession.search(floating,
                new TerminalCertificateSession.Limits(20, 10, 20, 2), attempt -> {
                    assertTrue(attempt.order().order().isEmpty());
                    assertTrue(attempt.checked().isEmpty());
                });
        assertEquals(TerminalCertificateSession.Status.BOUNDED_MISS, miss.status());
        assertEquals(TerminalCertificateGeometry.Stop.EXHAUSTED, miss.geometry().stop());
        assertEquals(1, miss.enumerations());
        assertEquals(0, miss.solverReplays());
    }

    @Test
    void failedObservationPropagatesAndCannotBecomeMissOrContinueToAnotherCover() {
        var failure = new IllegalStateException("raw output failure");
        var observed = new ArrayList<TerminalCertificateSession.Attempt>();
        assertSame(failure, assertThrows(IllegalStateException.class, () ->
                TerminalCertificateSession.search(fixture(true),
                        new TerminalCertificateSession.Limits(20, 10, 20, 2), attempt -> {
                            observed.add(attempt);
                            throw failure;
                        })));
        assertEquals(1, observed.size());
        assertTrue(observed.getFirst().checked().isPresent());
        assertThrows(IllegalArgumentException.class, () ->
                new TerminalCertificateSession.Limits(200_001, 128, 2_000, 16));
        assertThrows(IllegalArgumentException.class, () ->
                new TerminalCertificateSession.Limits(200_000, 129, 2_000, 16));
        assertThrows(IllegalArgumentException.class, () ->
                new TerminalCertificateSession.Limits(200_000, 128, 2_001, 16));
        assertThrows(IllegalArgumentException.class, () ->
                new TerminalCertificateSession.Limits(200_000, 128, 2_000, 17));
    }
}
