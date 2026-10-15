/*
 * Copyright (C) 2024-2026 xuMingHai
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai.benchmark;

import org.junit.jupiter.api.Test;
import xyz.xuminghai.tetris.ai.*;
import xyz.xuminghai.tetris.core.BoardPosition;
import xyz.xuminghai.tetris.core.TetrisFactory;
import xyz.xuminghai.tetris.core.TetrominoType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static xyz.xuminghai.tetris.ai.benchmark.GoalWindowPairedRunner.*;

/** Scheduling stubs and artificial boards are not population or latency evidence. */
class GoalWindowPairedRunnerTest {
    @Test
    void warmupIsOncePerArmThenSeedParityDeterminesMeasuredOrder() {
        var calls = new ArrayList<Invocation>();
        // No games executed: this seam tests only session scheduling, not fabricated outcomes.
        var scheduler = new GoalWindowPairedRunner(invocation -> {
            calls.add(invocation);
            return null;
        });
        scheduler.run(List.of(20000L, 20001L), 1000, ignored -> { });
        assertEquals(24, calls.size());
        for (int arm = 0; arm < 2; arm++) {
            for (int index = 0; index < 10; index++) {
                assertEquals(new Invocation(Phase.WARMUP, Arm.values()[arm], 19000 + index, 10),
                        calls.get(arm * 10 + index));
            }
        }
        assertEquals(List.of(
                new Invocation(Phase.MEASURED, Arm.BASELINE, 20000, 1000),
                new Invocation(Phase.MEASURED, Arm.CANDIDATE, 20000, 1000),
                new Invocation(Phase.MEASURED, Arm.CANDIDATE, 20001, 1000),
                new Invocation(Phase.MEASURED, Arm.BASELINE, 20001, 1000)), calls.subList(20, 24));
        assertThrows(IllegalArgumentException.class,
                () -> scheduler.run(List.of(20000L, 20000L), 1000, ignored -> { }));
        assertThrows(IllegalArgumentException.class,
                () -> scheduler.run(List.of(19000L), 1000, ignored -> { }));
        assertEquals(24, calls.size());
    }

    @Test
    void firstFailureStopsSessionAndDoesNotEmitACompleteGameForThatAttempt() {
        var calls = new ArrayList<Invocation>();
        var emitted = new ArrayList<CompletedGame>();
        var failure = new IncompleteRun(new Invocation(Phase.WARMUP, Arm.BASELINE, 19001, 10),
                FailureKind.RUNNER_FAILURE, new GoalWindowGameEvidence().snapshot("BASELINE", 19001),
                0, 0, new IllegalStateException("fixture"));
        var scheduler = new GoalWindowPairedRunner(invocation -> {
            calls.add(invocation);
            if (calls.size() == 2) throw failure;
            return null;
        });
        assertSame(failure, assertThrows(IncompleteRun.class,
                () -> scheduler.run(List.of(20000L), 1000, emitted::add)));
        assertEquals(2, calls.size());
        assertEquals(1, emitted.size());
    }

    @Test
    void actualCleanNonQUsesOriginalCompletionAndExactNanosNotCandidatePrediction() {
        var board = heart();
        board[18][1] = false;
        removeO(board, 2);
        var input = snapshot(board, TetrominoType.O, Optional.empty());
        var selected = BoardSimulator.candidates(input).stream()
                .filter(p -> ShapeTarget.HEART.progress(p.resultingBoard()).cleanCompletion())
                .findFirst().orElseThrow();
        selected = ActionPlanSimulator.requireTerminalPlacement(input, AiPlan.fromPlacement(selected.move()));
        var evidence = new GoalWindowGameEvidence();
        evidence.record(new HeadlessGameRunner.TurnObservation(1, input, selected, 1234567), null, 2345678);
        var facts = evidence.snapshot("BASELINE", 20000);
        assertTrue(facts.turns().getFirst().actual().cleanCompletion());
        assertFalse(facts.turns().getFirst().actualQ().accepted());
        assertEquals(List.of(1234567L), facts.decisionNanos());
        assertEquals("build_shape_outcome,BASELINE,heart,20000,1,1,2.345678,0,1,1,true,2.345678",
                facts.completionCsv());
        assertTrue(facts.predictions().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> evidence.record(
                new HeadlessGameRunner.TurnObservation(1, input, selectedFor(input), 1), null, 3));
        assertEquals(facts, evidence.snapshot("BASELINE", 20000));
    }

