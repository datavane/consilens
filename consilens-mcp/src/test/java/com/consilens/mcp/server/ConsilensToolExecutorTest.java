package com.consilens.mcp.server;

import com.consilens.mcp.ConsilensJson;
import com.consilens.mcp.catalog.ConsilensMcpCatalog;
import com.consilens.mcp.client.ConsilensServerClient;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConsilensToolExecutorTest {

    @Test
    void shouldRejectRunDiffWithoutSerialNo() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            JacksonMcpJsonMapper jsonMapper = new JacksonMcpJsonMapper(ConsilensJson.objectMapper());
            ConsilensToolExecutor executor = new ConsilensToolExecutor(
                    new ConsilensServerClient(URI.create(server.url("/").toString()), null, jsonMapper),
                    new ConsilensMcpCatalog(),
                    jsonMapper);

            var result = executor.execute("consilens.run.diff", Map.of("configArtifactId", "config-1"));

            assertThat(result.isError()).isTrue();
            assertThat(result.structuredContent().toString()).contains("serialNo is required");
            assertThat(server.getRequestCount()).isZero();
        }
    }

    @Test
    void shouldForwardRunDiffWithCallerSerialNo() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse()
                    .setResponseCode(202)
                    .setBody("{\"success\":true,\"data\":{\"taskId\":\"task-1\",\"status\":\"PENDING\"},\"traceId\":\"trace-1\"}")
                    .addHeader("Content-Type", "application/json"));
            server.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .setBody("{\"success\":true,\"data\":{\"taskId\":\"task-1\",\"status\":\"SUCCEEDED\"},\"traceId\":\"trace-1\"}")
                    .addHeader("Content-Type", "application/json"));
            server.start();
            JacksonMcpJsonMapper jsonMapper = new JacksonMcpJsonMapper(ConsilensJson.objectMapper());
            ConsilensToolExecutor executor = new ConsilensToolExecutor(
                    new ConsilensServerClient(URI.create(server.url("/").toString()), null, jsonMapper),
                    new ConsilensMcpCatalog(),
                    jsonMapper);

            var result = executor.execute("consilens.run.diff", Map.of(
                    "serialNo", "run-001",
                    "configArtifactId", "config-1"));

            assertThat(result.isError()).isFalse();
            assertThat(server.takeRequest().getBody().readUtf8()).contains("\"serialNo\":\"run-001\"");
            assertThat(server.takeRequest().getPath()).isEqualTo("/v1/tasks/task-1");
        }
    }
}
