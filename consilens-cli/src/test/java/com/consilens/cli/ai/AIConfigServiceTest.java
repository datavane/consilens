package com.consilens.cli.ai;

import com.consilens.ai.model.BackendInfo;
import com.consilens.ai.model.ChatMessage;
import com.consilens.ai.model.FunctionDefinition;
import com.consilens.ai.model.LLMResponse;
import com.consilens.ai.spi.LLMBackend;
import com.consilens.cli.model.CliConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AIConfigServiceTest {

    @Test
    void shouldGenerateConfigFromStructuredLlmJsonWhenHintsAreMissing() {
        String json = "{"
                + "\"source\":{\"type\":\"mysql\",\"jdbcUrl\":\"jdbc:mysql://localhost:3306/source\","
                + "\"usernameEnv\":\"MYSQL_USER\",\"passwordEnv\":\"MYSQL_PASSWORD\","
                + "\"resourceType\":\"table\",\"resourceName\":\"users\"},"
                + "\"target\":{\"type\":\"postgresql\",\"jdbcUrl\":\"jdbc:postgresql://localhost:5432/target\","
                + "\"usernameEnv\":\"PG_USER\",\"passwordEnv\":\"PG_PASSWORD\","
                + "\"resourceType\":\"table\",\"resourceName\":\"users\"},"
                + "\"mapping\":{\"sourceKeys\":[\"id\"],\"targetKeys\":[\"id\"],"
                + "\"sourceFields\":[\"name\"],\"targetFields\":[\"name\"]},"
                + "\"strategy\":{\"mode\":\"checksum\",\"algorithm\":\"xor\"},"
                + "\"result\":{\"sinkFormat\":\"console\",\"sinkType\":\"result\"}"
                + "}";
        AIConfigService service = serviceReturning(json);

        AIConfigResult result = service.generate(AIConfigRequest.builder()
                .goal("compare users")
                .backendOptions(AIBackendOptions.builder().backend("openai").build())
                .build());

        assertTrue(result.isValid());
        CliConfiguration config = result.getConfiguration();
        assertEquals("mysql", config.getSource().getType());
        assertEquals("postgresql", config.getTarget().getType());
        assertEquals("id", config.getComparison().getKeys().getSource().get(0));
        assertTrue(result.getYaml().contains("comparison:"));
    }

    @Test
    void shouldRejectInvalidLlmJson() {
        AIConfigService service = serviceReturning("not json");

        AIConfigRequest request = AIConfigRequest.builder()
                .goal("compare users")
                .backendOptions(AIBackendOptions.builder().backend("openai").build())
                .build();

        assertThrows(IllegalArgumentException.class, () -> service.generate(request));
    }

    @Test
    void shouldNotCallNoopLlmWhenBackendOptionIsMissing() {
        AIConfigService service = new AIConfigService(
                new com.consilens.ai.config.AIConfigDraftValidator(),
                new AIConfigCompiler(),
                new StaticBackendResolver(new StaticBackend("not json")),
                new ObjectMapper());

        AIConfigResult result = service.generate(AIConfigRequest.builder()
                .goal("compare users")
                .backendOptions(AIBackendOptions.builder().backend(null).build())
                .build());

        assertEquals(false, result.isValid());
        assertTrue(result.getIssues().stream()
                .anyMatch(issue -> "AI_CONFIG_DATASET_TYPE_MISSING".equals(issue.getCode())));
    }

    private AIConfigService serviceReturning(String response) {
        return new AIConfigService(
                new com.consilens.ai.config.AIConfigDraftValidator(),
                new AIConfigCompiler(),
                new StaticBackendResolver(new StaticBackend(response)),
                new ObjectMapper());
    }

    private static class StaticBackendResolver extends LLMBackendResolver {
        private final LLMBackend backend;

        StaticBackendResolver(LLMBackend backend) {
            this.backend = backend;
        }

        @Override
        public LLMBackend resolve(AIBackendOptions options) {
            return backend;
        }
    }

    private static class StaticBackend implements LLMBackend {
        private final String response;

        StaticBackend(String response) {
            this.response = response;
        }

        @Override
        public LLMResponse chat(String systemPrompt, List<ChatMessage> messages, List<FunctionDefinition> functions) {
            return LLMResponse.builder().text(response).finishReason("stop").build();
        }

        @Override
        public String complete(String prompt) {
            return response;
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public BackendInfo info() {
            return BackendInfo.builder()
                    .name("static")
                    .model("test")
                    .version("test")
                    .supportsFunctionCalling(false)
                    .supportsStreaming(false)
                    .build();
        }
    }
}
