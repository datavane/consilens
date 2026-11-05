package com.consilens.mcp;

import org.junit.jupiter.api.Test;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConsilensMcpApplicationConfigTest {

    @Test
    void shouldParseRuntimeTimeoutsAndServerPrefix() {
        McpRuntimeConfig config = McpRuntimeConfig.parse(new String[]{
                "--transport", "http",
                "--server-url", "http://127.0.0.1:8080/consilens/",
                "--http-host", "0.0.0.0",
                "--http-port", "18090",
                "--mcp-endpoint", "/agent/mcp",
                "--connect-timeout-ms", "2000",
                "--request-timeout-ms", "30000",
                "--allowed-hosts", "mcp.example.com,127.0.0.1:*",
                "--allowed-origins", "https://app.example.com,http://127.0.0.1:*"
        });

        assertThat(config.transport()).isEqualTo(McpRuntimeConfig.Transport.HTTP);
        assertThat(config.serverBaseUri()).isEqualTo(URI.create("http://127.0.0.1:8080/consilens/"));
        assertThat(config.httpHost()).isEqualTo("0.0.0.0");
        assertThat(config.httpPort()).isEqualTo(18090);
        assertThat(config.mcpEndpoint()).isEqualTo("/agent/mcp");
        assertThat(config.connectTimeout()).isEqualTo(Duration.ofMillis(2000));
        assertThat(config.requestTimeout()).isEqualTo(Duration.ofMillis(30000));
        assertThat(config.allowedHosts()).containsExactly("mcp.example.com", "127.0.0.1:*");
        assertThat(config.allowedOrigins()).containsExactly("https://app.example.com", "http://127.0.0.1:*");
    }

    @Test
    void shouldRejectInvalidPortAndTimeout() {
        assertThatThrownBy(() -> McpRuntimeConfig.parse(new String[]{
                "--http-port", "70000"
        })).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 1 and 65535");

        assertThatThrownBy(() -> McpRuntimeConfig.parse(new String[]{
                "--request-timeout-ms", "0"
        })).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");

        assertThatThrownBy(() -> McpRuntimeConfig.parse(new String[]{
                "--http-host", ""
        })).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be blank");

        assertThatThrownBy(() -> McpRuntimeConfig.parse(new String[]{
                "--allowed-hosts", ","
        })).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must include at least one value");
    }

    @Test
    void shouldExposeVersionLabel() {
        assertThat(ConsilensMcpApplication.versionLabel()).startsWith("consilens-mcp ");
    }

    @Test
    void shouldExposeHealthResponse() throws Exception {
        Server server = new Server(0);
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.NO_SESSIONS);
        context.setContextPath("/");
        context.addServlet(new ConsilensMcpApplication.HealthServlet(), "/healthz");
        server.setHandler(context);
        try {
            server.start();
            int port = ((ServerConnector) server.getConnectors()[0]).getLocalPort();
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/healthz")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("Content-Type")).hasValue("application/json");
            assertThat(response.body()).isEqualTo("{\"status\":\"UP\"}");
        } finally {
            server.stop();
        }
    }
}
