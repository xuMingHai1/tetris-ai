/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai.benchmark;

import xyz.xuminghai.tetris.ai.ActionReachabilityBenchmark;
import xyz.xuminghai.tetris.ai.GameSnapshot;
import xyz.xuminghai.tetris.core.BoardPosition;
import xyz.xuminghai.tetris.core.TetrominoType;

import java.util.List;
import java.util.Locale;

/**
 * Small deterministic reachability benchmark for comparing placement and action-native search.
 */
public final class ReachabilityBenchmarkApplication {

    private ReachabilityBenchmarkApplication() {
    }

    public static void main(String[] args) {
        for (Scenario scenario : scenarios()) {
            ActionReachabilityBenchmark.Result result =
                    ActionReachabilityBenchmark.measure(scenario.snapshot());
            System.out.printf(
                    Locale.ROOT,
                    "reachability,%s,%d,%d,%d,%d,%.3f,%.3f%n",
                    scenario.name(),
                    result.placementLandings(),
                    result.actionLandings(),
                    result.actionOnlyLandings(),
                    result.visitedActionStates(),
                    result.placementMillis(),
                    result.actionMillis());
        }
        System.out.println("# columns scenario,placement_landings,action_landings,action_only_landings,"
                + "visited_action_states,placement_ms,action_ms");
    }

    private static List<Scenario> scenarios() {
        boolean[][] empty = new boolean[20][10];

        boolean[][] tuck = new boolean[8][8];
        tuck[3][0] = true;
        tuck[3][1] = true;
        tuck[3][2] = true;
        tuck[3][4] = true;
        tuck[4][0] = true;
        tuck[4][4] = true;
        tuck[5][0] = true;
        tuck[5][4] = true;
        tuck[6][0] = true;
        tuck[6][4] = true;
        tuck[7][0] = true;
        tuck[7][1] = true;
        tuck[7][2] = true;
        tuck[7][3] = true;
        tuck[7][4] = true;

        return List.of(
                new Scenario("empty-t", new GameSnapshot(
                        20, 10, empty, TetrominoType.T,
                        List.of(
                                new BoardPosition(0, 4),
                                new BoardPosition(1, 3),
                                new BoardPosition(1, 4),
                                new BoardPosition(1, 5)))),
                new Scenario("tuck-t", new GameSnapshot(
                        8, 8, tuck, TetrominoType.T,
                        List.of(
                                new BoardPosition(0, 3),
                                new BoardPosition(1, 2),
                                new BoardPosition(1, 3),
                                new BoardPosition(1, 4)))));
    }

    private record Scenario(String name, GameSnapshot snapshot) {
    }
}
