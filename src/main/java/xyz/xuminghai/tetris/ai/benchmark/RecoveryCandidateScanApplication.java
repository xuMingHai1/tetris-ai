/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai.benchmark;

import xyz.xuminghai.tetris.ai.BuildShapeActionPlanningAgent;
import xyz.xuminghai.tetris.ai.BuildShapeDecisionObservation;
import xyz.xuminghai.tetris.ai.RecoveryCandidateScanBenchmark;
import xyz.xuminghai.tetris.ai.RecoveryRobustnessBenchmark;
import xyz.xuminghai.tetris.ai.ShapeProgress;
import xyz.xuminghai.tetris.ai.ShapeTarget;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Scans all action-native alternatives only when production BUILD_SHAPE has already selected
 * SURVIVAL top-1 and that selected resulting board still triggers the frozen recovery warning.
 *
 * <p>The scan is descriptive. Candidate order remains the existing production SURVIVAL ranking and
 * no candidate is applied to gameplay. The benchmark reports whether a warning-clearing alternative
 * exists, how far down the survival ranking it appears, the recovery-fact gains it offers, and its
 * HEART construction cost. This establishes whether a real recovery-planning action space exists
 * before any new runtime planner is designed.</p>
 */
public final class RecoveryCandidateScanApplication {

    static final String GAMES_ENV = "TETRIS_BENCHMARK_GAMES";
    static final String MAX_PIECES_ENV = "TETRIS_BENCHMARK_MAX_PIECES";
    static final String SEED_ENV = "TETRIS_BENCHMARK_SEED";

    private static final int DEFAULT_GAMES = 20;
    private static final int DEFAULT_MAX_PIECES = 1000;
    private static final long DEFAULT_SEED = 6000L;

    private RecoveryCandidateScanApplication() {
    }

    public static void main(String[] args) {
        Configuration configuration = Configuration.fromEnvironment();
        HeadlessGameRunner runner = new HeadlessGameRunner();
        List<StateObservation> states = new ArrayList<>();
        List<CandidateTrace> candidates = new ArrayList<>();
        List<GameObservation> games = new ArrayList<>();

        for (int game = 0; game < configuration.games(); game++) {
            long seed = configuration.seed() + game;
            DecisionCapture decisionCapture = new DecisionCapture();
            List<PendingState> pendingStates = new ArrayList<>();
            GameCounters counters = new GameCounters();

            GameBenchmarkResult result = runner.runPlanningObserved(
                    seed,
                    configuration.maxPieces(),
                    new BuildShapeActionPlanningAgent(
                            ShapeTarget.HEART,
                            decisionCapture::record),
                    null,
                    turn -> observeTurn(
                            seed,
                            turn,
                            decisionCapture.current(),
                            pendingStates,
                            candidates,
                            counters));

            for (PendingState pending : pendingStates) {
                states.add(pending.withOutcome(result.reachedPieceLimit()));
            }
            games.add(new GameObservation(
                    seed,
                    result.piecesPlaced(),
                    result.reachedPieceLimit(),
                    counters.warningStates,
                    counters.survivalTop1WarningStates,
                    counters.nonTop1WarningStates,
                    counters.scannedStates,
                    counters.statesWithClearingCandidate));
        }

        printCandidates(candidates);
        printStates(states);
        printGames(games);
        printProtocol(configuration);
        printSummary(states, games);
    }

