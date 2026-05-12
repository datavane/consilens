package com.consilens.ai.backend;

import com.consilens.ai.model.ChatMessage;
import com.consilens.ai.model.LLMResponse;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DeepSeekBackendTest {

    @Test
    void usesDeepSeekPaths() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .setBody("{"
                            + "\"choices\":[{"
                            + "\"message\":{\"content\":\"ok\"},"
                            + "\"finish_reason\":\"stop\""
                            + "}]"
                            + "}")
                    .addHeader("Content-Type", "application/json"));
            server.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));
            server.start();

            DeepSeekBackend backend = new DeepSeekBackend(server.url("").toString().replaceAll("/$", ""),
                    "deepseek-chat", "deepseek-key", Duration.ofSeconds(5), 0.3, 512);

            LLMResponse response = backend.chat(null, List.of(ChatMessage.user("hello")), List.of());
            assertThat(response.getText()).isEqualTo("ok");

            RecordedRequest chatRequest = server.takeRequest();
            assertThat(chatRequest.getPath()).isEqualTo("/chat/completions");
            assertThat(chatRequest.getHeader("Authorization")).isEqualTo("Bearer deepseek-key");
            String chatBody = chatRequest.getBody().readUtf8();
            assertThat(chatBody).contains("\"temperature\":0.3");
            assertThat(chatBody).contains("\"max_tokens\":512");

            assertThat(backend.isAvailable()).isTrue();
            RecordedRequest healthRequest = server.takeRequest();
            assertThat(healthRequest.getPath()).isEqualTo("/models");
        }
    }

    @Test
    void providerCreatesConfiguredBackend() {
        DeepSeekBackendProvider provider = new DeepSeekBackendProvider();

        assertThat(provider.getName()).isEqualTo("deepseek");
        assertThat(provider.create(Map.of(
                "baseUrl", "http://localhost",
                "model", "deepseek-reasoner",
                "apiKey", "key",
                "timeout", "5s",
                "temperature", 0.1,
                "maxTokens", 128
        )).info().getModel()).isEqualTo("deepseek-reasoner");
    }
}
