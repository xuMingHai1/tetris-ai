/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ObjectiveRiskControllerTest {

    private final ObjectiveRiskController controller = ObjectiveRiskController.adaptive();

    @Test
    void selectsBalancedForLowRiskBoard() {
        ObjectiveRiskController.Decision decision =
                controller.decide(candidate(boardWithTopCellAt(10), 0));

        assertEquals(ObjectiveRiskController.RiskLevel.LOW, decision.level());
        assertEquals(ObjectiveRiskProfile.BALANCED, decision.profile());
        assertEquals(10, decision.headroom());
    }

    @Test
    void selectsConservativeForNormalBoard() {
        ObjectiveRiskController.Decision decision =
                controller.decide(candidate(boardWithTopCellAt(12), 2));

        assertEquals(ObjectiveRiskController.RiskLevel.NORMAL, decision.level());
        assertEquals(ObjectiveRiskProfile.CONSERVATIVE, decision.profile());
        assertEquals(12, decision.headroom());
    }

    @Test
    void selectsStrictWhenHeadroomIsLow() {
        ObjectiveRiskController.Decision decision =
                controller.decide(candidate(boardWithTopCellAt(5), 0));

        assertEquals(ObjectiveRiskController.RiskLevel.DANGER, decision.level());
        assertEquals(ObjectiveRiskProfile.STRICT, decision.profile());
        assertEquals(5, decision.headroom());
    }

    @Test
    void selectsStrictWhenHoleCountIsHigh() {
        ObjectiveRiskController.Decision decision =
                controller.decide(candidate(boardWithTopCellAt(19), 5));

        assertEquals(ObjectiveRiskController.RiskLevel.DANGER, decision.level());
        assertEquals(ObjectiveRiskProfile.STRICT, decision.profile());
    }

    @Test
    void fixedControllerKeepsCalibrationProfileWhileStillClassifyingBoardRisk() {
        ObjectiveRiskController.Decision decision =
                ObjectiveRiskController.fixed(ObjectiveRiskProfile.RISKY)
                        .decide(candidate(boardWithTopCellAt(4), 0));

        assertEquals(ObjectiveRiskController.RiskLevel.DANGER, decision.level());
        assertEquals(ObjectiveRiskProfile.RISKY, decision.profile());
    }

    private static PlacementCandidate candidate(boolean[][] board, int holes) {
        return new PlacementCandidate(AiMove.NONE, board, 0, 0, holes, 0);
    }

    private static boolean[][] boardWithTopCellAt(int row) {
        boolean[][] board = new boolean[20][10];
        board[row][0] = true;
        return board;
    }
}