    private static void observeTurn(
            long seed,
            HeadlessGameRunner.TurnObservation turn,
            BuildShapeDecisionObservation buildDecision,
            List<PendingState> pendingStates,
            List<CandidateTrace> candidateTraces,
            GameCounters counters) {
        if (buildDecision == null || turn.snapshot().nextType().isEmpty()) {
            return;
        }

        long detectionStarted = System.nanoTime();
        RecoveryRobustnessBenchmark.Probe selectedProbe =
                RecoveryRobustnessBenchmark.probe(
                        turn.selected().resultingBoard(),
                        turn.snapshot().nextType().orElseThrow());
        long detectionProbeNanos = System.nanoTime() - detectionStarted;

        if (!RecoveryReserveValidationApplication.warning(selectedProbe)) {
            return;
        }

        counters.warningStates++;
        if (buildDecision.selectedRank() != 1) {
            counters.nonTop1WarningStates++;
            pendingStates.add(PendingState.notScanned(
                    seed,
                    turn.decision(),
                    buildDecision.selectedRank(),
                    selectedProbe,
                    ShapeTarget.HEART.progress(turn.selected().resultingBoard()),
                    detectionProbeNanos));
            return;
        }

        counters.survivalTop1WarningStates++;
        counters.scannedStates++;

        long scanStarted = System.nanoTime();
        List<RecoveryCandidateScanBenchmark.CandidateAnalysis> scan =
                RecoveryCandidateScanBenchmark.scan(turn.snapshot());
        long scanNanos = System.nanoTime() - scanStarted;

        ScanFacts facts = analyze(scan);
        if (facts.clearCandidateCount() > 0) {
            counters.statesWithClearingCandidate++;
        }

        ShapeProgress selectedProgress =
                ShapeTarget.HEART.progress(turn.selected().resultingBoard());
        int earliestClearVisualError = facts.earliestClear() == null
                ? -1
                : ShapeTarget.HEART
                        .progress(facts.earliestClear().placement().resultingBoard())
                        .visualErrorCells();

        pendingStates.add(new PendingState(
                seed,
                turn.decision(),
                buildDecision.selectedRank(),
                true,
                selectedProbe,
                selectedProgress.visualErrorCells(),
                scan.size(),
                facts.clearCandidateCount(),
                facts.earliestClear() == null
                        ? -1
                        : facts.earliestClear().survivalRank(),
                earliestClearVisualError,
                earliestClearVisualError < 0
                        ? 0
                        : earliestClearVisualError - selectedProgress.visualErrorCells(),
                facts.bestHeadroom().survivalRank(),
                facts.bestHeadroom().probe().recoveryHeadroom(),
                facts.bestHeadroom().probe().recoveryHeadroom()
                        - selectedProbe.recoveryHeadroom(),
                facts.bestPostUnknown().survivalRank(),
                facts.bestPostUnknown().probe().minPostUnknownHeadroom(),
                facts.bestPostUnknown().probe().minPostUnknownHeadroom()
                        - selectedProbe.minPostUnknownHeadroom(),
                detectionProbeNanos,
                scanNanos));

        for (RecoveryCandidateScanBenchmark.CandidateAnalysis candidate : scan) {
            ShapeProgress progress =
                    ShapeTarget.HEART.progress(candidate.placement().resultingBoard());
            candidateTraces.add(new CandidateTrace(
                    seed,
                    turn.decision(),
                    candidate.survivalRank(),
                    candidate.survivalRank() == 1,
                    RecoveryReserveValidationApplication.warning(candidate.probe()),
                    RecoveryReserveValidationApplication.warningReason(candidate.probe()),
                    progress.visualErrorCells(),
                    candidate.probe()));
        }
    }

    static ScanFacts analyze(
            List<RecoveryCandidateScanBenchmark.CandidateAnalysis> scan) {
        Objects.requireNonNull(scan, "scan");
        if (scan.isEmpty()) {
            throw new IllegalArgumentException("scan must not be empty");
        }

        List<RecoveryCandidateScanBenchmark.CandidateAnalysis> clearing =
                scan.stream()
                        .filter(candidate ->
                                !RecoveryReserveValidationApplication.warning(candidate.probe()))
                        .toList();

        RecoveryCandidateScanBenchmark.CandidateAnalysis bestHeadroom =
                scan.stream()
                        .filter(candidate -> candidate.probe().previewRecoverable())
                        .max(Comparator
                                .comparingInt((RecoveryCandidateScanBenchmark.CandidateAnalysis candidate) ->
                                        candidate.probe().recoveryHeadroom())
                                .thenComparingInt(candidate -> -candidate.survivalRank()))
                        .orElse(scan.getFirst());

        RecoveryCandidateScanBenchmark.CandidateAnalysis bestPostUnknown =
                scan.stream()
                        .filter(candidate -> candidate.probe().minPostUnknownHeadroom() >= 0)
                        .max(Comparator
                                .comparingInt((RecoveryCandidateScanBenchmark.CandidateAnalysis candidate) ->
                                        candidate.probe().minPostUnknownHeadroom())
                                .thenComparingInt(candidate -> -candidate.survivalRank()))
                        .orElse(scan.getFirst());

        return new ScanFacts(
                clearing.size(),
                clearing.isEmpty() ? null : clearing.getFirst(),
                bestHeadroom,
                bestPostUnknown);
    }

