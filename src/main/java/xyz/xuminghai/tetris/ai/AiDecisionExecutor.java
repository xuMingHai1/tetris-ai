/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
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

    private final AiPlanningAgent primaryAgent;
    private final AiPlanningAgent fallbackAgent;
    private final AtomicLong generation = new AtomicLong();

    /**
     * Creates an executor for action-native planning agents.
     */
    public AiDecisionExecutor(AiPlanningAgent primaryAgent, AiPlanningAgent fallbackAgent) {
        this.primaryAgent = Objects.requireNonNull(primaryAgent, "primaryAgent");
        this.fallbackAgent = Objects.requireNonNull(fallbackAgent, "fallbackAgent");
    }

    /**
     * Submits a new decision and supersedes every earlier request.
     *
     * @param snapshot immutable game state captured by the caller
     * @return request identity plus its asynchronous plan result
     */
    public AiDecision submit(GameSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        final long requestGeneration = generation.incrementAndGet();
        final CompletableFuture<AiPlan> result =
                CompletableFuture.supplyAsync(() -> planWithFallback(snapshot), VIRTUAL_THREAD_EXECUTOR);
        return new AiDecision(requestGeneration, result);
    }

    /**
     * Invalidates all submitted decisions without interrupting their worker threads.
     */
    public void invalidate() {
        generation.incrementAndGet();
    }

    /**
     * Supersedes the current primary decision and evaluates the local fallback immediately.
     *
     * <p>This is used when the live game is about to mutate the snapshot that a remote decision
     * was based on. The fallback therefore gets the last chance to act on that unchanged state.</p>
     *
     * @param snapshot immutable state that is still current in the live game
     * @return validated fallback plan for the snapshot
     */
    public AiPlan fallbackNow(GameSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        generation.incrementAndGet();
        return AiPlanValidator.requireValid(fallbackAgent.plan(snapshot));
    }

    /**
     * Returns whether this decision is still the newest request known to the executor.
     */
    public boolean isCurrent(AiDecision decision) {
        return decision != null && generation.get() == decision.generation();
    }

    private AiPlan planWithFallback(GameSnapshot snapshot) {
        try {
            return AiPlanValidator.requireValid(primaryAgent.plan(snapshot));
        }
        catch (RuntimeException primaryFailure) {
            try {
                return AiPlanValidator.requireValid(fallbackAgent.plan(snapshot));
            }
            catch (RuntimeException fallbackFailure) {
                fallbackFailure.addSuppressed(primaryFailure);
                throw fallbackFailure;
            }
        }
    }

    /**
     * One asynchronous decision request.
     */
    public record AiDecision(long generation, CompletionStage<AiPlan> result) {

        public AiDecision {
            Objects.requireNonNull(result, "result");
        }
    }
}
