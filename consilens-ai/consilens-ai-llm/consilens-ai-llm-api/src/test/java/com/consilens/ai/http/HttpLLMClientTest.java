package com.consilens.ai.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HttpLLMClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void postSendsHeadersAndJsonBody() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .setBody("{\"ok\":true}")
                    .addHeader("Content-Type", "application/json"));
            server.start();

            HttpLLMClient client = new HttpLLMClient();
            JsonNode body = objectMapper.readTree("{\"model\":\"test\"}");
            JsonNode response = client.post(server.url("/v1/chat/completions").toString(), body,
                    Map.of("Authorization", "Bearer token"));

            RecordedRequest request = server.takeRequest();
            assertThat(request.getMethod()).isEqualTo("POST");
            assertThat(request.getHeader("Authorization")).isEqualTo("Bearer token");
            assertThat(request.getHeader("Content-Type")).contains("application/json");
            assertThat(objectMapper.readTree(request.getBody().readUtf8()).path("model").asText()).isEqualTo("test");
            assertThat(response.path("ok").asBoolean()).isTrue();
        }
    }

    @Test
    void isReachableUsesHeaders() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));
            server.start();

            HttpLLMClient client = new HttpLLMClient();
            assertThat(client.isReachable(server.url("/models").toString(), Map.of("Authorization", "Bearer token"))).isTrue();

            RecordedRequest request = server.takeRequest();
            assertThat(request.getMethod()).isEqualTo("GET");
            assertThat(request.getHeader("Authorization")).isEqualTo("Bearer token");
        }
    }
}