    private static void printCandidates(List<CandidateTrace> traces) {
        System.out.println(
                "recovery_candidate_candidate,seed,decision,candidate_rank,selected_top1,"
                        + "warning,warning_reason,visual_error,preview_recoverable,"
                        + "recovery_headroom,recovery_holes,unplayable_unknown_types,"
                        + "min_unknown_reachable,min_post_unknown_headroom,"
                        + "fully_recoverable_across_tetrominoes");
        for (CandidateTrace trace : traces) {
            RecoveryRobustnessBenchmark.Probe probe = trace.probe();
            System.out.printf(
                    Locale.ROOT,
                    "recovery_candidate_candidate,%d,%d,%d,%s,%s,%s,%d,%s,%d,%d,%d,%d,%d,%s%n",
                    trace.seed(),
                    trace.decision(),
                    trace.candidateRank(),
                    trace.selectedTop1(),
                    trace.warning(),
                    trace.warningReason(),
                    trace.visualError(),
                    probe.previewRecoverable(),
                    probe.recoveryHeadroom(),
                    probe.recoveryHoles(),
                    probe.unplayableUnknownTypes(),
                    probe.minUnknownReachableOutcomes(),
                    probe.minPostUnknownHeadroom(),
                    probe.fullyRecoverableAcrossTetrominoes());
        }
    }

    private static void printStates(List<StateObservation> states) {
        System.out.println(
                "recovery_candidate_state,seed,decision,game_reached_limit,selected_rank,scanned,"
                        + "candidate_count,clear_candidate_count,earliest_clear_rank,"
                        + "selected_visual_error,earliest_clear_visual_error,"
                        + "earliest_clear_visual_error_delta,selected_recovery_headroom,"
                        + "best_recovery_headroom,best_recovery_headroom_rank,headroom_gain,"
                        + "selected_min_post_unknown_headroom,best_min_post_unknown_headroom,"
                        + "best_min_post_unknown_rank,min_post_unknown_headroom_gain,"
                        + "detection_probe_ms,scan_ms");
        for (StateObservation state : states) {
            System.out.println(formatState(state));
        }
    }

    static String formatState(StateObservation state) {
        Objects.requireNonNull(state, "state");
        return String.format(
                Locale.ROOT,
                "recovery_candidate_state,%d,%d,%s,%d,%s,%d,%d,%d,%d,%d,%d,"
                        + "%d,%d,%d,%d,%d,%d,%d,%d,%.6f,%.6f",
                state.seed(),
                state.decision(),
                state.gameReachedLimit(),
                state.selectedRank(),
                state.scanned(),
                state.candidateCount(),
                state.clearCandidateCount(),
                state.earliestClearRank(),
                state.selectedVisualError(),
                state.earliestClearVisualError(),
                state.earliestClearVisualErrorDelta(),
                state.selectedProbe().recoveryHeadroom(),
                state.bestRecoveryHeadroom(),
                state.bestRecoveryHeadroomRank(),
                state.headroomGain(),
                state.selectedProbe().minPostUnknownHeadroom(),
                state.bestMinPostUnknownHeadroom(),
                state.bestMinPostUnknownRank(),
                state.minPostUnknownHeadroomGain(),
                nanosToMillis(state.detectionProbeNanos()),
                nanosToMillis(state.scanNanos()));
    }

