/*
 * Copyright (C) 2024-2026 xuMingHai
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import org.junit.jupiter.api.Test;
import xyz.xuminghai.tetris.core.BoardPosition;
import xyz.xuminghai.tetris.core.TetrominoType;

import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Artificial occupancy fixtures, never empty-board construction or population evidence. */
class GoalWindowCandidateTest {
    @Test
    void terminalSeparatesCleanFromAllThreeExtraHoleRolesAndHeight() {
        var board = heart();
        var q = GoalWindowCandidate.terminal(board);
        assertTrue(q.accepted());
        assertEquals(12, q.rawHoles());
        board[18][1] = false;
        q = GoalWindowCandidate.terminal(board);
        assertTrue(q.clean());
        assertFalse(q.accepted());
        assertEquals(1, q.supportHoles());
        board = heart();
        board[13][2] = false;
        q = GoalWindowCandidate.terminal(board);
        assertFalse(q.accepted());
        assertEquals(1, q.requiredHoles());
        board = heart();
        board[18][0] = true;
        q = GoalWindowCandidate.terminal(board);
        assertTrue(q.clean());
        assertEquals(1, q.outsideHoles());
        assertFalse(q.accepted());
        board = heart();
        board[11][2] = true;
        q = GoalWindowCandidate.terminal(board);
        assertTrue(q.clean());
        assertEquals(9, q.maxHeight());
        assertFalse(q.accepted());
        board = heart();
        board[12][1] = true;
        q = GoalWindowCandidate.terminal(board);
        assertEquals(32, q.matchedRequired());
        assertEquals(1, q.forbiddenOccupied());
        assertFalse(q.accepted());
    }

    @Test
    void immediateWindowOverridesOriginalGuardAndMatchesRealReplay() {
        var board = heart();
        removeO(board, 2);
        var snapshot = snapshot(board, TetrominoType.O, Optional.of(TetrominoType.I));
        var observed = new AtomicReference<GoalWindowCandidate.Observation>();
        var plan = new GoalWindowCandidate(observed::set).plan(snapshot);
        assertEquals(GoalWindowCandidate.Choice.IMMEDIATE_Q, observed.get().choice());
        assertEquals(1, observed.get().enumerations());
        assertTrue(observed.get().windows().stream()
                .allMatch(w -> w.previewRank() == 0 && !w.originalGuard().ordinaryAllowed()));
        var ranked = ActionPlanCandidates.ranked(snapshot);
        int first = -1;
        for (int i = 0; i < ranked.size(); i++) {
            if (GoalWindowCandidate.terminal(ranked.get(i).placement().resultingBoard()).accepted()) {
                first = i;
                break;
            }
        }
        assertTrue(first >= 0);
        assertEquals(ranked.get(first).plan(), plan);
        assertEquals(first + 1, observed.get().windows().getFirst().currentRank());
        assertTrue(Arrays.deepEquals(heart(),
                ActionPlanSimulator.requireTerminalPlacement(snapshot, plan).resultingBoard()));
    }

    @Test
    void previewSetupMustRemainGuardedAndRecomputeAtTheActualNextSnapshot() {
        var board = heart();
        removeO(board, 2);
        removeO(board, 6);
        var snapshot = snapshot(board, TetrominoType.O, Optional.of(TetrominoType.O));
        var observed = new AtomicReference<GoalWindowCandidate.Observation>();
        var agent = new GoalWindowCandidate(observed::set);
        var plan = agent.plan(snapshot);
        assertEquals(GoalWindowCandidate.Choice.PREVIEW_SETUP, observed.get().choice());
        var firstObservation = observed.get();
        assertTrue(firstObservation.enumerations() <= 6);
        assertEquals(1, firstObservation.windows().getFirst().currentRank());
        assertTrue(firstObservation.setups().stream().filter(s -> s.currentRank() > 1)
                .allMatch(s -> !s.allowed() && s.originalGuard().danger()));
        assertTrue(firstObservation.setups().stream()
                .filter(s -> s.currentRank() > 1 && s.allowed())
                .allMatch(s -> s.originalGuard().ordinaryAllowed()));
        var selected = ActionPlanSimulator.requireTerminalPlacement(snapshot, plan);
        assertFalse(ShapeTarget.HEART.progress(selected.resultingBoard()).cleanCompletion());
        var next = BoardSimulator.snapshotForSpawnedPiece(selected.resultingBoard(), TetrominoType.O);
        var finish = agent.plan(next);
        assertEquals(GoalWindowCandidate.Choice.IMMEDIATE_Q, observed.get().choice());
        assertTrue(GoalWindowCandidate.terminal(
                ActionPlanSimulator.requireTerminalPlacement(next, finish).resultingBoard()).accepted());
    }

