package com.consilens.mcp.server;

import com.consilens.mcp.ConsilensJson;
import com.consilens.mcp.client.ConsilensServerClient;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConsilensResourceReaderTest {

    @Test
    void shouldReadConfigResourceFromServerConfigApi() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .setBody("{\"success\":true,\"data\":{\"artifact\":{\"id\":\"config-1\"},\"status\":\"READY\"},\"traceId\":\"trace-1\"}")
                    .addHeader("Content-Type", "application/json"));
            server.start();
            JacksonMcpJsonMapper jsonMapper = new JacksonMcpJsonMapper(ConsilensJson.objectMapper());
            ConsilensResourceReader reader = new ConsilensResourceReader(
                    new ConsilensServerClient(URI.create(server.url("/").toString()), null, jsonMapper),
                    jsonMapper);

            var result = reader.read("consilens://configs/config-1");

            var content = (McpSchema.TextResourceContents) result.contents().get(0);
            assertThat(content.text()).contains("\"status\":\"READY\"");
            assertThat(server.takeRequest().getPath()).isEqualTo("/v1/configs/config-1");
        }
    }

    @Test
    void shouldDecodeEncodedResourceIdBeforeCallingServer() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .setBody("{\"success\":true,\"data\":{\"artifact\":{\"id\":\"config 1+2\"}},\"traceId\":\"trace-1\"}")
                    .addHeader("Content-Type", "application/json"));
            server.start();
            JacksonMcpJsonMapper jsonMapper = new JacksonMcpJsonMapper(ConsilensJson.objectMapper());
            ConsilensResourceReader reader = new ConsilensResourceReader(
                    new ConsilensServerClient(URI.create(server.url("/").toString()), null, jsonMapper),
                    jsonMapper);

            reader.read("consilens://configs/config%201%2B2");

            assertThat(server.takeRequest().getPath()).isEqualTo("/v1/configs/config%201%2B2");
        }
    }

    @Test
    void shouldRejectEmptyResourceIdBeforeCallingServer() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            JacksonMcpJsonMapper jsonMapper = new JacksonMcpJsonMapper(ConsilensJson.objectMapper());
            ConsilensResourceReader reader = new ConsilensResourceReader(
                    new ConsilensServerClient(URI.create(server.url("/").toString()), null, jsonMapper),
                    jsonMapper);

            assertThatThrownBy(() -> reader.read("consilens://configs/"))
                    .hasMessageContaining("Resource id is required");
            assertThat(server.getRequestCount()).isZero();
        }
    }
}
