package com.consilens.mcp.server;

import com.consilens.mcp.McpRuntimeConfig;
import com.consilens.mcp.catalog.ConsilensMcpCatalog;
import com.consilens.mcp.catalog.McpToolSpec;
import com.consilens.mcp.client.ConsilensServerClient;
import com.consilens.mcp.client.ConsilensServerException;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public class ConsilensToolExecutor {

    private static final String RUN_TASK_PATH = "/v1/tasks/execute";
    private static final String ARTIFACT_PATH = "/v1/artifacts/{artifactId}";
    private static final Duration RUN_POLL_TIMEOUT = McpRuntimeConfig.RUN_POLL_TIMEOUT;
    private static final Duration RUN_POLL_INTERVAL = McpRuntimeConfig.RUN_POLL_INTERVAL;

    private final ConsilensServerClient client;
    private final ConsilensMcpCatalog catalog;
    private final McpJsonMapper jsonMapper;

    public ConsilensToolExecutor(ConsilensServerClient client,
                                 ConsilensMcpCatalog catalog,
                                 McpJsonMapper jsonMapper) {
        this.client = client;
        this.catalog = catalog;
        this.jsonMapper = jsonMapper;
    }

    public McpSchema.CallToolResult execute(String name, Map<String, Object> arguments) {
        try {
            Map<String, Object> result = doExecute(name, new LinkedHashMap<>(arguments == null ? Map.of() : arguments));
            return toolResult(false, result);
        } catch (ConsilensServerException | IllegalArgumentException exception) {
            return toolResult(true, errorPayload(exception));
        }
    }

    private Map<String, Object> doExecute(String name, Map<String, Object> arguments) {
        McpToolSpec spec = catalog.findTool(name)
                .orElseThrow(() -> new IllegalArgumentException("Unsupported MCP tool: " + name));
        if ("GET".equalsIgnoreCase(spec.httpMethod())) {
            return get(spec, arguments);
        }
        if (RUN_TASK_PATH.equals(spec.httpPath())) {
            return runDiff(spec, arguments);
        }
        return client.post(spec.httpPath(), arguments);
    }

    private Map<String, Object> get(McpToolSpec spec, Map<String, Object> arguments) {
        if (!ARTIFACT_PATH.equals(spec.httpPath())) {
            throw new IllegalArgumentException("Unsupported MCP GET tool: " + spec.name());
        }
        String artifactId = requireString(arguments, "artifactId");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("artifact", client.getArtifact(artifactId));
        if (Boolean.TRUE.equals(arguments.get("includeContent"))) {
            result.put("content", client.getArtifactContent(artifactId));
        }
        return result;
    }

    private Map<String, Object> runDiff(McpToolSpec spec, Map<String, Object> arguments) {
        requireString(arguments, "serialNo");
        requireString(arguments, "configArtifactId");
        Map<String, Object> accepted = client.post(spec.httpPath(), arguments);
        Object taskIdValue = accepted.get("taskId");
        if (taskIdValue == null || String.valueOf(taskIdValue).isBlank()) {
            return accepted;
        }
        String taskId = String.valueOf(taskIdValue);
        Instant deadline = Instant.now().plus(RUN_POLL_TIMEOUT);
        Map<String, Object> task = accepted;
        while (Instant.now().isBefore(deadline)) {
            task = client.get("/v1/tasks/" + taskId);
            String status = String.valueOf(task.getOrDefault("status", ""));
            if (isTerminalTaskStatus(status)) {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("accepted", accepted);
                result.put("task", task);
                return result;
            }
            waitForTaskPoll();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("accepted", accepted);
        result.put("task", task);
        result.put("timedOut", true);
        return result;
    }

    private boolean isTerminalTaskStatus(String status) {
        // RETRYABLE 表示任务已失败、等待人工重试，不会再自行推进；
        // 不把它视为终态会让每次调用白等满 RUN_POLL_TIMEOUT 并误报超时。
        return "SUCCEEDED".equalsIgnoreCase(status)
                || "FAILED".equalsIgnoreCase(status)
                || "CANCELLED".equalsIgnoreCase(status)
                || "RETRYABLE".equalsIgnoreCase(status);
    }

    private void waitForTaskPoll() {
        try {
            Thread.sleep(RUN_POLL_INTERVAL.toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for Consilens task completion", exception);
        }
    }

    private String requireString(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new IllegalArgumentException(key + " is required");
        }
        return String.valueOf(value);
    }

    private McpSchema.CallToolResult toolResult(boolean error, Map<String, Object> payload) {
        return new McpSchema.CallToolResult(
                java.util.List.of(new McpSchema.TextContent(writeJson(payload))),
                error,
                payload,
                null);
    }

    private Map<String, Object> errorPayload(Exception exception) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("error", exception.getMessage());
        if (exception instanceof ConsilensServerException serverException) {
            payload.put("errorCode", serverException.getErrorCode());
            payload.put("statusCode", serverException.getStatusCode());
            if (serverException.getTraceId() != null) {
                payload.put("traceId", serverException.getTraceId());
            }
        } else {
            payload.put("errorCode", "INVALID_INPUT");
        }
        return payload;
    }

    private String writeJson(Object value) {
        try {
            return jsonMapper.writeValueAsString(value);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to serialize MCP result", exception);
        }
    }
}
