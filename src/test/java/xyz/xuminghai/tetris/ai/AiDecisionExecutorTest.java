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
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiDecisionExecutorTest {

    @Test
    void runsPrimaryDecisionOnVirtualThreadWithoutCallingFallback() {
        AtomicBoolean virtualThread = new AtomicBoolean();
        AtomicInteger fallbackCalls = new AtomicInteger();
        AiMove expected = new AiMove(1, -2);
        AiDecisionExecutor executor = new AiDecisionExecutor(
                (AiPlanningAgent) snapshot -> {
                    virtualThread.set(Thread.currentThread().isVirtual());
                    return AiPlan.fromPlacement(expected);
                },
                snapshot -> {
                    fallbackCalls.incrementAndGet();
                    return AiPlan.fromPlacement(AiMove.NONE);
                });

        AiDecisionExecutor.AiDecision decision = executor.submit(snapshot());

        assertEquals(AiPlan.fromPlacement(expected), decision.result().toCompletableFuture().join());
        assertTrue(virtualThread.get());
        assertEquals(0, fallbackCalls.get());
        assertTrue(executor.isCurrent(decision));
    }

    @Test
    void fallsBackWhenPrimaryAgentFails() {
        AtomicInteger fallbackCalls = new AtomicInteger();
        AiMove fallbackMove = new AiMove(0, 3);
        AiDecisionExecutor executor = new AiDecisionExecutor(
                (AiPlanningAgent) snapshot -> {
                    throw new IllegalStateException("primary unavailable");
                },
                (AiPlanningAgent) snapshot -> {
                    fallbackCalls.incrementAndGet();
                    return AiPlan.fromPlacement(fallbackMove);
                });

        AiDecisionExecutor.AiDecision decision = executor.submit(snapshot());

        assertEquals(AiPlan.fromPlacement(fallbackMove), decision.result().toCompletableFuture().join());
        assertEquals(1, fallbackCalls.get());
    }

    @Test
    void newerSubmissionMakesOlderDecisionStale() {
        AiDecisionExecutor executor =
                new AiDecisionExecutor(
                        snapshot -> AiPlan.fromPlacement(AiMove.NONE),
                        snapshot -> AiPlan.fromPlacement(AiMove.NONE));

        AiDecisionExecutor.AiDecision first = executor.submit(snapshot());
        AiDecisionExecutor.AiDecision second = executor.submit(snapshot());

        assertFalse(executor.isCurrent(first));
        assertTrue(executor.isCurrent(second));
    }

    @Test
    void explicitInvalidationMakesPendingDecisionStale() {
        AiDecisionExecutor executor =
                new AiDecisionExecutor(
                        snapshot -> AiPlan.fromPlacement(AiMove.NONE),
                        snapshot -> AiPlan.fromPlacement(AiMove.NONE));
        AiDecisionExecutor.AiDecision decision = executor.submit(snapshot());

        executor.invalidate();

        assertFalse(executor.isCurrent(decision));
    }

    @Test
    void immediateFallbackInvalidatesPendingDecision() {
        AtomicInteger fallbackCalls = new AtomicInteger();
        AiMove fallbackMove = new AiMove(0, -2);
        AiDecisionExecutor executor = new AiDecisionExecutor(
                (AiPlanningAgent) snapshot -> AiPlan.fromPlacement(AiMove.NONE),
                snapshot -> {
                    fallbackCalls.incrementAndGet();
                    return AiPlan.fromPlacement(fallbackMove);
                });
        AiDecisionExecutor.AiDecision decision = executor.submit(snapshot());

        AiPlan plan = executor.fallbackNow(snapshot());

        assertEquals(AiPlan.fromPlacement(fallbackMove), plan);
        assertEquals(1, fallbackCalls.get());
        assertFalse(executor.isCurrent(decision));
    }

    @Test
    void acceptsActionNativePrimaryPlan() {
        AiPlan expected = new AiPlan(List.of(
                AiAction.LEFT,
                AiAction.SOFT_DROP,
                AiAction.ROTATE_CLOCKWISE,
                AiAction.HARD_DROP));
        AiDecisionExecutor executor = new AiDecisionExecutor(
                (AiPlanningAgent) snapshot -> expected,
                (AiPlanningAgent) snapshot -> AiPlan.fromPlacement(AiMove.NONE));

        assertEquals(expected, executor.submit(snapshot()).result().toCompletableFuture().join());
    }

    @Test
    void fallsBackWhenPrimaryPlanIsStructurallyInvalid() {
        AiPlan fallback = AiPlan.fromPlacement(new AiMove(0, 1));
        AiDecisionExecutor executor = new AiDecisionExecutor(
                (AiPlanningAgent) snapshot -> new AiPlan(List.of(AiAction.HARD_DROP, AiAction.LEFT)),
                (AiPlanningAgent) snapshot -> fallback);

        assertEquals(fallback, executor.submit(snapshot()).result().toCompletableFuture().join());
    }

    @Test
    void exposesFallbackFailureAndPreservesPrimaryFailure() {
        AiDecisionExecutor executor = new AiDecisionExecutor(
                (AiPlanningAgent) snapshot -> {
                    throw new IllegalStateException("primary failure");
                },
                snapshot -> {
                    throw new IllegalArgumentException("fallback failure");
                });

        CompletionException failure =
                assertThrows(CompletionException.class, () -> executor.submit(snapshot()).result().toCompletableFuture().join());

        assertEquals("fallback failure", failure.getCause().getMessage());
        assertEquals(1, failure.getCause().getSuppressed().length);
        assertEquals("primary failure", failure.getCause().getSuppressed()[0].getMessage());
    }

    private static GameSnapshot snapshot() {
        return new GameSnapshot(
                20,
                10,
                new boolean[20][10],
                TetrominoType.T,
                List.of(
                        new BoardPosition(0, 4),
                        new BoardPosition(1, 3),
                        new BoardPosition(1, 4),
                        new BoardPosition(1, 5)));
    }
}
