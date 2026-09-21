/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runs AI decisions away from the caller thread and tracks which request is still current.
 *
 * <p>The primary agent is attempted first. Runtime failures are isolated to the worker virtual
 * thread and fall back to the supplied local agent. Submitting a newer decision, or explicitly
 * invalidating the executor, makes older results stale without requiring unsafe thread
 * interruption.</p>
 */
public final class AiDecisionExecutor {

    private static final Executor VIRTUAL_THREAD_EXECUTOR = command -> Thread.startVirtualThread(command);

    private final TetrisAgent primaryAgent;
    private final TetrisAgent fallbackAgent;
    private final AtomicLong generation = new AtomicLong();

    public AiDecisionExecutor(TetrisAgent primaryAgent, TetrisAgent fallbackAgent) {
        this.primaryAgent = Objects.requireNonNull(primaryAgent, "primaryAgent");
        this.fallbackAgent = Objects.requireNonNull(fallbackAgent, "fallbackAgent");
    }

    /**
     * Submits a new decision and supersedes every earlier request.
     *
     * @param snapshot immutable game state captured by the caller
     * @return request identity plus its asynchronous move result
     */
    public AiDecision submit(GameSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        final long requestGeneration = generation.incrementAndGet();
        final CompletableFuture<AiMove> result =
                CompletableFuture.supplyAsync(() -> decideWithFallback(snapshot), VIRTUAL_THREAD_EXECUTOR);
        return new AiDecision(requestGeneration, result);
    }

    /**
     * Invalidates all submitted decisions without interrupting their worker threads.
     */
    public void invalidate() {
        generation.incrementAndGet();
    }

    /**
     * Returns whether this decision is still the newest request known to the executor.
     */
    public boolean isCurrent(AiDecision decision) {
        return decision != null && generation.get() == decision.generation();
    }

    private AiMove decideWithFallback(GameSnapshot snapshot) {
        try {
            return requireMove(primaryAgent.decide(snapshot));
        }
        catch (RuntimeException primaryFailure) {
            try {
                return requireMove(fallbackAgent.decide(snapshot));
            }
            catch (RuntimeException fallbackFailure) {
                fallbackFailure.addSuppressed(primaryFailure);
                throw fallbackFailure;
            }
        }
    }

    private static AiMove requireMove(AiMove move) {
        return Objects.requireNonNull(move, "TetrisAgent returned null");
    }

    /**
     * One asynchronous decision request.
     */
    public record AiDecision(long generation, CompletableFuture<AiMove> result) {

        public AiDecision {
            Objects.requireNonNull(result, "result");
        }
    }
}