    private static void printGames(List<GameObservation> games) {
        System.out.println(
                "recovery_candidate_game,seed,pieces_placed,reached_limit,warning_states,"
                        + "survival_top1_warning_states,non_top1_warning_states,scanned_states,"
                        + "states_with_clearing_candidate");
        for (GameObservation game : games) {
            System.out.printf(
                    Locale.ROOT,
                    "recovery_candidate_game,%d,%d,%s,%d,%d,%d,%d,%d%n",
                    game.seed(),
                    game.piecesPlaced(),
                    game.reachedLimit(),
                    game.warningStates(),
                    game.survivalTop1WarningStates(),
                    game.nonTop1WarningStates(),
                    game.scannedStates(),
                    game.statesWithClearingCandidate());
        }
    }

    private static void printProtocol(Configuration configuration) {
        System.out.printf(
                Locale.ROOT,
                "# recovery_candidate_scan_protocol seed_start=%d games=%d max_pieces=%d "
                        + "scan_trigger=production_selected_rank1_and_frozen_warning "
                        + "candidate_scope=all_action_native_reachable "
                        + "recovery_headroom_cutoff=%d recovery_holes_cutoff=%d%n",
                configuration.seed(),
                configuration.games(),
                configuration.maxPieces(),
                RecoveryReserveValidationApplication.RECOVERY_HEADROOM_CUTOFF,
                RecoveryReserveValidationApplication.RECOVERY_HOLES_CUTOFF);
    }

    private static void printSummary(
            List<StateObservation> states,
            List<GameObservation> games) {
        List<StateObservation> scanned =
                states.stream().filter(StateObservation::scanned).toList();

        long healthyScanned = scanned.stream()
                .filter(StateObservation::gameReachedLimit)
                .count();
        long failedScanned = scanned.size() - healthyScanned;
        long withClear = scanned.stream()
                .filter(state -> state.clearCandidateCount() > 0)
                .count();
        long healthyWithClear = scanned.stream()
                .filter(StateObservation::gameReachedLimit)
                .filter(state -> state.clearCandidateCount() > 0)
                .count();
        long failedWithClear = scanned.stream()
                .filter(state -> !state.gameReachedLimit())
                .filter(state -> state.clearCandidateCount() > 0)
                .count();

        double averageCandidateCount = scanned.stream()
                .mapToInt(StateObservation::candidateCount)
                .average()
                .orElse(0.0);
        double averageClearCount = scanned.stream()
                .mapToInt(StateObservation::clearCandidateCount)
                .average()
                .orElse(0.0);
        double averageEarliestClearRank = scanned.stream()
                .filter(state -> state.earliestClearRank() > 0)
                .mapToInt(StateObservation::earliestClearRank)
                .average()
                .orElse(-1.0);
        double averageHeadroomGain = scanned.stream()
                .mapToInt(StateObservation::headroomGain)
                .average()
                .orElse(0.0);
        int maxHeadroomGain = scanned.stream()
                .mapToInt(StateObservation::headroomGain)
                .max()
                .orElse(0);
        double averageScanMillis = scanned.stream()
                .mapToLong(StateObservation::scanNanos)
                .average()
                .orElse(0.0) / 1_000_000.0;
        double maxScanMillis = scanned.stream()
                .mapToLong(StateObservation::scanNanos)
                .max()
                .orElse(0L) / 1_000_000.0;

        long warningStates = games.stream()
                .mapToLong(GameObservation::warningStates)
                .sum();
        long survivalTop1Warnings = games.stream()
                .mapToLong(GameObservation::survivalTop1WarningStates)
                .sum();
        long nonTop1Warnings = games.stream()
                .mapToLong(GameObservation::nonTop1WarningStates)
                .sum();

        System.out.printf(
                Locale.ROOT,
                "# recovery_candidate_scan_result games=%d "
                        + "warning_states=%d survival_top1_warning_states=%d "
                        + "non_top1_warning_states=%d scanned_states=%d "
                        + "healthy_scanned_states=%d failed_scanned_states=%d "
                        + "states_with_clearing_candidate=%d "
                        + "healthy_states_with_clearing_candidate=%d "
                        + "failed_states_with_clearing_candidate=%d "
                        + "avg_candidate_count=%.3f avg_clear_candidate_count=%.3f "
                        + "avg_earliest_clear_rank=%.3f avg_best_headroom_gain=%.3f "
                        + "max_best_headroom_gain=%d avg_scan_ms=%.3f max_scan_ms=%.3f%n",
                games.size(),
                warningStates,
                survivalTop1Warnings,
                nonTop1Warnings,
                scanned.size(),
                healthyScanned,
                failedScanned,
                withClear,
                healthyWithClear,
                failedWithClear,
                averageCandidateCount,
                averageClearCount,
                averageEarliestClearRank,
                averageHeadroomGain,
                maxHeadroomGain,
                averageScanMillis,
                maxScanMillis);
    }

