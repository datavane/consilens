package com.consilens.ai.backend;

import com.consilens.ai.model.ChatMessage;
import com.consilens.ai.model.FunctionDefinition;
import com.consilens.ai.model.LLMResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAIBackendTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void sendsChatCompletionRequestAndParsesToolCalls() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .setBody("{"
                            + "\"choices\":[{"
                            + "\"message\":{"
                            + "\"content\":\"use tool\","
                            + "\"tool_calls\":[{"
                            + "\"id\":\"call_1\","
                            + "\"type\":\"function\","
                            + "\"function\":{"
                            + "\"name\":\"consilens_config_generate\","
                            + "\"arguments\":\"{\\\"source_table\\\":\\\"orders\\\",\\\"limit\\\":100}\""
                            + "}"
                            + "}]"
                            + "},"
                            + "\"finish_reason\":\"tool_calls\""
                            + "}],"
                            + "\"usage\":{"
                            + "\"prompt_tokens\":10,"
                            + "\"completion_tokens\":5,"
                            + "\"total_tokens\":15"
                            + "}"
                            + "}")
                    .addHeader("Content-Type", "application/json"));
            server.start();

            OpenAIBackend backend = new OpenAIBackend(server.url("/v1").toString().replaceAll("/$", ""),
                    "test-model", "test-key", Duration.ofSeconds(5), 0.2, 256);
            FunctionDefinition function = FunctionDefinition.builder()
                    .name("consilens_config_generate")
                    .description("Generate config")
                    .parameters(objectMapper.readTree("{\"type\":\"object\"}"))
                    .build();

            LLMResponse response = backend.chat("system", List.of(ChatMessage.user("generate config")), List.of(function));

            RecordedRequest request = server.takeRequest();
            JsonNode requestBody = objectMapper.readTree(request.getBody().readUtf8());
            assertThat(request.getPath()).isEqualTo("/v1/chat/completions");
            assertThat(request.getHeader("Authorization")).isEqualTo("Bearer test-key");
            assertThat(requestBody.path("model").asText()).isEqualTo("test-model");
            assertThat(requestBody.path("temperature").asDouble()).isEqualTo(0.2);
            assertThat(requestBody.path("max_tokens").asInt()).isEqualTo(256);
            assertThat(requestBody.path("messages").get(0).path("role").asText()).isEqualTo("system");
            assertThat(requestBody.path("messages").get(1).path("content").asText()).isEqualTo("generate config");
            assertThat(requestBody.path("tools").get(0).path("function").path("name").asText()).isEqualTo("consilens_config_generate");
            assertThat(requestBody.path("tool_choice").asText()).isEqualTo("auto");

            assertThat(response.getFinishReason()).isEqualTo("tool_calls");
            assertThat(response.getUsage().getTotalTokens()).isEqualTo(15);
            assertThat(response.getToolCalls()).hasSize(1);
            assertThat(response.getToolCalls().get(0).getName()).isEqualTo("consilens_config_generate");
            assertThat(response.getToolCalls().get(0).getArguments())
                    .containsEntry("source_table", "orders")
                    .containsEntry("limit", 100L);
        }
    }

    @Test
    void providerCreatesConfiguredBackend() {
        OpenAIBackendProvider provider = new OpenAIBackendProvider();

        assertThat(provider.getName()).isEqualTo("openai");
        assertThat(provider.create(Map.of(
                "baseUrl", "http://localhost",
                "model", "openai-model",
                "apiKey", "key",
                "timeout", "5s",
                "temperature", 0.1,
                "maxTokens", 128
        )).info().getModel()).isEqualTo("openai-model");
    }

    @Test
    void missingApiKeyReturnsUnavailableAndErrorResponse() {
        OpenAIBackend backend = new OpenAIBackend("http://localhost", "model", "");

        assertThat(backend.isAvailable()).isFalse();
        LLMResponse response = backend.chat(null, List.of(ChatMessage.user("hello")), List.of());

        assertThat(response.getFinishReason()).isEqualTo("error");
        assertThat(response.getText()).contains("api key is required");
    }
}
