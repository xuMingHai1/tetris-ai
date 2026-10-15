/*
 * Copyright (C) 2024-2026 xuMingHai
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Test-classpath-only, stateless HEART terminal-window experiment accepted by #116.
 * Only exact terminal Q may bypass the ordinary guard. A setup never receives that exception.
 * This class has no seed, generator, environment, history or runtime factory dependency.
 */
public final class GoalWindowCandidate implements AiPlanningAgent {
    private final Consumer<Observation> observer;

    public GoalWindowCandidate() {
        this(ignored -> { });
    }

    /** The observer records facts only; its output is never read by the selector. */
    public GoalWindowCandidate(Consumer<Observation> observer) {
        this.observer = Objects.requireNonNull(observer, "observer");
    }

    @Override
    public AiPlan plan(GameSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (snapshot.rows() != 20 || snapshot.cols() != 10) {
            throw new IllegalArgumentException("GoalWindow is frozen to the 20x10 HEART protocol");
        }
        var current = ActionPlanCandidates.ranked(snapshot);
        int enumerations = 1;
        int replays = 0;
        List<Window> windows = new ArrayList<>();
        List<Setup> setups = new ArrayList<>();
        if (!current.isEmpty()) {
            var baseline = current.getFirst().placement();
            var risk = ObjectiveRiskController.adaptive().decide(baseline);
            ActionPlanCandidates.PlannedCandidate immediate = null;
            for (int i = 0; i < current.size(); i++) {
                var candidate = current.get(i);
                var q = terminal(candidate.placement().resultingBoard());
                if (q.accepted()) {
                    verify(snapshot, candidate);
                    replays++;
                    windows.add(new Window(i + 1, 0, q,
                            guard(i + 1, baseline, candidate.placement(), risk)));
                    if (immediate == null) immediate = candidate;
                }
            }
            if (immediate != null) {
                observer.accept(new Observation(Choice.IMMEDIATE_Q, enumerations, replays,
                        List.copyOf(windows), List.of()));
                return immediate.plan();
            }

            ActionPlanCandidates.PlannedCandidate selectedSetup = null;
            if (snapshot.nextType().isPresent()) {
                for (int i = 0; i < Math.min(5, current.size()); i++) {
                    var setup = current.get(i);
                    var admission = guard(i + 1, baseline, setup.placement(), risk);
                    // Rank one is the production baseline itself, including in DANGER.
                    boolean allowed = i == 0 || admission.ordinaryAllowed();
                    setups.add(new Setup(i + 1, allowed, admission,
                            terminal(setup.placement().resultingBoard())));
                    if (!allowed) continue;
                    verify(snapshot, setup);
                    replays++;
                    var previewSnapshot = BoardSimulator.snapshotForSpawnedPiece(
                            setup.placement().resultingBoard(), snapshot.nextType().orElseThrow());
                    var preview = ActionPlanCandidates.ranked(previewSnapshot);
                    enumerations++;
                    var previewRisk = preview.isEmpty() ? null
                            : ObjectiveRiskController.adaptive().decide(preview.getFirst().placement());
                    for (int j = 0; j < preview.size(); j++) {
                        var finish = preview.get(j);
                        var q = terminal(finish.placement().resultingBoard());
                        if (!q.accepted()) continue;
                        verify(previewSnapshot, finish);
                        replays++;
                        windows.add(new Window(i + 1, j + 1, q,
                                guard(j + 1, preview.getFirst().placement(),
                                        finish.placement(), previewRisk)));
                        // Enumeration order implements the frozen lexicographic rank tie-break.
                        if (selectedSetup == null) selectedSetup = setup;
                    }
                }
            }
            if (selectedSetup != null) {
                observer.accept(new Observation(Choice.PREVIEW_SETUP, enumerations, replays,
                        List.copyOf(windows), List.copyOf(setups)));
                return selectedSetup.plan();
            }
        }
        // Reuse the private production selector through its public contract, including no-landing.
        AiPlan plan = new BuildShapeActionPlanningAgent().plan(snapshot);
        enumerations++;
        observer.accept(new Observation(Choice.BASELINE_CHOICE, enumerations, replays,
                List.copyOf(windows), List.copyOf(setups)));
        return plan;
    }

    static Guard guard(int rank, PlacementCandidate baseline, PlacementCandidate candidate,
                       ObjectiveRiskController.Decision risk) {
        var assessment = risk.profile().budget().assess(baseline, candidate);
        return new Guard(rank > 5, risk.level() == ObjectiveRiskController.RiskLevel.DANGER,
                assessment.allowed(), assessment.holesDelta(), assessment.aggregateHeightDelta(),
                assessment.bumpinessDelta(), risk.level().name(), risk.profile().name());
    }

    /** Q is narrower than actual clean. Never use this predicate to count game completion. */
    public static Terminal terminal(boolean[][] board) {
        if (board.length != 20 || Arrays.stream(board).anyMatch(row -> row.length != 10)) {
            throw new IllegalArgumentException("Q requires a 20x10 board");
        }
        var progress = ShapeTarget.HEART.progress(board);
        var metrics = BoardOccupancyMetrics.analyzeWithHoleCells(board);
        int required = 0;
        int support = 0;
        int outside = 0;
        for (var hole : metrics.holeCells()) {
            switch (ShapeTarget.HEART.roleAtBoardCell(20, 10, hole.row(), hole.col())) {
                case REQUIRED -> required++;
                case SUPPORT_ALLOWED -> support++;
                case OUTSIDE_TARGET -> outside++;
                case FORBIDDEN -> { /* Keep raw holes; only this role is exempt from Q. */ }
            }
        }
        return new Terminal(progress.cleanCompletion(), progress.matchedRequiredCells(),
                progress.forbiddenOccupiedCells(), metrics.maxColumnHeight(), metrics.holes(),
                required, support, outside);
    }

    private static void verify(GameSnapshot snapshot, ActionPlanCandidates.PlannedCandidate candidate) {
        var replayed = ActionPlanSimulator.requireTerminalPlacement(snapshot, candidate.plan());
        if (!Arrays.deepEquals(replayed.resultingBoard(), candidate.placement().resultingBoard())
                || replayed.clearedLines() != candidate.placement().clearedLines()) {
            throw new IllegalStateException("enumerated terminal disagrees with production replay");
        }
    }

    public enum Choice { IMMEDIATE_Q, PREVIEW_SETUP, BASELINE_CHOICE }

    public record Terminal(boolean clean, int matchedRequired, int forbiddenOccupied,
                           int maxHeight, int rawHoles, int requiredHoles,
                           int supportHoles, int outsideHoles) {
        public boolean accepted() {
            return clean && maxHeight <= ShapeTarget.HEART.height()
                    && requiredHoles == 0 && supportHoles == 0 && outsideHoles == 0;
        }
    }

    public record Guard(boolean outsideTopFive, boolean danger, boolean budgetAllowed,
                        int holesDelta, int aggregateHeightDelta, int bumpinessDelta,
                        String risk, String profile) {
        public boolean ordinaryAllowed() {
            return !outsideTopFive && !danger && budgetAllowed;
        }
    }

    public record Window(int currentRank, int previewRank, Terminal terminal, Guard originalGuard) { }
    public record Setup(int currentRank, boolean allowed, Guard originalGuard, Terminal resultingFacts) { }
    public record Observation(Choice choice, int enumerations, int replays,
                              List<Window> windows, List<Setup> setups) { }
}
