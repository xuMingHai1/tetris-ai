/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.integration.typesafe;

import java.util.Map;
import java.util.concurrent.CompletionStage;

/**
 * Minimal async boundary for a TypeSafe Choice evaluation.
 */
@FunctionalInterface
public interface TypeSafeChoiceClient {

    CompletionStage<TypeSafeChoiceResult> choose(
            Object state,
            Object instructions,
            Map<String, ?> criteria);
}
