/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai.benchmark;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Offline arithmetic and strict game-CSV validation for the accepted survival V1 design.
 *
 * <p>This entry point never runs a game. CSV cannot establish process exit, provenance or wall-clock
 * compliance, so even a passing CSV gate is explicitly pending separate execution verification.</p>
 */
public final class SurvivalComparisonApplication {

    static final String HEADER = "agent,seed,piece_limit,pieces_placed,lines_cleared,decisions,"
            + "primary_failures,fallback_decisions,avg_decision_ms,max_decision_ms,"
            + "final_aggregate_height,avg_aggregate_height,max_aggregate_height,"
            + "final_holes,avg_holes,max_holes,final_bumpiness,avg_bumpiness,max_bumpiness,"
            + "reached_piece_limit";
    private static final int GAMES = 20;
    private static final int LIMIT = 500;
    private static final long FIRST_SEED = 1000;

    private SurvivalComparisonApplication() {
    }

    /** Accepts two closed, extracted game CSV files, baseline first and lookahead second. */
    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            throw new IllegalArgumentException("Expected heuristic.csv lookahead.csv; no games are run");
        }
        try {
            Comparison comparison = compare(
                    Files.readAllLines(Path.of(args[0])), Files.readAllLines(Path.of(args[1])));
            System.out.print(comparison.report());
        } catch (IllegalArgumentException invalid) {
            System.out.println("csv_gate=INVALID");
            System.out.println("final_result=NOT_EVALUATED");
            throw invalid;
        }
    }

    static Comparison compare(List<String> baselineCsv, List<String> candidateCsv) {
        List<Game> baseline = parse(baselineCsv, "heuristic");
        List<Game> candidate = parse(candidateCsv, "lookahead");
        List<String> failures = new ArrayList<>();
        recordFailures(baseline, "heuristic", failures);
        recordFailures(candidate, "lookahead", failures);
        require(failures.isEmpty(), String.join("; ", failures));
        long baselinePieces = 0;
        long candidatePieces = 0;
        long baselineLines = 0;
        long candidateLines = 0;
        int improved = 0;
        int regressed = 0;
        boolean horizonProtected = true;
        boolean perSeedProtected = true;
        boolean bothSaturated = true;
        StringBuilder pairs = new StringBuilder(
                "seed,baseline_pieces,candidate_pieces,baseline_lines,candidate_lines,outcome\n");
        for (int index = 0; index < GAMES; index++) {
            Game b = baseline.get(index);
            Game c = candidate.get(index);
            int order = Long.compare(c.pieces(), b.pieces());
            if (order == 0) order = Long.compare(c.lines(), b.lines());
            if (order > 0) improved++;
            if (order < 0) regressed++;
            horizonProtected &= b.pieces() != LIMIT || c.pieces() == LIMIT;
            perSeedProtected &= 10 * c.pieces() >= 9 * b.pieces();
            bothSaturated &= b.pieces() == LIMIT && c.pieces() == LIMIT;
            baselinePieces += b.pieces();
            candidatePieces += c.pieces();
            baselineLines += b.lines();
            candidateLines += c.lines();
            pairs.append(b.seed()).append(',').append(b.pieces()).append(',').append(c.pieces())
                    .append(',').append(b.lines()).append(',').append(c.lines()).append(',')
                    .append(order > 0 ? "improved" : order < 0 ? "regressed" : "tied").append('\n');
        }
        boolean totalsPreserved = candidatePieces >= baselinePieces && candidateLines >= baselineLines;
        boolean tenPercent = (baselinePieces > 0 && 10 * candidatePieces >= 11 * baselinePieces)
                || (baselineLines > 0 && 10 * candidateLines >= 11 * baselineLines);
        boolean winsExceedLosses = improved > regressed;
        Gate gate = bothSaturated ? Gate.INCONCLUSIVE
                : totalsPreserved && tenPercent && winsExceedLosses && horizonProtected && perSeedProtected
                        ? Gate.PASS : Gate.FAIL;
        String report = pairs + "csv_gate=" + gate + '\n'
                + "baseline_pieces=" + baselinePieces + " candidate_pieces=" + candidatePieces + '\n'
                + "baseline_lines=" + baselineLines + " candidate_lines=" + candidateLines + '\n'
                + "improved=" + improved + " regressed=" + regressed + " tied=" + (GAMES - improved - regressed) + '\n'
                + "totals_preserved=" + totalsPreserved + " ten_percent=" + tenPercent + '\n'
                + "wins_exceed_losses=" + winsExceedLosses + " horizon_protected=" + horizonProtected
                + " per_seed_protected=" + perSeedProtected + " both_saturated=" + bothSaturated + '\n'
                + "final_result=NOT_EVALUATED\n"
                + "execution_verification=REQUIRED (exit codes, source/build identity, raw logs, total wall clock)\n";
        return new Comparison(gate, report);
    }

    private static List<Game> parse(List<String> csv, String arm) {
        require(csv.size() == GAMES + 1, arm + ": expected header and exactly 20 games");
        require(HEADER.equals(csv.getFirst()), arm + ": unexpected CSV header/schema");
        List<Game> games = new ArrayList<>(GAMES);
        for (int index = 0; index < GAMES; index++) {
            String context = arm + " seed=" + (FIRST_SEED + index);
            String[] fields = csv.get(index + 1).split(",", -1);
            require(fields.length == 20, context + ": expected exactly 20 fields");
            require(arm.equals(fields[0]), context + ": wrong agent");
            long[] integers = new long[20];
            for (int column : new int[]{1, 2, 3, 4, 5, 6, 7, 10, 12, 13, 15, 16, 18}) {
                integers[column] = nonNegativeInteger(fields[column], context, column);
            }
            for (int column : new int[]{8, 9, 11, 14, 17}) {
                require(fields[column].matches("[0-9]+(?:\\.[0-9]+)?"), context + ": invalid decimal column " + column);
                BigDecimal decimal = new BigDecimal(fields[column]);
                require(Double.isFinite(decimal.doubleValue()), context + ": non-finite column " + column);
            }
            require(integers[1] == FIRST_SEED + index, context + ": seed missing, duplicated or out of order");
            require(integers[2] == LIMIT, context + ": piece_limit must be 500");
            require(integers[3] <= LIMIT, context + ": pieces exceed limit");
            require(integers[5] == integers[3], context + ": decisions differ from placed pieces");
            require(integers[4] <= 4 * integers[3], context + ": lines exceed four per piece");
            require(fields[19].equals("true") || fields[19].equals("false"), context + ": invalid reached flag");
            require(Boolean.parseBoolean(fields[19]) == (integers[3] == LIMIT), context + ": inconsistent reached flag");
            games.add(new Game(integers[1], integers[3], integers[4], integers[6], integers[7]));
        }
        return List.copyOf(games);
    }

    private static void recordFailures(List<Game> games, String arm, List<String> failures) {
        for (Game game : games) {
            if (game.primaryFailures() != 0 || game.fallbackDecisions() != 0) {
                failures.add(arm + " seed=" + game.seed() + ": primary_failures=" + game.primaryFailures()
                        + " fallback_decisions=" + game.fallbackDecisions());
            }
        }
    }

    private static long nonNegativeInteger(String value, String context, int column) {
        require(value.matches("[0-9]+"), context + ": invalid integer column " + column);
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException overflow) {
            throw new IllegalArgumentException(context + ": integer overflow column " + column, overflow);
        }
    }

    private static void require(boolean valid, String message) {
        if (!valid) throw new IllegalArgumentException(message);
    }

    enum Gate { PASS, FAIL, INCONCLUSIVE }

    record Comparison(Gate gate, String report) { }

    private record Game(long seed, long pieces, long lines, long primaryFailures, long fallbackDecisions) { }
}