    private static double nanosToMillis(long nanos) {
        return nanos / 1_000_000.0;
    }

    record ScanFacts(
            int clearCandidateCount,
            RecoveryCandidateScanBenchmark.CandidateAnalysis earliestClear,
            RecoveryCandidateScanBenchmark.CandidateAnalysis bestHeadroom,
            RecoveryCandidateScanBenchmark.CandidateAnalysis bestPostUnknown) {

        ScanFacts {
            if (clearCandidateCount < 0) {
                throw new IllegalArgumentException(
                        "clearCandidateCount must not be negative");
            }
            Objects.requireNonNull(bestHeadroom, "bestHeadroom");
            Objects.requireNonNull(bestPostUnknown, "bestPostUnknown");
            if ((clearCandidateCount == 0) != (earliestClear == null)) {
                throw new IllegalArgumentException(
                        "earliestClear must match clearCandidateCount");
            }
        }
    }

    record StateObservation(
            long seed,
            int decision,
            boolean gameReachedLimit,
            int selectedRank,
            boolean scanned,
            RecoveryRobustnessBenchmark.Probe selectedProbe,
            int selectedVisualError,
            int candidateCount,
            int clearCandidateCount,
            int earliestClearRank,
            int earliestClearVisualError,
            int earliestClearVisualErrorDelta,
            int bestRecoveryHeadroomRank,
            int bestRecoveryHeadroom,
            int headroomGain,
            int bestMinPostUnknownRank,
            int bestMinPostUnknownHeadroom,
            int minPostUnknownHeadroomGain,
            long detectionProbeNanos,
            long scanNanos) {

        StateObservation {
            Objects.requireNonNull(selectedProbe, "selectedProbe");
            if (decision <= 0
                    || selectedRank <= 0
                    || selectedVisualError < 0
                    || candidateCount < 0
                    || clearCandidateCount < 0
                    || detectionProbeNanos < 0
                    || scanNanos < 0) {
                throw new IllegalArgumentException(
                        "invalid recovery candidate state observation");
            }
        }
    }

