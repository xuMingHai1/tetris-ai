/*
 * Copyright (C) 2024-2026 xuMingHai
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import xyz.xuminghai.tetris.core.BoardPosition;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Test-only ordering of one static cover through production reachability and replay.
 * An order is not diagnostic success: an independent checker, terminal Q and provenance remain required.
 * The same instance owns the enumeration budget across all synchronously streamed covers.
 */
final class TerminalCertificateOrder {
    static final int ENUMERATION_LIMIT = 2_000;
    private static final Comparator<BoardPosition> CELL_ORDER = Comparator
            .comparingInt(BoardPosition::row).thenComparingInt(BoardPosition::col);
    private static final Comparator<TerminalCertificateGeometry.Tile> TILE_ORDER = (a, b) -> {
        int type = a.type().compareTo(b.type());
        if (type != 0) return type;
        for (int i = 0; i < 4; i++) {
            int cell = CELL_ORDER.compare(a.cells().get(i), b.cells().get(i));
            if (cell != 0) return cell;
        }
        return 0;
    };

    enum Stop { ORDER_FOUND, EXHAUSTED, ENUMERATION_LIMIT }
    record Result(Stop stop, Optional<List<TerminalCertificateStep>> order,
                  int enumerations, int replays, List<StepFacts> facts) {
        Result {
            order = order.map(List::copyOf);
            facts = List.copyOf(facts);
        }
    }

    /** Descriptive facts from the already counted enumeration, never a search admission rule. */
    record StepFacts(int index, int bagBefore, int bagAfter, int survivalRank,
                     GoalWindowCandidate.Guard originalGuard, GoalWindowCandidate.Terminal resultingFacts) {
        // Production always permits its survival top choice, even in DANGER.
        boolean ordinaryAllowed() { return survivalRank == 1 || originalGuard.ordinaryAllowed(); }
    }

    private final int limit;
    private int enumerations;
    private int replays;
    private List<StepFacts> foundFacts = List.of();

    TerminalCertificateOrder() { this(ENUMERATION_LIMIT); }

    /** Fixtures may reduce, never increase, the registered cap. */
    TerminalCertificateOrder(int limit) {
        if (limit < 1 || limit > ENUMERATION_LIMIT) throw new IllegalArgumentException("enumeration limit");
        this.limit = limit;
    }

    Result search(TerminalCertificateGeometry.Domain domain,
                  List<TerminalCertificateGeometry.Tile> cover) {
        var tiles = validateCover(domain, cover);
        foundFacts = List.of();
        var order = visit(domain, tiles, new boolean[tiles.size()], new boolean[20][10],
                0, new ArrayList<>(), new ArrayList<>());
        var stop = enumerations == limit ? Stop.ENUMERATION_LIMIT
                : order != null ? Stop.ORDER_FOUND : Stop.EXHAUSTED;
        return new Result(stop, Optional.ofNullable(order), enumerations, replays, foundFacts);
    }

    private List<TerminalCertificateStep> visit(TerminalCertificateGeometry.Domain domain,
            List<TerminalCertificateGeometry.Tile> tiles, boolean[] used, boolean[][] board,
            int bagMask, List<TerminalCertificateStep> path, List<StepFacts> facts) {
        if (path.size() == tiles.size()) {
            foundFacts = List.copyOf(facts);
            return List.copyOf(path);
        }
        if (enumerations == limit) return null;
        for (int i = 0; i < tiles.size(); i++) {
            var tile = tiles.get(i);
            int typeBit = 1 << tile.type().ordinal();
            if (used[i] || (bagMask & typeBit) != 0) continue;
            var snapshot = BoardSimulator.snapshotForSpawnedPiece(board, tile.type());
            enumerations++;
            var candidates = ActionPlanCandidates.ranked(snapshot);
            var expected = snapshot.occupied();
            var absoluteCells = tile.cells().stream()
                    .map(c -> new BoardPosition(c.row() + 20 - domain.rows(), c.col())).toList();
            for (var cell : absoluteCells) expected[cell.row()][cell.col()] = true;
            var expectedBits = TerminalCertificateStep.boardBits(expected);
            for (int rank = 0; rank < candidates.size(); rank++) {
                var candidate = candidates.get(rank);
                if (candidate.placement().clearedLines() != 0
                        || !TerminalCertificateStep.boardBits(candidate.placement().resultingBoard())
                        .equals(expectedBits)) continue;
                // Only the first ranked path to this exact union matters: subsequent state is identical.
                replays++;
                var replay = ActionPlanSimulator.requireTerminalPlacement(snapshot, candidate.plan());
                if (replay.clearedLines() != 0
                        || !TerminalCertificateStep.boardBits(replay.resultingBoard()).equals(expectedBits)) {
                    throw new IllegalStateException("enumeration/replay mismatch");
                }
                path.add(new TerminalCertificateStep(tile.type(), snapshot.currentCells(),
                        snapshot.currentRotation(), absoluteCells, candidate.plan(), expectedBits, 0));
                used[i] = true;
                int nextBag = bagMask | typeBit;
                if (nextBag == 127) nextBag = 0;
                var baseline = candidates.getFirst().placement();
                facts.add(new StepFacts(path.size() - 1, bagMask, nextBag, rank + 1,
                        GoalWindowCandidate.guard(rank + 1, baseline, candidate.placement(),
                                ObjectiveRiskController.adaptive().decide(baseline)),
                        GoalWindowCandidate.terminal(replay.resultingBoard())));
                // The last permitted enumeration may finish a cover, but never opens another edge.
                var found = visit(domain, tiles, used, replay.resultingBoard(), nextBag, path, facts);
                used[i] = false;
                path.removeLast();
                facts.removeLast();
                if (found != null) return found;
                break;
            }
            if (enumerations == limit) return null;
        }
        return null;
    }

    private static List<TerminalCertificateGeometry.Tile> validateCover(
            TerminalCertificateGeometry.Domain domain, List<TerminalCertificateGeometry.Tile> cover) {
        Objects.requireNonNull(domain, "domain");
        if (cover.isEmpty() || cover.size() > TerminalCertificateGeometry.TILE_LIMIT) {
            throw new IllegalArgumentException("cover size");
        }
        var catalog = new HashSet<>(TerminalCertificateGeometry.tiles(domain));
        var occupied = BigInteger.ZERO;
        for (var tile : cover) {
            if (!catalog.contains(tile) || occupied.and(tile.mask()).signum() != 0) {
                throw new IllegalArgumentException("invalid or overlapping cover tile");
            }
            occupied = occupied.or(tile.mask());
        }
        if (domain.required().andNot(occupied).signum() != 0) {
            throw new IllegalArgumentException("cover misses required cells");
        }
        var rowMask = BigInteger.ONE.shiftLeft(domain.cols()).subtract(BigInteger.ONE);
        for (int row = 0; row < domain.rows(); row++) {
            if (occupied.shiftRight(row * domain.cols()).and(rowMask).equals(rowMask)) {
                throw new IllegalArgumentException("cover has a full domain row");
            }
        }
        return cover.stream().sorted(TILE_ORDER).toList();
    }
}
