/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Benchmark-only scan of recovery robustness across the existing action-native survival ranking.
 *
 * <p>This class does not define a recovery policy or a new ranking. It preserves the exact
 * {@link ActionPlanCandidates} order already used by production SURVIVAL and attaches the existing
 * {@link RecoveryRobustnessBenchmark} probe to every reachable candidate. Benchmark applications
 * can therefore ask whether a warned SURVIVAL top-1 state has any alternative reachable action
 * with better recovery facts without duplicating movement rules or heuristic weights.</p>
 */
public final class RecoveryCandidateScanBenchmark {

    private RecoveryCandidateScanBenchmark() {
    }

    /**
     * Scans every action-native reachable candidate in production SURVIVAL order.
     *
     * @throws IllegalArgumentException when the runtime preview piece is unavailable
     */
    public static List<CandidateAnalysis> scan(GameSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (snapshot.nextType().isEmpty()) {
            throw new IllegalArgumentException(
                    "recovery candidate scan requires a known preview piece");
        }

        List<ActionPlanCandidates.PlannedCandidate> ranked =
                ActionPlanCandidates.ranked(snapshot);
        if (ranked.isEmpty()) {
            return List.of();
        }

        var previewType = snapshot.nextType().orElseThrow();
        List<CandidateAnalysis> analyses = new ArrayList<>(ranked.size());
        for (int index = 0; index < ranked.size(); index++) {
            ActionPlanCandidates.PlannedCandidate candidate = ranked.get(index);
            analyses.add(new CandidateAnalysis(
                    index + 1,
                    candidate.plan(),
                    candidate.placement(),
                    RecoveryRobustnessBenchmark.probe(
                            candidate.placement().resultingBoard(),
                            previewType)));
        }
        return List.copyOf(analyses);
    }

    /**
     * One existing SURVIVAL-ranked reachable action plus its recovery evidence.
     */
    public record CandidateAnalysis(
            int survivalRank,
            AiPlan plan,
            PlacementCandidate placement,
            RecoveryRobustnessBenchmark.Probe probe) {

        public CandidateAnalysis {
            if (survivalRank <= 0) {
                throw new IllegalArgumentException("survivalRank must be positive");
            }
            Objects.requireNonNull(plan, "plan");
            Objects.requireNonNull(placement, "placement");
            Objects.requireNonNull(probe, "probe");
        }
    }
}