    private record PendingState(
            long seed,
            int decision,
            int selectedRank,
            boolean scanned,
            RecoveryRobustnessBenchmark.Probe selectedProbe,
            int selectedVisualError,
            int candidateCount,
            int clearCandidateCount,
            int earliestClearRank,
            int earliestClearVisualError,
            int earliestClearVisualErrorDelta,
            int bestRecoveryHeadroomRank,
            int bestRecoveryHeadroom,
            int headroomGain,
            int bestMinPostUnknownRank,
            int bestMinPostUnknownHeadroom,
            int minPostUnknownHeadroomGain,
            long detectionProbeNanos,
            long scanNanos) {

        private static PendingState notScanned(
                long seed,
                int decision,
                int selectedRank,
                RecoveryRobustnessBenchmark.Probe selectedProbe,
                ShapeProgress selectedProgress,
                long detectionProbeNanos) {
            return new PendingState(
                    seed,
                    decision,
                    selectedRank,
                    false,
                    selectedProbe,
                    selectedProgress.visualErrorCells(),
                    0,
                    0,
                    -1,
                    -1,
                    0,
                    -1,
                    selectedProbe.recoveryHeadroom(),
                    0,
                    -1,
                    selectedProbe.minPostUnknownHeadroom(),
                    0,
                    detectionProbeNanos,
                    0L);
        }

        private StateObservation withOutcome(boolean reachedLimit) {
            return new StateObservation(
                    seed,
                    decision,
                    reachedLimit,
                    selectedRank,
                    scanned,
                    selectedProbe,
                    selectedVisualError,
                    candidateCount,
                    clearCandidateCount,
                    earliestClearRank,
                    earliestClearVisualError,
                    earliestClearVisualErrorDelta,
                    bestRecoveryHeadroomRank,
                    bestRecoveryHeadroom,
                    headroomGain,
                    bestMinPostUnknownRank,
                    bestMinPostUnknownHeadroom,
                    minPostUnknownHeadroomGain,
                    detectionProbeNanos,
                    scanNanos);
        }
    }

    record CandidateTrace(
            long seed,
            int decision,
            int candidateRank,
            boolean selectedTop1,
            boolean warning,
            String warningReason,
            int visualError,
            RecoveryRobustnessBenchmark.Probe probe) {

        CandidateTrace {
            Objects.requireNonNull(warningReason, "warningReason");
            Objects.requireNonNull(probe, "probe");
            if (decision <= 0 || candidateRank <= 0 || visualError < 0) {
                throw new IllegalArgumentException(
                        "invalid recovery candidate trace");
            }
        }
    }

    record GameObservation(
            long seed,
            int piecesPlaced,
            boolean reachedLimit,
            int warningStates,
            int survivalTop1WarningStates,
            int nonTop1WarningStates,
            int scannedStates,
            int statesWithClearingCandidate) {

        GameObservation {
            if (piecesPlaced < 0
                    || warningStates < 0
                    || survivalTop1WarningStates < 0
                    || nonTop1WarningStates < 0
                    || scannedStates < 0
                    || statesWithClearingCandidate < 0
                    || survivalTop1WarningStates + nonTop1WarningStates != warningStates
                    || scannedStates != survivalTop1WarningStates
                    || statesWithClearingCandidate > scannedStates) {
                throw new IllegalArgumentException(
                        "invalid recovery candidate game observation");
            }
        }
    }

    private static final class DecisionCapture {
        private BuildShapeDecisionObservation current;

        void record(BuildShapeDecisionObservation decision) {
            current = Objects.requireNonNull(decision, "decision");
        }

        BuildShapeDecisionObservation current() {
            return current;
        }
    }

    private static final class GameCounters {
        private int warningStates;
        private int survivalTop1WarningStates;
        private int nonTop1WarningStates;
        private int scannedStates;
        private int statesWithClearingCandidate;
    }

    private record Configuration(
            int games,
            int maxPieces,
            long seed) {

        static Configuration fromEnvironment() {
            return new Configuration(
                    positiveInt(GAMES_ENV, DEFAULT_GAMES),
                    positiveInt(MAX_PIECES_ENV, DEFAULT_MAX_PIECES),
                    longValue(SEED_ENV, DEFAULT_SEED));
        }

        private static int positiveInt(String name, int defaultValue) {
            String value = System.getenv(name);
            if (value == null || value.isBlank()) {
                return defaultValue;
            }
            int parsed = Integer.parseInt(value);
            if (parsed <= 0) {
                throw new IllegalArgumentException(name + " must be greater than 0");
            }
            return parsed;
        }

        private static long longValue(String name, long defaultValue) {
            String value = System.getenv(name);
            return value == null || value.isBlank()
                    ? defaultValue
                    : Long.parseLong(value);
        }
    }
}
