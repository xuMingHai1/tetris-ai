/*
 * Copyright (C) 2024-2026 xuMingHai
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai.benchmark;

import xyz.xuminghai.tetris.ai.AiPlan;
import xyz.xuminghai.tetris.ai.AiPlanningAgent;
import xyz.xuminghai.tetris.ai.BuildShapeActionPlanningAgent;
import xyz.xuminghai.tetris.ai.GameSnapshot;
import xyz.xuminghai.tetris.ai.GoalWindowCandidate;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Test-only session orchestration around the original game loop.
 * A caller must preserve each emitted game and the partial evidence on IncompleteRun.
 */
final class GoalWindowPairedRunner {
    private final GameJob gameJob;

    GoalWindowPairedRunner() {
        this(GoalWindowPairedRunner::runGame);
    }

    /** Scheduling seam only; production execution always uses runPlanningObserved. */
    GoalWindowPairedRunner(GameJob gameJob) {
        this.gameJob = Objects.requireNonNull(gameJob, "gameJob");
    }

    void run(List<Long> seeds, int pieceLimit, Consumer<CompletedGame> output) {
        seeds = List.copyOf(seeds);
        Objects.requireNonNull(output, "output");
        if (seeds.isEmpty() || new HashSet<>(seeds).size() != seeds.size() || pieceLimit <= 0
                || seeds.stream().anyMatch(seed -> seed >= 19000 && seed <= 19009)) {
            throw new IllegalArgumentException("unique measured seeds outside warmup and positive horizon required");
        }
        // Exactly one 100-decision warmup per arm, before any measured game, in this JVM.
        for (var arm : Arm.values()) {
            for (long seed = 19000; seed <= 19009; seed++) {
                output.accept(gameJob.run(new Invocation(Phase.WARMUP, arm, seed, 10)));
            }
        }
        for (long seed : seeds) {
            var first = seed % 2 == 0 ? Arm.BASELINE : Arm.CANDIDATE;
            var second = first == Arm.BASELINE ? Arm.CANDIDATE : Arm.BASELINE;
            output.accept(gameJob.run(new Invocation(Phase.MEASURED, first, seed, pieceLimit)));
            output.accept(gameJob.run(new Invocation(Phase.MEASURED, second, seed, pieceLimit)));
        }
    }

    static CompletedGame runGame(Invocation invocation) {
        return runGame(invocation, GoalWindowPairedRunner::agent);
    }

    private static AiPlanningAgent agent(Arm arm, Consumer<GoalWindowCandidate.Observation> observer) {
        return arm == Arm.BASELINE ? new BuildShapeActionPlanningAgent() : new GoalWindowCandidate(observer);
    }

    static void runSession(List<Long> seeds, int pieceLimit, EventSink sink) {
        new GoalWindowPairedRunner(invocation -> runObservedGame(invocation,
                GoalWindowPairedRunner::agent, sink)).run(seeds, pieceLimit, ignored -> { });
    }

    static CompletedGame runObservedGame(Invocation invocation, AgentFactory factory, EventSink sink) {
        sink.started(invocation);
        try {
            var game = runGame(invocation, factory, update -> sink.turn(invocation, update));
            sink.completed(game);
            return game;
        }
        catch (IncompleteRun failure) {
            sink.failed(failure);
            throw failure;
        }
    }

    /** Factory receives no seed, phase, run order or future pieces. Fresh agent on every call. */
    static CompletedGame runGame(Invocation invocation, AgentFactory factory) {
        return runGame(invocation, factory, ignored -> { });
    }

    private static CompletedGame runGame(Invocation invocation, AgentFactory factory,
                                         Consumer<GoalWindowGameEvidence.Update> output) {
        var evidence = new GoalWindowGameEvidence();
        var audit = new AuditedAgent();
        long started = System.nanoTime();
        try {
            audit.delegate = Objects.requireNonNull(factory.create(invocation.arm(), audit::observe));
            var result = new HeadlessGameRunner().runPlanningObserved(invocation.seed(),
                    invocation.pieceLimit(), audit, null, turn -> {
                        // Reaching this callback proves the original terminal validator accepted.
                        audit.awaitingValidation = false;
                        audit.inEvidence = true;
                        var choice = audit.requireObservation(invocation.arm());
                        var update = evidence.record(turn, choice, System.nanoTime() - started);
                        output.accept(update);
                        audit.inEvidence = false;
                    });
            audit.inEvidence = true;
            evidence.requireMatches(result);
            requireFullWarmup(invocation, result);
            return new CompletedGame(invocation, result,
                    evidence.snapshot(invocation.arm().name(), invocation.seed()), 0);
        }
        catch (RuntimeException failure) {
            var kind = audit.delegateFailed ? FailureKind.AGENT_FAILURE
                    : audit.awaitingValidation ? FailureKind.ILLEGAL_PLAN
                    : audit.inEvidence ? FailureKind.EVIDENCE_FAILURE : FailureKind.RUNNER_FAILURE;
            // With fallback=null, the production loop stops on the first primary failure.
            int primaryFailures = kind == FailureKind.AGENT_FAILURE || kind == FailureKind.ILLEGAL_PLAN ? 1 : 0;
            throw new IncompleteRun(invocation, kind,
                    evidence.snapshot(invocation.arm().name(), invocation.seed()), primaryFailures,
                    kind == FailureKind.ILLEGAL_PLAN ? 1 : 0, failure);
        }
    }

