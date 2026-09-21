/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TypeSafeSystemOneClientTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void sendsDocumentedChoiceRequestAndParsesAnswer() {
        AtomicReference<HttpRequest> capturedRequest = new AtomicReference<>();
        AtomicReference<String> capturedBody = new AtomicReference<>();
        TypeSafeSystemOneClient client = client(
                (request, requestBody) -> {
                    capturedRequest.set(request);
                    capturedBody.set(requestBody);
                    return new TypeSafeSystemOneClient.RawResponse(
                            200,
                            """
                            {
                              "model": "jev-1.13.0",
                              "answers": {
                                "move": {
                                  "type": "choice",
                                  "choice": "c1",
                                  "confidence": 0.82,
                                  "probabilities": {"c0": 0.18, "c1": 0.82}
                                }
                              },
                              "usage": {"input_tokens": 100, "output_tokens": 20}
                            }
                            """);
                },
                duration -> {
                });

        TypeSafeSystemOneClient.ChoiceResult result = client.choose(
                "move",
                Map.of("current_piece", "T"),
                "Choose one",
                Map.of("c0", Map.of("holes", 2), "c1", Map.of("holes", 0)));

        assertEquals("c1", result.choice());
        assertEquals(0.82, result.confidence(), 0.0001);
        assertEquals(100, result.inputTokens());
        assertEquals(20, result.outputTokens());
        assertEquals("Bearer test-key",
                capturedRequest.get().headers().firstValue("Authorization").orElseThrow());

        JsonNode json = OBJECT_MAPPER.readTree(capturedBody.get());
        assertEquals("jev-latest", json.path("model").asText());
        assertEquals("T", json.path("state").path("current_piece").asText());
        assertEquals("choice", json.path("questions").path("move").path("type").asText());
        assertEquals(2, json.path("questions").path("move").path("criteria").size());
    }

    @Test
    void retries429And529WithExponentialBackoff() {
        Deque<TypeSafeSystemOneClient.RawResponse> responses = new ArrayDeque<>(List.of(
                new TypeSafeSystemOneClient.RawResponse(429, "{}"),
                new TypeSafeSystemOneClient.RawResponse(529, "{}"),
                new TypeSafeSystemOneClient.RawResponse(
                        200,
                        """
                        {"answers":{"move":{"type":"choice","choice":"c0","confidence":0.7}},"usage":{"input_tokens":12,"output_tokens":4}}
                        """)));
        List<Duration> sleeps = new ArrayList<>();
        TypeSafeSystemOneClient client =
                client((request, requestBody) -> responses.removeFirst(), sleeps::add);

        TypeSafeSystemOneClient.ChoiceResult result =
                client.choose("move", Map.of("state", true), "Choose one", Map.of("c0", "candidate"));

        assertEquals("c0", result.choice());
        assertEquals(List.of(Duration.ofMillis(200), Duration.ofMillis(400)), sleeps);
    }

    @Test
    void doesNotRetryNonRetryableErrors() {
        List<Duration> sleeps = new ArrayList<>();
        TypeSafeSystemOneClient client = client(
                (request, requestBody) -> new TypeSafeSystemOneClient.RawResponse(401, "invalid key"),
                sleeps::add);

        TypeSafeSystemOneClient.TypeSafeApiException failure = assertThrows(
                TypeSafeSystemOneClient.TypeSafeApiException.class,
                () -> client.choose("move", Map.of("state", true), "Choose one", Map.of("c0", "candidate")));

        assertEquals(401, failure.statusCode());
        assertEquals(List.of(), sleeps);
    }

    private static TypeSafeSystemOneClient client(
            TypeSafeSystemOneClient.Transport transport,
            TypeSafeSystemOneClient.Sleeper sleeper) {
        return new TypeSafeSystemOneClient(
                "test-key",
                "jev-latest",
                URI.create("https://example.invalid/systemone"),
                Duration.ofSeconds(1),
                3,
                Duration.ofMillis(200),
                transport,
                sleeper);
    }
}