    @Test
    void predictedPreviewDoesNotCountUntilARealNextValidatedPlacement() {
        var board = heart();
        removeO(board, 2);
        removeO(board, 6);
        var first = snapshot(board, TetrominoType.O, Optional.of(TetrominoType.O));
        var choice = new AtomicReference<GoalWindowCandidate.Observation>();
        var agent = new GoalWindowCandidate(choice::set);
        var setup = ActionPlanSimulator.requireTerminalPlacement(first, agent.plan(first));
        var evidence = new GoalWindowGameEvidence();
        evidence.record(new HeadlessGameRunner.TurnObservation(1, first, setup, 11), choice.get(), 20);
        var prefix = evidence.snapshot("CANDIDATE", 20000);
        assertEquals(GoalWindowCandidate.Choice.PREVIEW_SETUP, choice.get().choice());
        assertFalse(prefix.turns().getFirst().actual().cleanCompletion());
        assertEquals(GoalWindowGameEvidence.Resolution.NO_NEXT_VALIDATED_TURN,
                prefix.predictions().getFirst().resolution());
        assertEquals("-1", prefix.completionCsv().split(",")[5]);
        var next = snapshot(setup.resultingBoard(), TetrominoType.O, Optional.empty());
        var finish = ActionPlanSimulator.requireTerminalPlacement(next, agent.plan(next));
        evidence.record(new HeadlessGameRunner.TurnObservation(2, next, finish, 13), choice.get(), 40);
        var actual = evidence.snapshot("CANDIDATE", 20000);
        assertEquals("2", actual.completionCsv().split(",")[5]);
        assertEquals(GoalWindowGameEvidence.Resolution.ACTUAL_Q, actual.predictions().getFirst().resolution());
        assertEquals(List.of(11L, 13L), actual.decisionNanos());
        assertEquals(GoalWindowGameEvidence.Resolution.NO_NEXT_VALIDATED_TURN,
                prefix.predictions().getFirst().resolution());
    }

    @Test
    void changedNextBoardDoesNotCreditPredictionEvenWhenActualCleanIsCounted() {
        var board = heart();
        removeO(board, 2);
        removeO(board, 6);
        var first = snapshot(board, TetrominoType.O, Optional.of(TetrominoType.O));
        var choice = new AtomicReference<GoalWindowCandidate.Observation>();
        var agent = new GoalWindowCandidate(choice::set);
        var setup = ActionPlanSimulator.requireTerminalPlacement(first, agent.plan(first));
        var evidence = new GoalWindowGameEvidence();
        evidence.record(new HeadlessGameRunner.TurnObservation(1, first, setup, 1), choice.get(), 2);
        var changed = setup.resultingBoard();
        changed[18][1] = false;
        var next = snapshot(changed, TetrominoType.O, Optional.empty());
        var finish = BoardSimulator.candidates(next).stream()
                .filter(p -> ShapeTarget.HEART.progress(p.resultingBoard()).cleanCompletion())
                .findFirst().orElseThrow();
        finish = ActionPlanSimulator.requireTerminalPlacement(next, AiPlan.fromPlacement(finish.move()));
        evidence.record(new HeadlessGameRunner.TurnObservation(2, next, finish, 3), null, 6);
        var actual = evidence.snapshot("CANDIDATE", 20000);
        assertTrue(actual.turns().get(1).actual().cleanCompletion());
        assertFalse(actual.turns().get(1).actualQ().accepted());
        assertEquals(GoalWindowGameEvidence.Resolution.BOARD_OR_TYPE_CHANGED,
                actual.predictions().getFirst().resolution());
        assertEquals("2", actual.completionCsv().split(",")[5]);
    }

    @Test
    void productionRunnerKeepsOriginalOutcomesAndCrossChecksEveryDecisionCost() {
        var invocation = new Invocation(Phase.MEASURED, Arm.BASELINE, 20000, 3);
        var actual = runGame(invocation);
        var original = new HeadlessGameRunner().runPlanning(20000, 3, new BuildShapeActionPlanningAgent());
        assertEquals(original.piecesPlaced(), actual.result().piecesPlaced());
        assertEquals(original.linesCleared(), actual.result().linesCleared());
        assertEquals(original.boardHealth(), actual.result().boardHealth());
        assertEquals(3, actual.evidence().turns().size());
        assertEquals(actual.result().totalDecisionNanos(),
                actual.evidence().decisionNanos().stream().mapToLong(Long::longValue).sum());
        assertEquals(actual.result().maxDecisionNanos(),
                actual.evidence().decisionNanos().stream().mapToLong(Long::longValue).max().orElseThrow());
        assertEquals(0, actual.illegalPlans());
        assertEquals(0, actual.result().fallbackDecisions());
        assertTrue(actual.evidence().turns().stream().allMatch(t -> t.candidateChoice() == null));
        var candidate = runGame(new Invocation(Phase.MEASURED, Arm.CANDIDATE, 20000, 1));
        assertEquals(GoalWindowCandidate.Choice.BASELINE_CHOICE,
                candidate.evidence().turns().getFirst().candidateChoice().choice());
    }

