/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.integration.typesafe;

import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

/**
 * Java HTTP client for TypeSafe System One.
 *
 * <p>The client intentionally uses the documented REST API instead of embedding the Python or JavaScript SDK.
 * Requests are asynchronous. HTTP 429 and 529 are retried with exponential backoff as recommended by TypeSafe.</p>
 */
public final class TypeSafeSystemOneClient implements TypeSafeChoiceClient {

    public static final URI DEFAULT_ENDPOINT = URI.create("https://api.typesafe.ai/v1/systemone");
    public static final String DEFAULT_MODEL = "jev-latest";
    public static final String API_KEY_ENV = "TYPESAFE_API_KEY";

    private static final String QUESTION_ID = "decision";
    private static final int MAX_CHOICE_OPTIONS = 255;

    private final HttpClient httpClient;
    private final JsonMapper jsonMapper;
    private final URI endpoint;
    private final String apiKey;
    private final String model;
    private final Duration requestTimeout;
    private final int maxAttempts;
    private final Duration initialBackoff;

    public TypeSafeSystemOneClient(String apiKey) {
        this(
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(5))
                        .build(),
                JsonMapper.builder().build(),
                DEFAULT_ENDPOINT,
                apiKey,
                DEFAULT_MODEL,
                Duration.ofSeconds(30),
                3,
                Duration.ofMillis(250));
    }

    TypeSafeSystemOneClient(
            HttpClient httpClient,
            JsonMapper jsonMapper,
            URI endpoint,
            String apiKey,
            String model,
            Duration requestTimeout,
            int maxAttempts,
            Duration initialBackoff) {

        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.jsonMapper = Objects.requireNonNull(jsonMapper, "jsonMapper");
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.apiKey = requireText(apiKey, "apiKey");
        this.model = requireText(model, "model");
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1");
        }
        this.maxAttempts = maxAttempts;
        this.initialBackoff = Objects.requireNonNull(initialBackoff, "initialBackoff");
    }

    public static Optional<TypeSafeSystemOneClient> fromEnvironment() {
        String apiKey = System.getenv(API_KEY_ENV);
        if (apiKey == null || apiKey.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new TypeSafeSystemOneClient(apiKey));
    }

    @Override
    public CompletionStage<TypeSafeChoiceResult> choose(
            Object state,
            Object instructions,
            Map<String, ?> criteria) {

        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(instructions, "instructions");
        Objects.requireNonNull(criteria, "criteria");
        if (criteria.isEmpty()) {
            throw new IllegalArgumentException("Choice criteria must not be empty");
        }
        if (criteria.size() > MAX_CHOICE_OPTIONS) {
            throw new IllegalArgumentException("Choice criteria must contain at most " + MAX_CHOICE_OPTIONS + " options");
        }

        Map<String, Object> stableCriteria = new LinkedHashMap<>();
        criteria.forEach(stableCriteria::put);

        ChoiceQuestion question = new ChoiceQuestion("choice", instructions, stableCriteria);
        SystemOneRequest request = new SystemOneRequest(
                state,
                model,
                Map.of(QUESTION_ID, question));

        final String body;
        try {
            body = jsonMapper.writeValueAsString(request);
        }
        catch (Exception exception) {
            return CompletableFuture.failedFuture(exception);
        }
        return send(body, stableCriteria, 1);
    }

    private CompletionStage<TypeSafeChoiceResult> send(
            String body,
            Map<String, Object> criteria,
            int attempt) {

        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(requestTimeout)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenCompose(response -> handleResponse(body, criteria, attempt, response));
    }

    private CompletionStage<TypeSafeChoiceResult> handleResponse(
            String body,
            Map<String, Object> criteria,
            int attempt,
            HttpResponse<String> response) {

        int statusCode = response.statusCode();
        if (statusCode >= 200 && statusCode < 300) {
            try {
                return CompletableFuture.completedFuture(parseChoice(response.body(), criteria));
            }
            catch (Exception exception) {
                return CompletableFuture.failedFuture(exception);
            }
        }

        if ((statusCode == 429 || statusCode == 529) && attempt < maxAttempts) {
            long delayMillis = initialBackoff.toMillis() * (1L << (attempt - 1));
            return CompletableFuture
                    .runAsync(
                            () -> {
                            },
                            CompletableFuture.delayedExecutor(delayMillis, TimeUnit.MILLISECONDS))
                    .thenCompose(ignored -> send(body, criteria, attempt + 1));
        }

        return CompletableFuture.failedFuture(new TypeSafeApiException(statusCode, response.body()));
    }

    private TypeSafeChoiceResult parseChoice(String body, Map<String, Object> criteria) throws Exception {
        SystemOneResponse response = jsonMapper.readValue(body, SystemOneResponse.class);
        ChoiceAnswer answer = response.answers().get(QUESTION_ID);
        if (answer == null) {
            throw new IllegalStateException("TypeSafe response does not contain the decision answer");
        }
        if (!"choice".equals(answer.type())) {
            throw new IllegalStateException("TypeSafe response decision is not a Choice answer");
        }
        if (!criteria.containsKey(answer.choice())) {
            throw new IllegalStateException("TypeSafe response selected an unknown Choice option: " + answer.choice());
        }

        Usage usage = response.usage();
        return new TypeSafeChoiceResult(
                response.model(),
                answer.choice(),
                answer.confidence(),
                answer.probabilities(),
                usage == null ? 0 : usage.input_tokens(),
                usage == null ? 0 : usage.output_tokens());
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private record SystemOneRequest(
            Object state,
            String model,
            Map<String, ChoiceQuestion> questions) {
    }

    private record ChoiceQuestion(
            String type,
            Object instructions,
            Map<String, Object> criteria) {
    }

    private record SystemOneResponse(
            String model,
            Map<String, ChoiceAnswer> answers,
            Usage usage) {
    }

    private record ChoiceAnswer(
            String type,
            String choice,
            double confidence,
            Map<String, Double> probabilities) {
    }

    private record Usage(
            int input_tokens,
            int output_tokens) {
    }
}
