/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic local baseline that uses the known preview piece for one-step look-ahead.
 *
 * <p>The current move is constrained to the same top-five heuristic safety shortlist used by Jev.
 * For every shortlisted current placement, the agent simulates the known preview piece and finds
 * its best response with the existing one-ply heuristic. It then chooses the current placement
 * whose best preview response ranks highest. If no preview piece is known, or every shortlisted
 * placement makes the preview piece impossible to place, it falls back to the current heuristic
 * top choice.</p>
 */
public final class NextPieceHeuristicTetrisAgent implements TetrisAgent {

    static final int MAX_CURRENT_CANDIDATES = 5;

    @Override
    public AiMove decide(GameSnapshot snapshot) {
        List<PlacementCandidate> currentCandidates =
                HeuristicTetrisAgent.shortlist(snapshot, MAX_CURRENT_CANDIDATES);
        if (currentCandidates.isEmpty()) {
            return AiMove.NONE;
        }
        if (snapshot.nextType().isEmpty()) {
            return currentCandidates.getFirst().move();
        }

        Map<PlacementCandidate, PlacementCandidate> currentByBestResponse = new IdentityHashMap<>();
        List<PlacementCandidate> bestResponses = new ArrayList<>();
        for (PlacementCandidate currentCandidate : currentCandidates) {
            NextPieceOutlook outlook =
                    NextPieceOutlook.evaluate(currentCandidate, snapshot.nextType().orElseThrow());
            outlook.bestLocalResponse().ifPresent(bestResponse -> {
                currentByBestResponse.put(bestResponse, currentCandidate);
                bestResponses.add(bestResponse);
            });
        }

        if (bestResponses.isEmpty()) {
            return currentCandidates.getFirst().move();
        }

        PlacementCandidate bestResponse =
                HeuristicTetrisAgent.rankCandidates(bestResponses).getFirst();
        return currentByBestResponse.get(bestResponse).move();
    }
}