    @Test
    void rowClearReanchorsTerminalUsingOnlyTheProductionResultingBoard() {
        var board = new boolean[20][10];
        var complete = heart();
        for (int row = 1; row < 20; row++) board[row - 1] = complete[row].clone();
        Arrays.fill(board[19], true);
        board[19][0] = false;
        var snapshot = snapshot(board, TetrominoType.I, Optional.empty());
        var observed = new AtomicReference<GoalWindowCandidate.Observation>();
        var plan = new GoalWindowCandidate(observed::set).plan(snapshot);
        var selected = ActionPlanSimulator.requireTerminalPlacement(snapshot, plan);
        assertEquals(GoalWindowCandidate.Choice.IMMEDIATE_Q, observed.get().choice());
        assertEquals(1, selected.clearedLines());
        assertTrue(GoalWindowCandidate.terminal(selected.resultingBoard()).accepted());
        assertFalse(GoalWindowCandidate.terminal(board).accepted());
    }

    @Test
    void changedRealPreviewStateCanLoseThePredictedWindowAndMustBeReevaluated() {
        var board = heart();
        removeO(board, 2);
        removeO(board, 6);
        var snapshot = snapshot(board, TetrominoType.O, Optional.of(TetrominoType.O));
        var observed = new AtomicReference<GoalWindowCandidate.Observation>();
        var agent = new GoalWindowCandidate(observed::set);
        var setup = ActionPlanSimulator.requireTerminalPlacement(snapshot, agent.plan(snapshot));
        assertEquals(GoalWindowCandidate.Choice.PREVIEW_SETUP, observed.get().choice());
        var changed = setup.resultingBoard();
        changed[18][1] = false; // An unfillable support hole invalidates Q even if visually clean.
        var actual = snapshot(changed, TetrominoType.O, Optional.empty());
        assertEquals(new BuildShapeActionPlanningAgent().plan(actual), agent.plan(actual));
        assertEquals(GoalWindowCandidate.Choice.BASELINE_CHOICE, observed.get().choice());
        assertTrue(observed.get().windows().isEmpty());
        // Actual completion is the production clean predicate, never the experiment's Q.
        var cleanNonQ = heart();
        cleanNonQ[18][1] = false;
        removeO(cleanNonQ, 2);
        var cleanSnapshot = snapshot(cleanNonQ, TetrominoType.O, Optional.empty());
        var completionPlan = ActionPlanCandidates.ranked(cleanSnapshot).stream()
                .filter(c -> ShapeTarget.HEART.progress(c.placement().resultingBoard()).cleanCompletion())
                .findFirst().orElseThrow().plan();
        var outcome = ActionPlanSimulator.requireTerminalPlacement(cleanSnapshot, completionPlan);
        assertTrue(ShapeTarget.HEART.progress(outcome.resultingBoard()).cleanCompletion());
        assertFalse(GoalWindowCandidate.terminal(outcome.resultingBoard()).accepted());
    }

    @Test
    void absentPreviewUsesExactlyTheOriginalGreedyPlan() {
        var snapshot = snapshot(new boolean[20][10], TetrominoType.T, Optional.empty());
        var observed = new AtomicReference<GoalWindowCandidate.Observation>();
        var actual = new GoalWindowCandidate(observed::set).plan(snapshot);
        assertEquals(new BuildShapeActionPlanningAgent().plan(snapshot), actual);
        assertEquals(GoalWindowCandidate.Choice.BASELINE_CHOICE, observed.get().choice());
        assertEquals(2, observed.get().enumerations());
        assertTrue(observed.get().setups().isEmpty());
    }

