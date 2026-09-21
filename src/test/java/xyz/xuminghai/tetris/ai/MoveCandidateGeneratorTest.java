/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import org.junit.jupiter.api.Test;
import xyz.xuminghai.tetris.core.BoardPosition;
import xyz.xuminghai.tetris.core.TetrominoType;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MoveCandidateGeneratorTest {

    @Test
    void exposesDeterministicFeaturesForLegalPlacements() {
        GameSnapshot snapshot = lineCompletionSnapshot();

        List<MoveCandidate> candidates = new MoveCandidateGenerator().generate(snapshot);

        assertTrue(candidates.stream().anyMatch(candidate ->
                candidate.move().equals(new AiMove(0, 3))
                        && candidate.clearedLines() == 1
                        && candidate.holes() == 0));
    }

    static GameSnapshot lineCompletionSnapshot() {
        boolean[][] board = new boolean[20][10];
        for (int col = 0; col < 6; col++) {
            board[19][col] = true;
        }
        return new GameSnapshot(
                20,
                10,
                board,
                TetrominoType.I,
                List.of(
                        new BoardPosition(-1, 3),
                        new BoardPosition(-1, 4),
                        new BoardPosition(-1, 5),
                        new BoardPosition(-1, 6)));
    }
}
