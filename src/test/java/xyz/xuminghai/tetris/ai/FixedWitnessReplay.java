/*
 * Copyright (C) 2024-2026 xuMingHai
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import tools.jackson.databind.json.JsonMapper;
import xyz.xuminghai.tetris.core.BoardRules;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Test-only fixed-action replay, with no candidate enumeration or executable historical entry point.
 * A future authenticated supervisor must supply the registered bytes and terminal expectations.
 * COMPLETE here describes only this local chain, never build provenance or diagnostic acceptance.
 */
final class FixedWitnessReplay {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private FixedWitnessReplay() { }

    /**
     * Creates an exclusive journal. Each attempt is forced before the sole terminal replay and
     * each resulting row is forced afterwards. A killed process or failed write leaves a prefix,
     * not a manufactured COMPLETE. No caller may retry a registered historical session.
     */
    static Result run(byte[] bytes, String inputHash, List<FixedWitnessInput.PathSpec> paths,
                      Map<String, FixedWitnessReportChecker.Expected> expected, Path journal)
            throws IOException {
        paths = List.copyOf(paths);
        expected = Map.copyOf(expected);
        List<Row> rows = new ArrayList<>();
        int attempted = 0;
        try (FileChannel channel = FileChannel.open(journal,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            append(channel, Map.of("event", "START", "schema", 1,
                    "inputHash", inputHash, "paths", paths.stream()
                            .map(path -> Map.of("id", path.id(), "placements", path.placements())).toList(),
                    "expected", FixedWitnessReportCodec.expected(expected)));
            Status status = Status.INVALID;
            String reason = "";
            try {
                var steps = FixedWitnessInput.read(bytes, inputHash, paths);
                if (!expected.keySet().equals(paths.stream()
                        .map(FixedWitnessInput.PathSpec::id).collect(java.util.stream.Collectors.toSet()))) {
                    throw new IllegalArgumentException("terminal expectations must cover exactly all paths");
                }
                boolean[][] board = new boolean[20][10];
                int cumulativeClears = 0;
                for (int index = 0; index < steps.size(); index++) {
                    var step = steps.get(index);
                    if (step.step() == 1) {
                        board = new boolean[20][10];
                        cumulativeClears = 0;
                    }
                    // A snapshot can represent colliding spawn cells; reject that state explicitly
                    // using shared rules before a HARD_DROP-only plan could escape it.
                    var snapshot = BoardSimulator.snapshotForSpawnedPiece(board, step.piece());
                    requireSpawn(snapshot);
                    append(channel, Map.of("event", "ATTEMPT", "attempt", ++attempted,
                            "pathId", step.pathId(), "step", step.step()));
                    var placement = ActionPlanSimulator.requireTerminalPlacement(snapshot, step.plan());
                    Row row = describe(board, placement, step, inputHash, cumulativeClears);
                    append(channel, Map.of("event", "STEP", "row", FixedWitnessReportCodec.row(row)));
                    rows.add(row);
                    board = placement.resultingBoard();
                    cumulativeClears = row.cumulativeClears();
                    // Inspect only saved facts. This checker never executes a second action path.
                    FixedWitnessReportChecker.checkPrefix(rows, steps, inputHash);
                    if (index + 1 == steps.size()
                            || !steps.get(index + 1).pathId().equals(step.pathId())) {
                        FixedWitnessReportChecker.checkTerminal(row, expected.get(step.pathId()));
                    }
                }
                status = Status.COMPLETE;
            } catch (FixedWitnessReportChecker.Mismatch exception) {
                status = Status.EVIDENCE_MISMATCH;
                reason = exception.getMessage();
            } catch (IllegalArgumentException | IllegalStateException exception) {
                reason = exception.getMessage();
            }
            Result result = new Result(status, attempted, rows, reason);
            append(channel, Map.of("event", "END", "status", status.name(),
                    "attempted", attempted, "completed", rows.size(), "reason", reason));
            return result;
        }
    }

    /** Artificial board seam for gravity, spawn, collision and row-clear unit tests only. */
    static Row replayOne(boolean[][] board, FixedWitnessInput.Step step,
                         String inputHash, int cumulativeClears) {
        var snapshot = BoardSimulator.snapshotForSpawnedPiece(board, step.piece());
        requireSpawn(snapshot);
        return describe(board, ActionPlanSimulator.requireTerminalPlacement(snapshot, step.plan()),
                step, inputHash, cumulativeClears);
    }

    private static void requireSpawn(GameSnapshot snapshot) {
        if (snapshot.rows() != 20 || snapshot.cols() != 10
                || !BoardRules.canPlace(snapshot.occupied(), 20, 10, snapshot.currentCells())) {
            throw new IllegalStateException("invalid post-gravity spawn");
        }
    }

    private static Row describe(boolean[][] before, PlacementCandidate placement,
                                FixedWitnessInput.Step step, String hash, int cumulativeClears) {
        boolean[][] after = placement.resultingBoard();
        var progress = ShapeTarget.HEART.progress(after);
        var metrics = BoardOccupancyMetrics.analyze(after);
        // classifyHoles is a read-only scan; the audit() method is deliberately never called.
        var holes = ShapeWitnessConstraintAudit.classifyHoles(ShapeTarget.HEART, after);
        return new Row(step.pathId(), step.step(), step.sourceLine(), step.piece().name(),
                step.plan().actions().stream().map(Enum::name).toList(), hash,
                occupancy(before), occupancy(after), placement.clearedLines(),
                Math.addExact(cumulativeClears, placement.clearedLines()),
                progress, metrics.maxColumnHeight(), metrics.headroom(), holes);
    }

    private static Occupancy occupancy(boolean[][] board) {
        StringBuilder bits = new StringBuilder(200);
        int occupied = 0;
        for (boolean[] row : board) {
            for (boolean cell : row) {
                bits.append(cell ? '1' : '0');
                if (cell) occupied++;
            }
        }
        String value = bits.toString();
        return new Occupancy(value, FixedWitnessInput.sha256(value.getBytes(StandardCharsets.UTF_8)), occupied);
    }

    private static void append(FileChannel channel, Object event) throws IOException {
        ByteBuffer bytes = ByteBuffer.wrap((JSON.writeValueAsString(event) + "\n")
                .getBytes(StandardCharsets.UTF_8));
        while (bytes.hasRemaining()) channel.write(bytes);
        channel.force(true);
    }

    enum Status { COMPLETE, INVALID, EVIDENCE_MISMATCH }

    record Occupancy(String bits, String sha256, int occupied) { }

    record Row(String pathId, int step, int sourceLine, String piece, List<String> actions,
               String inputHash, Occupancy before, Occupancy after, int clearedLines,
               int cumulativeClears, ShapeProgress progress, int maxHeight, int headroom,
               ShapeWitnessConstraintAudit.HoleBreakdown holes) {
        Row {
            actions = List.copyOf(actions);
        }
    }

    record Result(Status status, int attempted, List<Row> rows, String reason) {
        Result {
            Objects.requireNonNull(status, "status");
            rows = List.copyOf(rows);
        }
    }
}
