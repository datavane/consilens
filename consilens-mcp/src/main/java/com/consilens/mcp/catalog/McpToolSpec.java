package com.consilens.mcp.catalog;

import io.modelcontextprotocol.spec.McpSchema;

import java.util.List;
import java.util.Map;

public record McpToolSpec(String name,
                          String title,
                          String description,
                          List<String> required,
                          Map<String, Object> properties,
                          Map<String, Object> outputSchema,
                          McpSchema.ToolAnnotations annotations,
                          String httpMethod,
                          String httpPath) {

    public McpSchema.Tool toTool() {
        return McpSchema.Tool.builder()
                .name(name)
                .title(title)
                .description(description)
                .inputSchema(new McpSchema.JsonSchema("object", properties, required, true, null, null))
                .outputSchema(outputSchema)
                .annotations(annotations)
                .meta(Map.of("httpMethod", httpMethod, "httpPath", httpPath))
                .build();
    }
}