    static void requireFullWarmup(Invocation invocation, GameBenchmarkResult result) {
        if (invocation.phase() == Phase.WARMUP && result.decisions() != invocation.pieceLimit()) {
            throw new IllegalStateException("warmup ended before its required ten decisions");
        }
    }

    enum Arm { BASELINE, CANDIDATE }
    enum Phase { WARMUP, MEASURED }
    enum FailureKind { AGENT_FAILURE, ILLEGAL_PLAN, EVIDENCE_FAILURE, RUNNER_FAILURE }
    record Invocation(Phase phase, Arm arm, long seed, int pieceLimit) {
        Invocation {
            Objects.requireNonNull(phase, "phase");
            Objects.requireNonNull(arm, "arm");
            if (pieceLimit <= 0 || phase == Phase.WARMUP
                    && (pieceLimit != 10 || seed < 19000 || seed > 19009)) {
                throw new IllegalArgumentException("invalid game or fixed warmup configuration");
            }
        }
    }
    record CompletedGame(Invocation invocation, GameBenchmarkResult result,
                         GoalWindowGameEvidence.Snapshot evidence, int illegalPlans) { }

    static final class IncompleteRun extends RuntimeException {
        private final Invocation invocation;
        private final FailureKind kind;
        private final GoalWindowGameEvidence.Snapshot prefix;
        private final int primaryFailures;
        private final int illegalPlans;

        IncompleteRun(Invocation invocation, FailureKind kind, GoalWindowGameEvidence.Snapshot prefix,
                      int primaryFailures, int illegalPlans, RuntimeException cause) {
            super("INCOMPLETE " + invocation + ": " + kind, cause);
            this.invocation = invocation;
            this.kind = kind;
            this.prefix = prefix;
            this.primaryFailures = primaryFailures;
            this.illegalPlans = illegalPlans;
        }

        Invocation invocation() { return invocation; }
        FailureKind kind() { return kind; }
        GoalWindowGameEvidence.Snapshot prefix() { return prefix; }
        int primaryFailures() { return primaryFailures; }
        int illegalPlans() { return illegalPlans; }
    }

    @FunctionalInterface
    interface GameJob { CompletedGame run(Invocation invocation); }

    @FunctionalInterface
    interface AgentFactory { AiPlanningAgent create(Arm arm, Consumer<GoalWindowCandidate.Observation> observer); }

    interface EventSink {
        void started(Invocation invocation);
        void turn(Invocation invocation, GoalWindowGameEvidence.Update update);
        void completed(CompletedGame game);
        void failed(IncompleteRun failure);
    }

    private static final class AuditedAgent implements AiPlanningAgent {
        private AiPlanningAgent delegate;
        private boolean awaitingValidation;
        private boolean delegateFailed;
        private boolean inEvidence;
        private int observations;
        private GoalWindowCandidate.Observation observation;

        @Override
        public AiPlan plan(GameSnapshot snapshot) {
            observations = 0;
            observation = null;
            try {
                var plan = delegate.plan(snapshot);
                awaitingValidation = true;
                return plan;
            }
            catch (RuntimeException failure) {
                delegateFailed = true;
                throw failure;
            }
        }

        void observe(GoalWindowCandidate.Observation value) {
            observations++;
            observation = Objects.requireNonNull(value);
        }

        GoalWindowCandidate.Observation requireObservation(Arm arm) {
            if (arm == Arm.CANDIDATE ? observations != 1 : observations != 0) {
                throw new IllegalStateException("missing, duplicate or wrong-arm candidate observation");
            }
            return observation;
        }
    }
}
