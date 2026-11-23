/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai.benchmark;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SurvivalComparisonApplicationTest {

    @Test
    void exactTenPercentBoundaryPassesButNeverClaimsExecutionAcceptance() {
        var result = compare(csv("heuristic", 100, 20), csv("lookahead", 110, 20));
        assertEquals(SurvivalComparisonApplication.Gate.PASS, result.gate());
        assertTrue(result.report().contains("final_result=NOT_EVALUATED"));
        assertTrue(result.report().contains("execution_verification=REQUIRED"));
        assertEquals(SurvivalComparisonApplication.Gate.FAIL,
                compare(csv("heuristic", 100, 20), csv("lookahead", 109, 20)).gate());
    }

    @Test
    void lineImprovementCanPassWithoutPieceImprovementButOtherTotalCannotFall() {
        assertEquals(SurvivalComparisonApplication.Gate.PASS,
                compare(csv("heuristic", 100, 20), csv("lookahead", 100, 22)).gate());
        assertEquals(SurvivalComparisonApplication.Gate.FAIL,
                compare(csv("heuristic", 100, 20), csv("lookahead", 99, 22)).gate());
        assertEquals(SurvivalComparisonApplication.Gate.FAIL,
                compare(csv("heuristic", 100, 20), csv("lookahead", 110, 19)).gate());
    }

    @Test
    void individualPieceRegressionGateAllowsExactlyTenPercent() {
        List<String> candidate = csv("lookahead", 120, 20);
        replace(candidate, 1, 3, "90");
        replace(candidate, 1, 5, "90");
        assertEquals(SurvivalComparisonApplication.Gate.PASS,
                compare(csv("heuristic", 100, 20), candidate).gate());
        replace(candidate, 1, 3, "89");
        replace(candidate, 1, 5, "89");
        var result = compare(csv("heuristic", 100, 20), candidate);
        assertEquals(SurvivalComparisonApplication.Gate.FAIL, result.gate());
        assertTrue(result.report().contains("per_seed_protected=false"));
    }

    @Test
    void baselineAtHorizonCannotBecomeAnEarlyFinishEvenWithinTenPercent() {
        List<String> baseline = csv("heuristic", 100, 20);
        List<String> candidate = csv("lookahead", 120, 20);
        baseline.set(1, row("heuristic", 1000, 500, 20));
        candidate.set(1, row("lookahead", 1000, 499, 20));
        var result = compare(baseline, candidate);
        assertEquals(SurvivalComparisonApplication.Gate.FAIL, result.gate());
        assertTrue(result.report().contains("horizon_protected=false"));
        assertTrue(result.report().contains("per_seed_protected=true"));
    }

    @Test
    void bothSaturatedIsInconclusiveEvenWithLargeLineGain() {
        assertEquals(SurvivalComparisonApplication.Gate.INCONCLUSIVE,
                compare(csv("heuristic", 500, 20), csv("lookahead", 500, 40)).gate());
    }

    @Test
    void zeroBaselineIsNotRelativeImprovementAndTiesCannotPass() {
        assertEquals(SurvivalComparisonApplication.Gate.FAIL,
                compare(csv("heuristic", 100, 0), csv("lookahead", 100, 1)).gate());
        var result = compare(csv("heuristic", 100, 20), csv("lookahead", 100, 20));
        assertEquals(SurvivalComparisonApplication.Gate.FAIL, result.gate());
        assertTrue(result.report().contains("improved=0 regressed=0 tied=20"));
    }

    @Test
    void lexicographicWinsMustOutnumberLossesEvenWhenAggregateGainsPass() {
        List<String> baseline = csv("heuristic", 100, 20);
        List<String> candidate = csv("lookahead", 90, 20);
        for (int index = 1; index <= 10; index++) {
            candidate.set(index, row("lookahead", 999 + index, 150, 20));
        }
        var result = compare(baseline, candidate);
        assertEquals(SurvivalComparisonApplication.Gate.FAIL, result.gate());
        assertTrue(result.report().contains("totals_preserved=true ten_percent=true"));
        assertTrue(result.report().contains("improved=10 regressed=10 tied=0"));
        assertTrue(result.report().contains("1000,100,150,20,20,improved"));
    }

    @Test
    void rejectsMissingExtraDuplicatedReorderedSeedsAndMixedArms() {
        List<String> bad = csv("lookahead", 110, 20);
        bad.removeLast();
        invalid(bad);
        bad = csv("lookahead", 110, 20);
        bad.add(bad.getLast());
        invalid(bad);
        bad = csv("lookahead", 110, 20);
        bad.set(2, bad.get(1));
        invalid(bad);
        bad = csv("lookahead", 110, 20);
        String first = bad.get(1);
        bad.set(1, bad.get(2));
        bad.set(2, first);
        invalid(bad);
        bad = csv("lookahead", 110, 20);
        replace(bad, 1, 0, "jev");
        invalid(bad);
    }

    @Test
    void rejectsSchemaBlankRowsAndAnyBadNumericOrBooleanField() {
        List<String> bad = csv("lookahead", 110, 20);
        bad.set(0, SurvivalComparisonApplication.HEADER + ",extra");
        invalid(bad);
        bad = csv("lookahead", 110, 20);
        bad.set(1, "");
        invalid(bad);
        for (int column = 1; column < 19; column++) {
            for (String value : List.of("", "-1", "NaN", "Infinity", " 1", "1e9999")) {
                bad = csv("lookahead", 110, 20);
                replace(bad, 1, column, value);
                invalid(bad);
            }
        }
        for (String value : List.of("TRUE", "1", "", "false")) {
            bad = csv("lookahead", 500, 20);
            replace(bad, 1, 19, value);
            invalid(bad);
        }
        bad = csv("lookahead", 110, 20);
        replace(bad, 1, 3, "110.0");
        invalid(bad);
        replace(bad, 1, 3, "99999999999999999999999999");
        invalid(bad);
    }

    @Test
    void rejectsLimitCountLineFailureAndFallbackViolationsBeforeSaturation() {
        for (int[] change : new int[][]{{2, 501}, {3, 501}, {4, 2001}, {5, 499}, {6, 1}, {7, 1}}) {
            List<String> bad = csv("lookahead", 500, 20);
            replace(bad, 1, change[0], Integer.toString(change[1]));
            invalid(bad);
        }
        List<String> bad = csv("lookahead", 110, 20);
        replace(bad, 1, 7, "2");
        var failure = assertThrows(IllegalArgumentException.class,
                () -> compare(csv("heuristic", 100, 20), bad));
        assertTrue(failure.getMessage().contains("lookahead seed=1000"));
        assertTrue(failure.getMessage().contains("fallback_decisions=2"));
        assertFalse(failure.getMessage().contains("PASS"));
    }

    @Test
    void listsEveryFailureAndFallbackAcrossBothArms() {
        List<String> baseline = csv("heuristic", 100, 20);
        List<String> candidate = csv("lookahead", 110, 20);
        replace(baseline, 2, 6, "1");
        replace(candidate, 1, 7, "2");
        replace(candidate, 20, 6, "3");
        String error = assertThrows(IllegalArgumentException.class,
                () -> compare(baseline, candidate)).getMessage();
        assertTrue(error.contains("heuristic seed=1001: primary_failures=1 fallback_decisions=0"));
        assertTrue(error.contains("lookahead seed=1000: primary_failures=0 fallback_decisions=2"));
        assertTrue(error.contains("lookahead seed=1019: primary_failures=3 fallback_decisions=0"));
    }

    private static SurvivalComparisonApplication.Comparison compare(List<String> b, List<String> c) {
        return SurvivalComparisonApplication.compare(b, c);
    }

    private static void invalid(List<String> candidate) {
        assertThrows(IllegalArgumentException.class, () -> compare(csv("heuristic", 100, 20), candidate));
    }

    private static List<String> csv(String arm, int pieces, int lines) {
        List<String> result = new ArrayList<>();
        result.add(SurvivalComparisonApplication.HEADER);
        for (long seed = 1000; seed < 1020; seed++) result.add(row(arm, seed, pieces, lines));
        return result;
    }

    private static String row(String arm, long seed, int pieces, int lines) {
        return arm + "," + seed + ",500," + pieces + "," + lines + "," + pieces
                + ",0,0,1.000,2.000,0,0.000,0,0,0.000,0,0,0.000,0," + (pieces == 500);
    }

    private static void replace(List<String> csv, int row, int column, String value) {
        String[] fields = csv.get(row).split(",", -1);
        fields[column] = value;
        csv.set(row, String.join(",", fields));
    }
}
