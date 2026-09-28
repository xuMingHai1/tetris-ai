/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import org.junit.jupiter.api.Test;
import xyz.xuminghai.tetris.core.TetrominoType;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreviewRescueActionPlanningAgentTest {

    @Test
    void normalBuildShapeDecisionIsPreservedWhenPreviewIsPlayableOrAbsent() {
        GameSnapshot base = BoardSimulator.snapshotForSpawnedPiece(
                new boolean[20][10], TetrominoType.T);
        GameSnapshot knownPreview = new GameSnapshot(
                base.rows(), base.cols(), base.occupied(), base.currentType(),
                base.currentCells(), TetrominoType.I);
        List<PreviewRescueActionPlanningAgent.Observation> observations = new ArrayList<>();
        PreviewRescueActionPlanningAgent agent =
                new PreviewRescueActionPlanningAgent(observations::add);

        assertEquals(new BuildShapeActionPlanningAgent().plan(base), agent.plan(base));
        assertEquals(new BuildShapeActionPlanningAgent().plan(knownPreview), agent.plan(knownPreview));
        assertEquals(2, observations.size());
        assertFalse(observations.getFirst().previewUnrecoverable());
        assertFalse(observations.getLast().replaced());
        assertTrue(observations.getLast().detectionNanos() >= 0);
    }
}
