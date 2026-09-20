/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai.jev;

import xyz.xuminghai.tetris.ai.AiMove;
import xyz.xuminghai.tetris.ai.AsyncTetrisAgent;
import xyz.xuminghai.tetris.ai.GameSnapshot;
import xyz.xuminghai.tetris.ai.MoveCandidate;
import xyz.xuminghai.tetris.ai.MoveCandidateGenerator;
import xyz.xuminghai.tetris.core.BoardPosition;
import xyz.xuminghai.tetris.integration.typesafe.TypeSafeChoiceClient;
import xyz.xuminghai.tetris.integration.typesafe.TypeSafeChoiceResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Network-backed Tetris agent that asks TypeSafe Jev to choose between legal candidate placements.
 *
 * <p>Jev never invents coordinates. Java enumerates legal moves and computes deterministic board features first,
 * then Jev receives those candidates as a typed Choice question.</p>
 */
public final class JevTetrisAgent implements AsyncTetrisAgent {

    private static final String BOARD_EMPTY = ".";
    private static final String BOARD_OCCUPIED = "#";

    private final TypeSafeChoiceClient client;
    private final MoveCandidateGenerator candidateGenerator;

    public JevTetrisAgent(TypeSafeChoiceClient client) {
        this(client, new MoveCandidateGenerator());
    }

    JevTetrisAgent(TypeSafeChoiceClient client, MoveCandidateGenerator candidateGenerator) {
        this.client = Objects.requireNonNull(client, "client");
        this.candidateGenerator = Objects.requireNonNull(candidateGenerator, "candidateGenerator");
    }

    @Override
    public CompletionStage<AiMove> decide(GameSnapshot snapshot) {
        return decideDetailed(snapshot).thenApply(JevDecision::move);
    }

    public CompletionStage<JevDecision> decideDetailed(GameSnapshot snapshot) {
        List<MoveCandidate> candidates = candidateGenerator.generate(snapshot);
        if (candidates.isEmpty()) {
            return CompletableFuture.completedFuture(
                    new JevDecision(AiMove.NONE, "no-candidate", 1.0, Map.of(AiMove.NONE, 1.0), 0, 0));
        }

        LinkedHashMap<String, MoveCandidate> candidatesById = new LinkedHashMap<>();
        LinkedHashMap<String, Object> criteria = new LinkedHashMap<>();
        for (int index = 0; index < candidates.size(); index++) {
            String id = "move_%03d".formatted(index);
            MoveCandidate candidate = candidates.get(index);
            candidatesById.put(id, candidate);
            criteria.put(id, candidateDescription(candidate));
        }

        Map<String, Object> state = Map.of(
                "board", boardRows(snapshot),
                "current_piece", snapshot.currentType().name(),
                "current_cells", snapshot.currentCells().stream()
                        .map(JevTetrisAgent::position)
                        .toList());

        Map<String, Object> instructions = Map.of(
                "task", "Choose the candidate move that best preserves long-term Tetris board survivability.",
                "priority", List.of(
                        "Avoid creating holes.",
                        "Keep aggregate height and surface bumpiness low.",
                        "Prefer clearing lines when it does not create a worse board."),
                "board_encoding", "Rows are top-to-bottom; # means occupied and . means empty.");

        return client.choose(state, instructions, criteria)
                .thenApply(result -> toDecision(result, candidatesById));
    }

    private static Map<String, Object> candidateDescription(MoveCandidate candidate) {
        return Map.of(
                "clockwise_rotations", candidate.move().clockwiseRotations(),
                "horizontal_shift", candidate.move().horizontalShift(),
                "cleared_lines", candidate.clearedLines(),
                "aggregate_height", candidate.aggregateHeight(),
                "holes", candidate.holes(),
                "bumpiness", candidate.bumpiness(),
                "heuristic_score", candidate.heuristicScore());
    }

    private static List<String> boardRows(GameSnapshot snapshot) {
        boolean[][] occupied = snapshot.occupied();
        List<String> rows = new ArrayList<>(snapshot.rows());
        for (boolean[] row : occupied) {
            StringBuilder encoded = new StringBuilder(snapshot.cols());
            for (boolean cell : row) {
                encoded.append(cell ? BOARD_OCCUPIED : BOARD_EMPTY);
            }
            rows.add(encoded.toString());
        }
        return List.copyOf(rows);
    }

    private static Map<String, Integer> position(BoardPosition position) {
        return Map.of("row", position.row(), "col", position.col());
    }

    private static JevDecision toDecision(
            TypeSafeChoiceResult result,
            Map<String, MoveCandidate> candidatesById) {

        MoveCandidate selected = candidatesById.get(result.choice());
        if (selected == null) {
            throw new IllegalStateException("Jev selected an unknown candidate: " + result.choice());
        }

        LinkedHashMap<AiMove, Double> probabilities = new LinkedHashMap<>();
        result.probabilities().forEach((id, probability) -> {
            MoveCandidate candidate = candidatesById.get(id);
            if (candidate != null) {
                probabilities.put(candidate.move(), probability);
            }
        });

        return new JevDecision(
                selected.move(),
                result.model(),
                result.confidence(),
                probabilities,
                result.inputTokens(),
                result.outputTokens());
    }
}
