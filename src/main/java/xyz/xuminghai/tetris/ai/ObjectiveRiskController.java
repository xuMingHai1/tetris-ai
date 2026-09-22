/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import java.util.Objects;
import java.util.Optional;

/**
 * Selects an objective risk profile from the health of the SURVIVAL top-1 resulting board.
 *
 * <p>The adaptive controller deliberately never selects {@link ObjectiveRiskProfile#RISKY}. The
 * calibration benchmark showed that allowing one additional hole caused a sharp survival drop, so
 * adaptive runtime control keeps {@code additional holes == 0} as a hard boundary and only varies
 * height/bumpiness freedom.</p>
 */
public final class ObjectiveRiskController {

    private static final ObjectiveRiskController ADAPTIVE =
            new ObjectiveRiskController(Optional.empty());

    private final Optional<ObjectiveRiskProfile> fixedProfile;

    private ObjectiveRiskController(Optional<ObjectiveRiskProfile> fixedProfile) {
        this.fixedProfile = fixedProfile;
    }

    /**
     * Returns the state-aware runtime controller.
     */
    public static ObjectiveRiskController adaptive() {
        return ADAPTIVE;
    }

    /**
     * Returns a fixed-profile controller for benchmark calibration.
     */
    public static ObjectiveRiskController fixed(ObjectiveRiskProfile profile) {
        return new ObjectiveRiskController(
                Optional.of(Objects.requireNonNull(profile, "profile")));
    }

    /**
     * Chooses the active risk profile from the SURVIVAL top-1 resulting board.
     */
    public Decision decide(PlacementCandidate survivalBaseline) {
        Objects.requireNonNull(survivalBaseline, "survivalBaseline");

        boolean[][] board = survivalBaseline.resultingBoard();
        int rows = board.length;
        int cols = board[0].length;
        int maxColumnHeight = maxColumnHeight(board);
        int headroom = rows - maxColumnHeight;
        int holes = survivalBaseline.holes();

        RiskLevel level = classify(rows, cols, headroom, holes);
        ObjectiveRiskProfile profile = fixedProfile.orElseGet(() -> switch (level) {
            case LOW -> ObjectiveRiskProfile.BALANCED;
            case NORMAL -> ObjectiveRiskProfile.CONSERVATIVE;
            case DANGER -> ObjectiveRiskProfile.STRICT;
        });

        return new Decision(level, profile, headroom, maxColumnHeight, holes);
    }

    private static RiskLevel classify(int rows, int cols, int headroom, int holes) {
        int dangerHeadroom = Math.max(4, rows / 4);
        int dangerHoles = Math.max(4, cols / 2);

        if (headroom <= dangerHeadroom || holes >= dangerHoles) {
            return RiskLevel.DANGER;
        }

        int lowRiskHeadroom = Math.max(8, rows / 2);
        if (headroom >= lowRiskHeadroom && holes <= 1) {
            return RiskLevel.LOW;
        }

        return RiskLevel.NORMAL;
    }

    private static int maxColumnHeight(boolean[][] board) {
        int rows = board.length;
        int cols = board[0].length;
        int maxHeight = 0;

        for (int col = 0; col < cols; col++) {
            for (int row = 0; row < rows; row++) {
                if (board[row][col]) {
                    maxHeight = Math.max(maxHeight, rows - row);
                    break;
                }
            }
        }
        return maxHeight;
    }

    public enum RiskLevel {
        LOW,
        NORMAL,
        DANGER
    }

    /**
     * One state-aware risk decision based only on the SURVIVAL baseline board.
     */
    public record Decision(
            RiskLevel level,
            ObjectiveRiskProfile profile,
            int headroom,
            int maxColumnHeight,
            int holes) {

        public Decision {
            Objects.requireNonNull(level, "level");
            Objects.requireNonNull(profile, "profile");
            if (headroom < 0 || maxColumnHeight < 0 || holes < 0) {
                throw new IllegalArgumentException("risk decision metrics must not be negative");
            }
            if (profile == ObjectiveRiskProfile.RISKY && level != RiskLevel.LOW) {
                // Fixed calibration may intentionally use RISKY at any board state.
                // Adaptive mode never returns RISKY, so this is intentionally not rejected.
            }
        }
    }
}
