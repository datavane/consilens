package com.consilens.ai.tool;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigGenerateToolTest {

    private final ConfigGenerateTool tool = new ConfigGenerateTool();

    @Test
    void shouldHaveCorrectName() {
        assertThat(tool.getName()).isEqualTo("consilens_config_generate");
    }

    @Test
    void shouldBeReadOnly() {
        assertThat(tool.isReadOnly()).isTrue();
    }

    @Test
    void shouldGenerateYamlConfig() {
        com.fasterxml.jackson.databind.node.ObjectNode input = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        input.put("source_url", "jdbc:mysql://source-host:3306/mydb");
        input.put("source_username", "root");
        input.put("source_table", "mydb.orders");
        input.put("target_url", "jdbc:mysql://target-host:3306/mydb");
        input.put("target_username", "root");
        input.put("target_table", "mydb.orders");
        input.put("primary_keys", "id");

        ToolContext context = ToolContext.builder()
                .conversation(new com.consilens.ai.chat.ConversationContext())
                .build();

        ToolResult result = tool.execute(input, context);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("source:");
        assertThat(result.getContent()).contains("target:");
        assertThat(result.getContent()).contains("connection:");
        assertThat(result.getContent()).contains("resource:");
        assertThat(result.getContent()).contains("comparison:");
        assertThat(result.getContent()).contains("keys:");
        assertThat(result.getContent()).contains("strategy:");
        assertThat(result.getContent()).contains("result:");
        assertThat(result.getContent()).doesNotContain("comparisons:");
        assertThat(result.getContent()).doesNotContain("primary_keys:");
        assertThat(result.getContent()).contains("id");
    }

    @Test
    void shouldReturnFailureWhenRequiredParamMissing() {
        com.fasterxml.jackson.databind.node.ObjectNode input = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();

        ToolContext context = ToolContext.builder()
                .conversation(new com.consilens.ai.chat.ConversationContext())
                .build();

        ToolResult result = tool.execute(input, context);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).isNotBlank();
    }

    @Test
    void shouldHaveValidInputSchema() {
        JsonNode schema = tool.getInputSchema();
        assertThat(schema).isNotNull();
        assertThat(schema.path("type").asText()).isEqualTo("object");
        assertThat(schema.path("properties").has("source_url")).isTrue();
        assertThat(schema.path("properties").has("target_url")).isTrue();
        assertThat(schema.path("properties").has("primary_keys")).isTrue();
    }
}
