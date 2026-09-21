/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import xyz.xuminghai.tetris.core.TetrominoType;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Deterministic one-piece outlook for one current placement candidate.
 *
 * <p>The outlook reuses {@link BoardSimulator} for the preview piece and the existing local
 * heuristic to identify its best response. It contains facts only; it does not select the current
 * move.</p>
 */
record NextPieceOutlook(
        TetrominoType piece,
        int legalPlacements,
        Optional<PlacementCandidate> bestLocalResponse) {

    NextPieceOutlook {
        Objects.requireNonNull(piece, "piece");
        if (legalPlacements < 0) {
            throw new IllegalArgumentException("legalPlacements must not be negative");
        }
        bestLocalResponse = Objects.requireNonNull(bestLocalResponse, "bestLocalResponse");
        if ((legalPlacements == 0) != bestLocalResponse.isEmpty()) {
            throw new IllegalArgumentException(
                    "bestLocalResponse must be present exactly when legal placements exist");
        }
    }

    static NextPieceOutlook evaluate(PlacementCandidate currentCandidate, TetrominoType nextType) {
        Objects.requireNonNull(currentCandidate, "currentCandidate");
        Objects.requireNonNull(nextType, "nextType");

        List<PlacementCandidate> ranked = HeuristicTetrisAgent.rankCandidates(
                BoardSimulator.candidatesForSpawnedPiece(currentCandidate.resultingBoard(), nextType));
        return new NextPieceOutlook(
                nextType,
                ranked.size(),
                ranked.isEmpty() ? Optional.empty() : Optional.of(ranked.getFirst()));
    }

    boolean canPlace() {
        return bestLocalResponse.isPresent();
    }
}
