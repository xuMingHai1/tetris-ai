/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BagPieceGeneratorTest {

    @Test
    void eachBagContainsEveryTetrominoExactlyOnce() {
        BagPieceGenerator generator = new BagPieceGenerator(42L);
        Set<TetrominoType> firstBag = new HashSet<>();

        for (int i = 0; i < 7; i++) {
            firstBag.add(TetrominoType.from(generator.next()));
        }

        assertEquals(Set.of(TetrominoType.values()), firstBag);
    }

    @Test
    void sameSeedProducesSamePieceSequence() {
        BagPieceGenerator first = new BagPieceGenerator(12345L);
        BagPieceGenerator second = new BagPieceGenerator(12345L);
        List<TetrominoType> firstSequence = new ArrayList<>();
        List<TetrominoType> secondSequence = new ArrayList<>();

        for (int i = 0; i < 28; i++) {
            firstSequence.add(TetrominoType.from(first.next()));
            secondSequence.add(TetrominoType.from(second.next()));
        }

        assertEquals(firstSequence, secondSequence);
    }
}
