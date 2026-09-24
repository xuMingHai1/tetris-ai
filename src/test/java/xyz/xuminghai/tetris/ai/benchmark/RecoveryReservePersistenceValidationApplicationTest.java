/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai.benchmark;

import org.junit.jupiter.api.Test;
import xyz.xuminghai.tetris.ai.RecoveryRobustnessBenchmark;
import xyz.xuminghai.tetris.core.TetrominoType;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecoveryReservePersistenceValidationApplicationTest {

    @Test
    void groupsOnlyConsecutiveWarningPositionsIntoEpisodes() {
        List<RecoveryReservePersistenceValidationApplication.Episode> episodes =
                RecoveryReservePersistenceValidationApplication.buildEpisodes(
                        RecoveryReservePersistenceValidationApplication.Source.FAILED_RUNTIME,
                        4001L,
                        List.of(10, 11, 12, 20, 22, 23),
                        25);

        assertEquals(3, episodes.size());
        assertEquals(3, episodes.get(0).length());
        assertEquals(1, episodes.get(1).length());
        assertEquals(2, episodes.get(2).length());
        assertTrue(episodes.stream().allMatch(
                RecoveryReservePersistenceValidationApplication.Episode::recovered));
        assertEquals(15, episodes.get(0).startDistanceToFailure());
        assertEquals(13, episodes.get(0).endDistanceToFailure());
    }

    @Test
    void marksEpisodeAtFailedGameEndAsTerminalInsteadOfRecovered() {
        RecoveryReservePersistenceValidationApplication.Episode episode =
                RecoveryReservePersistenceValidationApplication.buildEpisodes(
                                RecoveryReservePersistenceValidationApplication.Source.FAILED_RUNTIME,
                                4002L,
                                List.of(48, 49, 50),
                                50)
                        .getFirst();

        assertFalse(episode.recovered());
        assertTrue(episode.terminalOrCensored());
        assertEquals(2, episode.startDistanceToFailure());
        assertEquals(0, episode.endDistanceToFailure());
    }

    @Test
    void marksHealthyEpisodeAtHorizonAsCensored() {
        RecoveryReservePersistenceValidationApplication.Episode episode =
                RecoveryReservePersistenceValidationApplication.buildEpisodes(
                                RecoveryReservePersistenceValidationApplication.Source.HEALTHY_RUNTIME,
                                4003L,
                                List.of(999, 1000),
                                1000)
                        .getFirst();

        assertFalse(episode.recovered());
        assertTrue(episode.terminalOrCensored());
        assertEquals(-1, episode.startDistanceToFailure());
        assertEquals(-1, episode.endDistanceToFailure());
    }

    @Test
    void persistenceValidationDelegatesToFrozenWarningContract() {
        assertTrue(RecoveryReservePersistenceValidationApplication.warning(probe(2, 15)));
        assertFalse(RecoveryReservePersistenceValidationApplication.warning(probe(3, 15)));
        assertFalse(RecoveryReservePersistenceValidationApplication.warning(probe(2, 14)));
    }

    @Test
    void formatsEpisodeCsv() {
        RecoveryReservePersistenceValidationApplication.Episode episode =
                new RecoveryReservePersistenceValidationApplication.Episode(
                        RecoveryReservePersistenceValidationApplication.Source.FAILED_RUNTIME,
                        4010L,
                        90,
                        93,
                        4,
                        true,
                        false,
                        10,
                        7);

        String[] columns =
                RecoveryReservePersistenceValidationApplication.formatEpisode(episode)
                        .split(",", -1);

        assertEquals(10, columns.length);
        assertEquals("recovery_reserve_persistence_episode", columns[0]);
        assertEquals("failed-runtime", columns[1]);
        assertEquals("4010", columns[2]);
        assertEquals("4", columns[5]);
        assertEquals("true", columns[6]);
        assertEquals("false", columns[7]);
    }

    private static RecoveryRobustnessBenchmark.Probe probe(
            int recoveryHeadroom,
            int recoveryHoles) {
        return new RecoveryRobustnessBenchmark.Probe(
                TetrominoType.T,
                20,
                true,
                recoveryHeadroom,
                40,
                recoveryHoles,
                8,
                7,
                0,
                9,
                12.0,
                24,
                4,
                18,
                55);
    }
}
