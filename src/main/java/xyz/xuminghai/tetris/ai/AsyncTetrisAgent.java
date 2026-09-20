/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import java.util.concurrent.CompletionStage;

/**
 * Asynchronous decision contract for network-backed agents such as Jev.
 */
@FunctionalInterface
public interface AsyncTetrisAgent {

    CompletionStage<AiMove> decide(GameSnapshot snapshot);
}
