/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.integration.typesafe;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TypeSafeSystemOneClientTest {

    @Test
    void retriesRateLimitAndParsesChoiceResponse() {
        FakeHttpClient httpClient = new FakeHttpClient(
                new StubResponse(429, "{\"detail\":\"rate limited\"}"),
                new StubResponse(200, """
                        {
                          "model": "jev-1.13.0",
                          "answers": {
                            "decision": {
                              "type": "choice",
                              "choice": "move_001",
                              "confidence": 0.78,
                              "probabilities": {
                                "move_000": 0.22,
                                "move_001": 0.78
                              }
                            }
                          },
                          "usage": {
                            "input_tokens": 120,
                            "output_tokens": 18
                          }
                        }
                        """));

        TypeSafeSystemOneClient client = new TypeSafeSystemOneClient(
                httpClient,
                JsonMapper.builder().build(),
                URI.create("https://example.test/v1/systemone"),
                "secret",
                "jev-latest",
                Duration.ofSeconds(1),
                3,
                Duration.ZERO);

        TypeSafeChoiceResult result = client.choose(
                        Map.of("board", List.of("....")),
                        "Choose a move",
                        Map.of("move_000", null, "move_001", Map.of("cleared_lines", 1)))
                .toCompletableFuture()
                .join();

        assertEquals("move_001", result.choice());
        assertEquals(0.78, result.confidence());
        assertEquals(120, result.inputTokens());
        assertEquals(18, result.outputTokens());
        assertEquals(2, httpClient.requests().size());
        assertEquals(
                "Bearer secret",
                httpClient.requests().getFirst().headers().firstValue("Authorization").orElseThrow());
    }

    private static final class FakeHttpClient extends HttpClient {

        private final ArrayDeque<HttpResponse<String>> responses = new ArrayDeque<>();
        private final List<HttpRequest> requests = new ArrayList<>();

        private FakeHttpClient(HttpResponse<String>... responses) {
            this.responses.addAll(List.of(responses));
        }

        private List<HttpRequest> requests() {
            return List.copyOf(requests);
        }

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return Optional.of(Duration.ofSeconds(1));
        }

        @Override
        public Redirect followRedirects() {
            return Redirect.NEVER;
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return Optional.empty();
        }

        @Override
        public SSLContext sslContext() {
            return null;
        }

        @Override
        public SSLParameters sslParameters() {
            return new SSLParameters();
        }

        @Override
        public Optional<Authenticator> authenticator() {
            return Optional.empty();
        }

        @Override
        public Version version() {
            return Version.HTTP_2;
        }

        @Override
        public Optional<Executor> executor() {
            return Optional.empty();
        }

        @Override
        public <T> HttpResponse<T> send(
                HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler)
                throws IOException, InterruptedException {
            throw new UnsupportedOperationException("Synchronous send is not used");
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler) {

            requests.add(request);
            return CompletableFuture.completedFuture((HttpResponse<T>) responses.removeFirst());
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler,
                HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            return sendAsync(request, responseBodyHandler);
        }
    }

    private record StubResponse(int statusCode, String body) implements HttpResponse<String> {

        @Override
        public HttpRequest request() {
            return null;
        }

        @Override
        public Optional<HttpResponse<String>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            return HttpHeaders.of(Map.of(), (_, _) -> true);
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return URI.create("https://example.test/v1/systemone");
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_2;
        }
    }
}
