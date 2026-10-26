/*
 * Copyright (C) 2024-2026 xuMingHai
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import xyz.xuminghai.tetris.core.TetrominoType;

import java.math.BigInteger;
import java.util.HashSet;
import java.util.List;

/**
 * Independent empty-board replay of claims, with no solver status, cached board or enumerator input.
 * A replayed artificial-domain cover may still fail HEART Q; callers must require terminal.accepted()
 * in addition to provenance before reporting a real certificate.
 */
final class TerminalCertificateChecker {
    private TerminalCertificateChecker() { }

    record Checked(int replays, BigInteger finalBoard, GoalWindowCandidate.Terminal terminal) { }

    static Checked check(TerminalCertificateGeometry.Domain domain, List<TerminalCertificateStep> steps) {
        steps = List.copyOf(steps);
        if (steps.isEmpty() || steps.size() > 16) throw new IllegalArgumentException("step count");
        var board = new boolean[20][10];
        var bag = new HashSet<TetrominoType>();
        var covered = BigInteger.ZERO;
        for (var step : steps) {
            if (!bag.add(step.type())) throw new IllegalArgumentException("7-bag duplicate");
            if (bag.size() == TetrominoType.values().length) bag.clear();
            var snapshot = BoardSimulator.snapshotForSpawnedPiece(board, step.type());
            if (step.spawnRotation() != snapshot.currentRotation()
                    || !step.spawnCells().equals(snapshot.currentCells())) {
                throw new IllegalArgumentException("spawn-gravity boundary mismatch");
            }
            if (step.cells().size() != 4 || new HashSet<>(step.cells()).size() != 4) {
                throw new IllegalArgumentException("four distinct cells required");
            }
            var expected = snapshot.occupied();
            for (var cell : step.cells()) {
                int localRow = cell.row() - (20 - domain.rows());
                if (localRow < 0 || localRow >= domain.rows() || cell.col() < 0
                        || cell.col() >= domain.cols()
                        || !domain.allowed().testBit(localRow * domain.cols() + cell.col())) {
                    throw new IllegalArgumentException("cell outside allowed domain");
                }
                if (expected[cell.row()][cell.col()]) throw new IllegalArgumentException("overlapping cells");
                expected[cell.row()][cell.col()] = true;
                covered = covered.setBit(localRow * domain.cols() + cell.col());
            }
            var actual = ActionPlanSimulator.requireTerminalPlacement(snapshot, step.plan());
            if (step.clearedLines() != 0 || actual.clearedLines() != 0) {
                throw new IllegalArgumentException("row clear outside certificate family");
            }
            var actualBits = TerminalCertificateStep.boardBits(actual.resultingBoard());
            if (!actualBits.equals(TerminalCertificateStep.boardBits(expected))
                    || !actualBits.equals(step.postBoard())) {
                throw new IllegalArgumentException("action/cells/post-board mismatch");
            }
            board = actual.resultingBoard();
        }
        if (domain.required().andNot(covered).signum() != 0) {
            throw new IllegalArgumentException("missing required cells");
        }
        return new Checked(steps.size(), TerminalCertificateStep.boardBits(board),
                GoalWindowCandidate.terminal(board));
    }
}
