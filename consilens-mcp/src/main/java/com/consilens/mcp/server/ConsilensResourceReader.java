package com.consilens.mcp.server;

import com.consilens.mcp.client.ConsilensServerClient;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;

public class ConsilensResourceReader {

    private final ConsilensServerClient client;
    private final McpJsonMapper jsonMapper;

    public ConsilensResourceReader(ConsilensServerClient client, McpJsonMapper jsonMapper) {
        this.client = client;
        this.jsonMapper = jsonMapper;
    }

    public McpSchema.ReadResourceResult read(String uri) {
        Map<String, Object> payload;
        String resourceId;
        if (uri != null && uri.startsWith("consilens://artifacts/")) {
            resourceId = requiredResourceId(uri, "artifacts");
            payload = client.getArtifactContent(resourceId);
        } else if (uri != null && uri.startsWith("consilens://configs/")) {
            resourceId = requiredResourceId(uri, "configs");
            payload = client.getConfig(resourceId);
        } else if (uri != null && uri.startsWith("consilens://diagnosis/")) {
            resourceId = requiredResourceId(uri, "diagnosis");
            payload = client.getArtifactContent(resourceId);
        } else {
            throw McpError.RESOURCE_NOT_FOUND.apply(uri);
        }
        return new McpSchema.ReadResourceResult(List.of(
                new McpSchema.TextResourceContents(uri, "application/json", writeJson(payload))));
    }

    private String requiredResourceId(String uri, String authority) {
        String id;
        try {
            URI parsed = URI.create(uri);
            if (!authority.equals(parsed.getAuthority())) {
                throw McpError.RESOURCE_NOT_FOUND.apply(uri);
            }
            String path = parsed.getPath();
            id = path == null || path.length() <= 1 ? "" : path.substring(1);
        } catch (IllegalArgumentException exception) {
            throw McpError.builder(McpSchema.ErrorCodes.INVALID_PARAMS)
                    .message("Invalid resource uri")
                    .data(Map.of("uri", uri))
                    .build();
        }
        if (id.isBlank()) {
            throw McpError.builder(McpSchema.ErrorCodes.INVALID_PARAMS)
                    .message("Resource id is required")
                    .data(Map.of("uri", uri))
                    .build();
        }
        return id;
    }

    private String writeJson(Object value) {
        try {
            return jsonMapper.writeValueAsString(value);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to serialize MCP resource", exception);
        }
    }
}
