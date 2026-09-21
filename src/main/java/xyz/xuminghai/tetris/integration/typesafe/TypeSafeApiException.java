/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.integration.typesafe;

/**
 * Non-success response returned by the TypeSafe API.
 */
public final class TypeSafeApiException extends RuntimeException {

    private final int statusCode;

    public TypeSafeApiException(int statusCode, String responseBody) {
        super("TypeSafe API request failed with HTTP " + statusCode + ": " + abbreviate(responseBody));
        this.statusCode = statusCode;
    }

    public int statusCode() {
        return statusCode;
    }

    private static String abbreviate(String body) {
        if (body == null) {
            return "";
        }
        String normalized = body.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 500 ? normalized : normalized.substring(0, 500) + "...";
    }
}