    @Test
    void noWindowStillFullyEvaluatesTheFixedEnvelopeBeforeBaseline() {
        var snapshot = snapshot(new boolean[20][10], TetrominoType.O, Optional.of(TetrominoType.I));
        var observed = new AtomicReference<GoalWindowCandidate.Observation>();
        var actual = new GoalWindowCandidate(observed::set).plan(snapshot);
        assertEquals(new BuildShapeActionPlanningAgent().plan(snapshot), actual);
        assertEquals(GoalWindowCandidate.Choice.BASELINE_CHOICE, observed.get().choice());
        assertEquals(5, observed.get().setups().size());
        assertEquals(2 + observed.get().setups().stream().filter(GoalWindowCandidate.Setup::allowed).count(),
                observed.get().enumerations());
        assertTrue(observed.get().enumerations() <= 7);
        assertTrue(observed.get().windows().isEmpty());
    }

    @Test
    void impossibleSnapshotRetainsNoLandingSemanticsAndRejectsProtocolDimensions() {
        var board = new boolean[20][10];
        Arrays.stream(board).forEach(row -> Arrays.fill(row, true));
        var snapshot = snapshot(board, TetrominoType.O, Optional.of(TetrominoType.I));
        assertFalse(ActionPlanSimulator.hasReachableTerminalPlacement(snapshot));
        assertEquals(new BuildShapeActionPlanningAgent().plan(snapshot),
                new GoalWindowCandidate().plan(snapshot));
        assertThrows(IllegalArgumentException.class, () -> new GoalWindowCandidate().plan(
                new GameSnapshot(21, 10, new boolean[21][10], snapshot.currentType(), snapshot.currentCells())));
    }

    @Test
    void partialPlaybackOrientationAndChangedSnapshotsNeverReuseAnOldPlan() {
        var original = snapshot(new boolean[20][10], TetrominoType.T, Optional.empty());
        var piece = original.restoreCurrentPiece();
        piece.rotateClockwise();
        piece.downMove();
        piece.leftMove();
        var rotated = new GameSnapshot(20, 10, original.occupied(), TetrominoType.T,
                Arrays.stream(piece.getCells()).map(c -> new BoardPosition(c.getRow(), c.getCol())).toList(),
                1, Optional.empty());
        var agent = new GoalWindowCandidate();
        assertEquals(new BuildShapeActionPlanningAgent().plan(rotated), agent.plan(rotated));
        assertNotNull(ActionPlanSimulator.requireTerminalPlacement(rotated, agent.plan(rotated)));
        var missing = heart();
        removeO(missing, 2);
        var terminal = snapshot(missing, TetrominoType.O, Optional.empty());
        var first = agent.plan(terminal);
        agent.plan(rotated);
        assertEquals(first, agent.plan(terminal));
        assertEquals(first, new GoalWindowCandidate().plan(terminal));
    }

    static boolean[][] heart() {
        var board = new boolean[20][10];
        for (int row = 0; row < 20; row++) {
            for (int col = 0; col < 10; col++) {
                var role = ShapeTarget.HEART.roleAtBoardCell(20, 10, row, col);
                board[row][col] = role == ShapeTarget.CellRole.REQUIRED
                        || role == ShapeTarget.CellRole.SUPPORT_ALLOWED;
            }
        }
        return board;
    }

    private static void removeO(boolean[][] board, int col) {
        for (int row = 12; row <= 13; row++) {
            board[row][col] = false;
            board[row][col + 1] = false;
        }
    }

    static GameSnapshot snapshot(boolean[][] board, TetrominoType type, Optional<TetrominoType> preview) {
        var spawned = BoardSimulator.snapshotForSpawnedPiece(board, type);
        return new GameSnapshot(20, 10, board, type, spawned.currentCells(), 0, preview);
    }
}
