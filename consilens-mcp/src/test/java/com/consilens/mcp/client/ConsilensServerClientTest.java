package com.consilens.mcp.client;

import com.consilens.mcp.ConsilensJson;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConsilensServerClientTest {

    @Test
    void shouldPostApiRequestAndReturnData() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .setBody("{\"success\":true,\"data\":{\"artifact\":{\"id\":\"a1\"}},\"traceId\":\"t1\"}")
                    .addHeader("Content-Type", "application/json"));
            server.start();

            ConsilensServerClient client = new ConsilensServerClient(
                    URI.create(server.url("/").toString()),
                    "key-1",
                    new JacksonMcpJsonMapper(ConsilensJson.objectMapper()));

            Map<String, Object> result = client.post("/v1/plan", Map.of("goal", "compare"));

            assertThat(result).containsKey("artifact");
            var request = server.takeRequest();
            assertThat(request.getPath()).isEqualTo("/v1/plan");
            assertThat(request.getHeader("X-Consilens-Api-Key")).isEqualTo("key-1");
        }
    }

    @Test
    void shouldPreserveServerBasePathWhenResolvingRequests() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .setBody("{\"success\":true,\"data\":{\"artifact\":{\"id\":\"a 1\"}},\"traceId\":\"t1\"}")
                    .addHeader("Content-Type", "application/json"));
            server.start();

            ConsilensServerClient client = new ConsilensServerClient(
                    URI.create(server.url("/gateway/consilens/").toString()),
                    null,
                    new JacksonMcpJsonMapper(ConsilensJson.objectMapper()));

            client.getArtifact("a 1");

            assertThat(server.takeRequest().getPath()).isEqualTo("/gateway/consilens/v1/artifacts/a%201");
        }
    }

    @Test
    void shouldKeepHttpStatusWhenErrorBodyIsNotApiResponse() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse()
                    .setResponseCode(502)
                    .setBody("<html>bad gateway</html>")
                    .addHeader("X-Trace-Id", "gateway-trace"));
            server.enqueue(new MockResponse()
                    .setResponseCode(502)
                    .setBody("<html>bad gateway</html>")
                    .addHeader("X-Trace-Id", "gateway-trace"));
            server.enqueue(new MockResponse()
                    .setResponseCode(502)
                    .setBody("<html>bad gateway</html>")
                    .addHeader("X-Trace-Id", "gateway-trace"));
            server.start();

            ConsilensServerClient client = new ConsilensServerClient(
                    URI.create(server.url("/").toString()),
                    null,
                    new JacksonMcpJsonMapper(ConsilensJson.objectMapper()));

            assertThatThrownBy(() -> client.get("/v1/artifacts/a1"))
                    .isInstanceOf(ConsilensServerException.class)
                    .extracting("statusCode", "errorCode", "traceId")
                    .containsExactly(502, "INVALID_SERVER_RESPONSE", "gateway-trace");
        }
    }

    @Test
    void shouldUseTraceHeaderWhenApiErrorBodyDoesNotIncludeTraceId() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse()
                    .setResponseCode(503)
                    .setBody("{\"success\":false,\"error\":\"server unavailable\"}")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("X-Trace-Id", "gateway-trace"));
            server.enqueue(new MockResponse()
                    .setResponseCode(503)
                    .setBody("{\"success\":false,\"error\":\"server unavailable\"}")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("X-Trace-Id", "gateway-trace"));
            server.enqueue(new MockResponse()
                    .setResponseCode(503)
                    .setBody("{\"success\":false,\"error\":\"server unavailable\"}")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("X-Trace-Id", "gateway-trace"));
            server.start();

            ConsilensServerClient client = new ConsilensServerClient(
                    URI.create(server.url("/").toString()),
                    null,
                    new JacksonMcpJsonMapper(ConsilensJson.objectMapper()));

            assertThatThrownBy(() -> client.get("/v1/artifacts/a1"))
                    .isInstanceOf(ConsilensServerException.class)
                    .extracting("statusCode", "errorCode", "traceId")
                    .containsExactly(503, "SERVER_ERROR", "gateway-trace");
        }
    }

    @Test
    void shouldRetryTransientServerErrors() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(503).setBody("temporarily unavailable"));
            server.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .setBody("{\"success\":true,\"data\":{\"artifact\":{\"id\":\"a1\"}}}")
                    .addHeader("Content-Type", "application/json"));
            server.start();

            ConsilensServerClient client = new ConsilensServerClient(
                    URI.create(server.url("/").toString()),
                    null,
                    new JacksonMcpJsonMapper(ConsilensJson.objectMapper()));

            assertThat(client.get("/v1/artifacts/a1")).containsKey("artifact");
            assertThat(server.getRequestCount()).isEqualTo(2);
        }
    }

    @Test
    void shouldKeepTraceIdWhenRequestFailsBeforeServerResponse() {
        JacksonMcpJsonMapper jsonMapper = new JacksonMcpJsonMapper(ConsilensJson.objectMapper());
        ConsilensServerClient client = new ConsilensServerClient(
                URI.create("http://127.0.0.1:1"),
                null,
                jsonMapper,
                new FailingHttpClient(),
                Duration.ofSeconds(1));

        assertThatThrownBy(() -> client.get("/v1/artifacts/a1"))
                .isInstanceOfSatisfying(ConsilensServerException.class, exception -> {
                    assertThat(exception.getStatusCode()).isZero();
                    assertThat(exception.getErrorCode()).isEqualTo("SERVER_IO_ERROR");
                    assertThat(exception.getTraceId()).startsWith("mcp-");
                });
    }

    @Test
    void shouldRejectNonHttpBaseUrls() {
        JacksonMcpJsonMapper jsonMapper = new JacksonMcpJsonMapper(ConsilensJson.objectMapper());

        assertThatThrownBy(() -> new ConsilensServerClient(URI.create("ftp://example.com"), null, jsonMapper))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("http");
        assertThatThrownBy(() -> new ConsilensServerClient(
                URI.create("http://user:secret@example.com"), null, jsonMapper))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("credentials");
    }

    private static class FailingHttpClient extends HttpClient {

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return Optional.empty();
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
        public javax.net.ssl.SSLContext sslContext() {
            return null;
        }

        @Override
        public javax.net.ssl.SSLParameters sslParameters() {
            return null;
        }

        @Override
        public Optional<Authenticator> authenticator() {
            return Optional.empty();
        }

        @Override
        public Version version() {
            return Version.HTTP_1_1;
        }

        @Override
        public Optional<java.util.concurrent.Executor> executor() {
            return Optional.empty();
        }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler)
                throws IOException {
            throw new IOException("connection refused");
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                                                                HttpResponse.BodyHandler<T> responseBodyHandler) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                                                                HttpResponse.BodyHandler<T> responseBodyHandler,
                                                                HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            throw new UnsupportedOperationException();
        }
    }
}
