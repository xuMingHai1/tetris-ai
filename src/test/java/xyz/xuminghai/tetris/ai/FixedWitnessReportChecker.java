/*
 * Copyright (C) 2024-2026 xuMingHai
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/**
 * Independent structural/hash/conservation checks on recorded facts only. Does not simulate,
 * enumerate, score or reconstruct game rules, and does not authenticate a build or old run.
 */
final class FixedWitnessReportChecker {
    private FixedWitnessReportChecker() { }

    static void checkPrefix(List<FixedWitnessReplay.Row> rows, List<FixedWitnessInput.Step> steps,
                            String hash) {
        require(!rows.isEmpty() && rows.size() <= steps.size(), "row count");
        FixedWitnessReplay.Row previous = null;
        for (int index = 0; index < rows.size(); index++) {
            var row = rows.get(index);
            var step = steps.get(index);
            require(row.pathId().equals(step.pathId()) && row.step() == step.step()
                    && row.sourceLine() == step.sourceLine() && row.piece().equals(step.piece().name())
                    && row.actions().equals(step.plan().actions().stream().map(Enum::name).toList())
                    && row.inputHash().equals(hash), "input identity/order");
            checkOccupancy(row.before());
            checkOccupancy(row.after());
            require(row.clearedLines() >= 0 && row.clearedLines() <= 4, "clearedLines range");
            boolean first = step.step() == 1;
            if (first) {
                require(row.before().occupied() == 0, "path must start empty");
            } else {
                require(previous != null && previous.pathId().equals(row.pathId())
                        && previous.step() + 1 == row.step()
                        && previous.after().equals(row.before()), "board chain");
            }
            int priorClears = first ? 0 : previous.cumulativeClears();
            require(row.cumulativeClears() == priorClears + row.clearedLines(), "cumulative clears");
            require(row.after().occupied() == row.before().occupied() + 4 - 10 * row.clearedLines()
                    && row.after().occupied() == 4 * row.step() - 10 * row.cumulativeClears(),
                    "occupancy conservation");
            var holes = row.holes();
            require(holes.totalHoles() <= 200 && holes.requiredHoles() <= 200
                    && holes.forbiddenHoles() <= 200 && holes.supportAllowedHoles() <= 200
                    && holes.outsideTargetHoles() <= 200
                    && holes.requiredHoles() + holes.forbiddenHoles() + holes.supportAllowedHoles()
                    + holes.outsideTargetHoles() == holes.totalHoles(), "hole role partition");
            require(row.maxHeight() >= 0 && row.maxHeight() <= 20
                    && row.headroom() == 20 - row.maxHeight(), "height/headroom");
            require(row.progress().requiredCells() == 32 && row.progress().forbiddenCells() == 16,
                    "fixed HEART dimensions");
            require(row.progress().supportOccupiedCells() <= 16
                    && row.progress().matchedRequiredCells() + row.progress().forbiddenOccupiedCells()
                    + row.progress().supportOccupiedCells() <= row.after().occupied(), "occupancy roles");
            previous = row;
        }
    }

    static void checkTerminal(FixedWitnessReplay.Row row, Expected expected) {
        require(expected != null, "missing terminal expectation");
        var actual = new Expected(row.progress().matchedRequiredCells(),
                row.progress().forbiddenOccupiedCells(), row.holes().totalHoles(),
                row.holes().requiredHoles(), row.holes().forbiddenHoles(),
                row.holes().supportAllowedHoles(), row.holes().outsideTargetHoles());
        require(actual.equals(expected), "terminal facts disagree with fixed evidence for path " + row.pathId());
    }

    private static void checkOccupancy(FixedWitnessReplay.Occupancy value) {
        require(value.bits().matches("[01]{200}"), "board must be row-major 20x10");
        require(value.occupied() == value.bits().chars().filter(c -> c == '1').count(), "occupied count");
        require(Objects.equals(value.sha256(), FixedWitnessInput.sha256(
                value.bits().getBytes(StandardCharsets.UTF_8))), "board hash");
    }

    private static void require(boolean condition, String reason) {
        if (!condition) throw new Mismatch(reason);
    }

    /** Explicit original-log terminal fields; missing fields cannot silently become zero. */
    record Expected(int required, int forbidden, int rawHoles, int requiredHoles,
                    int forbiddenHoles, int supportHoles, int outsideHoles) {
        Expected {
            if (required < 0 || required > 32 || forbidden < 0 || forbidden > 16
                    || rawHoles < 0 || rawHoles > 200 || requiredHoles < 0 || requiredHoles > 200
                    || forbiddenHoles < 0 || forbiddenHoles > 200
                    || supportHoles < 0 || supportHoles > 200 || outsideHoles < 0 || outsideHoles > 200
                    || rawHoles != requiredHoles + forbiddenHoles + supportHoles + outsideHoles) {
                throw new IllegalArgumentException("invalid expected terminal facts");
            }
        }
    }

    static final class Mismatch extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        Mismatch(String message) {
            super(message);
        }
    }
}
