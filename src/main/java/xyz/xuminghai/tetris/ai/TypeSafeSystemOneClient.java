/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Minimal HTTP adapter for TypeSafe System One Choice requests.
 *
 * <p>TypeSafe currently has no Java SDK, so this adapter uses the documented HTTP endpoint
 * directly. It owns authentication, JSON transport and the documented 429/529 retry policy while
 * keeping provider-specific payloads out of the Tetris domain model.</p>
 */
public final class TypeSafeSystemOneClient {

    static final URI DEFAULT_ENDPOINT = URI.create("https://api.typesafe.ai/v1/systemone");
    static final String DEFAULT_MODEL = "jev-latest";

    private static final String CHOICE_TYPE = "choice";
    private static final int DEFAULT_MAX_ATTEMPTS = 3;
    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration DEFAULT_INITIAL_BACKOFF = Duration.ofMillis(200);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String apiKey;
    private final String model;
    private final URI endpoint;
    private final Duration requestTimeout;
    private final int maxAttempts;
    private final Duration initialBackoff;
    private final Transport transport;
    private final Sleeper sleeper;

    public TypeSafeSystemOneClient(String apiKey) {
        this(apiKey, DEFAULT_MODEL);
    }

    public TypeSafeSystemOneClient(String apiKey, String model) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(DEFAULT_CONNECT_TIMEOUT)
                .build();
        this.apiKey = requireText(apiKey, "apiKey");
        this.model = requireText(model, "model");
        this.endpoint = DEFAULT_ENDPOINT;
        this.requestTimeout = DEFAULT_REQUEST_TIMEOUT;
        this.maxAttempts = DEFAULT_MAX_ATTEMPTS;
        this.initialBackoff = DEFAULT_INITIAL_BACKOFF;
        this.transport = (request, requestBody) -> {
            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new RawResponse(response.statusCode(), response.body());
        };
        this.sleeper = duration -> Thread.sleep(duration.toMillis());
    }

    TypeSafeSystemOneClient(
            String apiKey,
            String model,
            URI endpoint,
            Duration requestTimeout,
            int maxAttempts,
            Duration initialBackoff,
            Transport transport,
            Sleeper sleeper) {
        this.apiKey = requireText(apiKey, "apiKey");
        this.model = requireText(model, "model");
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.requestTimeout = requirePositive(requestTimeout, "requestTimeout");
        if (maxAttempts <= 0) {
            throw new IllegalArgumentException("maxAttempts must be greater than 0");
        }
        this.maxAttempts = maxAttempts;
        this.initialBackoff = requirePositive(initialBackoff, "initialBackoff");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
    }

    /**
     * Evaluates one Choice question against a structured state.
     *
     * @param questionId caller-owned answer key
     * @param state structured state sent to Jev
     * @param instructions decision instructions
     * @param criteria legal options keyed by caller-owned ids
     */
    public ChoiceResult choose(
            String questionId,
            Object state,
            String instructions,
            Map<String, ?> criteria) {
        String normalizedQuestionId = requireText(questionId, "questionId");
        String normalizedInstructions = requireText(instructions, "instructions");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(criteria, "criteria");
        if (criteria.isEmpty()) {
            throw new IllegalArgumentException("criteria must not be empty");
        }
        if (criteria.size() > 255) {
            throw new IllegalArgumentException("TypeSafe Choice supports at most 255 criteria");
        }

        Map<String, Object> question = new LinkedHashMap<>();
        question.put("type", CHOICE_TYPE);
        question.put("instructions", normalizedInstructions);
        question.put("criteria", criteria);

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("state", state);
        requestBody.put("model", model);
        requestBody.put("questions", Map.of(normalizedQuestionId, question));

        String json = objectMapper.writeValueAsString(requestBody);
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(requestTimeout)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();

        RawResponse response = sendWithRetry(request, json);
        return parseChoice(response.body(), normalizedQuestionId);
    }

    private RawResponse sendWithRetry(HttpRequest request, String requestBody) {
        Duration backoff = initialBackoff;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                RawResponse response = transport.send(request, requestBody);
                if (isSuccessful(response.statusCode())) {
                    return response;
                }
                if (isRetryable(response.statusCode()) && attempt < maxAttempts) {
                    sleeper.sleep(backoff);
                    backoff = backoff.multipliedBy(2);
                    continue;
                }
                throw new TypeSafeApiException(response.statusCode(), response.body());
            }
            catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("TypeSafe request was interrupted", exception);
            }
            catch (IOException exception) {
                throw new IllegalStateException("TypeSafe request failed", exception);
            }
        }
        throw new IllegalStateException("TypeSafe retry loop terminated unexpectedly");
    }

    private ChoiceResult parseChoice(String responseBody, String questionId) {
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode answer = root.path("answers").path(questionId);
        if (!CHOICE_TYPE.equals(answer.path("type").asText())) {
            throw new IllegalStateException("TypeSafe response does not contain the expected Choice answer");
        }

        JsonNode choiceNode = answer.path("choice");
        JsonNode confidenceNode = answer.path("confidence");
        if (!choiceNode.isTextual() || !confidenceNode.isNumber()) {
            throw new IllegalStateException("TypeSafe Choice answer is missing choice or confidence");
        }

        double confidence = confidenceNode.doubleValue();
        if (confidence < 0.0 || confidence > 1.0) {
            throw new IllegalStateException("TypeSafe Choice confidence is outside [0, 1]");
        }
        return new ChoiceResult(choiceNode.asText(), confidence);
    }

    private static boolean isSuccessful(int statusCode) {
        return statusCode >= 200 && statusCode < 300;
    }

    private static boolean isRetryable(int statusCode) {
        return statusCode == 429 || statusCode == 529;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static Duration requirePositive(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return duration;
    }

    public record ChoiceResult(String choice, double confidence) {

        public ChoiceResult {
            choice = requireText(choice, "choice");
            if (confidence < 0.0 || confidence > 1.0) {
                throw new IllegalArgumentException("confidence must be within [0, 1]");
            }
        }
    }

    @FunctionalInterface
    interface Transport {
        RawResponse send(HttpRequest request, String requestBody) throws IOException, InterruptedException;
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    record RawResponse(int statusCode, String body) {
        RawResponse {
            body = body == null ? "" : body;
        }
    }

    static final class TypeSafeApiException extends RuntimeException {

        private final int statusCode;

        TypeSafeApiException(int statusCode, String body) {
            super("TypeSafe API returned HTTP " + statusCode + ": " + abbreviate(body));
            this.statusCode = statusCode;
        }

        int statusCode() {
            return statusCode;
        }

        private static String abbreviate(String body) {
            String normalized = body == null ? "" : body.replaceAll("\\s+", " ").trim();
            return normalized.length() <= 512 ? normalized : normalized.substring(0, 512) + "...";
        }
    }
}
