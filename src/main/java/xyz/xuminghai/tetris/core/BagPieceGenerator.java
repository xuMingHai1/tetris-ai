/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.core;

import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

/**
 * Standard 7-bag generator with optional deterministic seeding.
 *
 * <p>Each generator owns its bag and random source, so simulations can be reproduced without sharing global bag
 * state with the interactive game.</p>
 */
public final class BagPieceGenerator implements PieceGenerator {

    private static final String RANDOM_ALGORITHM = "Xoroshiro128PlusPlus";

    private final RandomGenerator randomGenerator;

    private final List<TetrominoType> bag = new ArrayList<>(TetrominoType.values().length);

    public BagPieceGenerator() {
        this(RandomGeneratorFactory.of(RANDOM_ALGORITHM).create());
    }

    public BagPieceGenerator(long seed) {
        this(RandomGeneratorFactory.of(RANDOM_ALGORITHM).create(seed));
    }

    private BagPieceGenerator(RandomGenerator randomGenerator) {
        this.randomGenerator = randomGenerator;
    }

    @Override
    public Tetris next() {
        if (bag.isEmpty()) {
            bag.addAll(List.of(TetrominoType.values()));
        }
        return TetrisFactory.create(bag.remove(randomGenerator.nextInt(bag.size())));
    }
}