    @Test
    void originalValidatorDistinguishesInvalidReturnedPlanFromThrownAgentFailure() {
        for (boolean invalid : List.of(false, true)) {
            var calls = new AtomicInteger();
            var failure = assertThrows(IncompleteRun.class, () -> runGame(
                    new Invocation(Phase.MEASURED, Arm.BASELINE, 20000, 3), (arm, observer) -> input -> {
                        if (calls.incrementAndGet() == 2) {
                            if (invalid) return null;
                            throw new IllegalArgumentException("agent fixture failure");
                        }
                        return new BuildShapeActionPlanningAgent().plan(input);
                    }));
            assertEquals(invalid ? FailureKind.ILLEGAL_PLAN : FailureKind.AGENT_FAILURE, failure.kind());
            assertEquals(1, failure.primaryFailures());
            assertEquals(invalid ? 1 : 0, failure.illegalPlans());
            assertEquals(1, failure.prefix().turns().size());
            assertEquals(2, calls.get());
        }
    }

    @Test
    void missingOrDuplicateCandidateMetadataFailsInsteadOfReusingAnEarlierChoice() {
        for (boolean duplicate : List.of(false, true)) {
            var calls = new AtomicInteger();
            var failure = assertThrows(IncompleteRun.class, () -> runGame(
                    new Invocation(Phase.MEASURED, Arm.CANDIDATE, 20000, 3), (arm, observer) -> input -> {
                        int turn = calls.incrementAndGet();
                        if (turn == 1 || duplicate) observer.accept(baselineObservation());
                        if (turn == 2 && duplicate) observer.accept(baselineObservation());
                        return new BuildShapeActionPlanningAgent().plan(input);
                    }));
            assertEquals(FailureKind.EVIDENCE_FAILURE, failure.kind());
            assertEquals(0, failure.primaryFailures());
            assertEquals(0, failure.illegalPlans());
            assertEquals(1, failure.prefix().turns().size());
            assertEquals(2, calls.get());
        }
    }

    @Test
    void eachGameGetsFreshAgentAndWarmupDeathCannotBecomeSuccessfulWarmup() {
        var factories = new AtomicInteger();
        AgentFactory factory = (arm, observer) -> {
            factories.incrementAndGet();
            var turns = new AtomicInteger();
            return input -> {
                assertEquals(1, turns.incrementAndGet());
                return new BuildShapeActionPlanningAgent().plan(input);
            };
        };
        var invocation = new Invocation(Phase.MEASURED, Arm.BASELINE, 20000, 1);
        runGame(invocation, factory);
        runGame(invocation, factory);
        assertEquals(2, factories.get());
        // Legal hard drops at the spawn column die before ten: no fallback or shortened warmup.
        var failed = assertThrows(IncompleteRun.class, () -> runGame(
                new Invocation(Phase.WARMUP, Arm.BASELINE, 19000, 10),
                (arm, observer) -> input -> new AiPlan(List.of(AiAction.HARD_DROP))));
        assertEquals(FailureKind.EVIDENCE_FAILURE, failed.kind());
        assertTrue(failed.prefix().turns().size() < 10);
        assertEquals(0, failed.primaryFailures());
    }

    private static GoalWindowCandidate.Observation baselineObservation() {
        return new GoalWindowCandidate.Observation(GoalWindowCandidate.Choice.BASELINE_CHOICE,
                2, 0, List.of(), List.of());
    }

    private static PlacementCandidate selectedFor(GameSnapshot input) {
        return ActionPlanSimulator.requireTerminalPlacement(input, AiPlan.fromPlacement(
                BoardSimulator.candidates(input).getFirst().move()));
    }

    private static boolean[][] heart() {
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

    private static GameSnapshot snapshot(boolean[][] board, TetrominoType type, Optional<TetrominoType> next) {
        var piece = TetrisFactory.create(type);
        piece.downMove();
        return new GameSnapshot(20, 10, board, type, Arrays.stream(piece.getCells())
                .map(c -> new BoardPosition(c.getRow(), c.getCol())).toList(), next);
    }
}
