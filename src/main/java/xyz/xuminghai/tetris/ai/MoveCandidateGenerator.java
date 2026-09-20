/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import java.util.ArrayList;
import java.util.List;

/**
 * Enumerates legal rotate-then-shift placements for the current snapshot.
 */
public final class MoveCandidateGenerator {

    public List<MoveCandidate> generate(GameSnapshot snapshot) {
        List<MoveCandidate> candidates = new ArrayList<>();
        for (int rotations = 0; rotations < snapshot.currentType().rotationStates(); rotations++) {
            for (int shift = -snapshot.cols(); shift <= snapshot.cols(); shift++) {
                MoveCandidate candidate = BoardSimulator.simulate(snapshot, rotations, shift);
                if (candidate != null) {
                    candidates.add(candidate);
                }
            }
        }
        return List.copyOf(candidates);
    }
}
