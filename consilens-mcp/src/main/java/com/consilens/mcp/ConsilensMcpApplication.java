package com.consilens.mcp;

import com.consilens.mcp.catalog.ConsilensMcpCatalog;
import com.consilens.mcp.client.ConsilensServerClient;
import com.consilens.mcp.server.ConsilensMcpServerFactory;
import com.consilens.mcp.transport.McpHttpServer;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.transport.DefaultServerTransportSecurityValidator;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;

import java.io.IOException;

public class ConsilensMcpApplication {

    public static void main(String[] args) throws Exception {
        McpRuntimeConfig config = McpRuntimeConfig.parse(args);
        McpJsonMapper jsonMapper = new JacksonMcpJsonMapper(ConsilensJson.objectMapper());
        ConsilensMcpCatalog catalog = new ConsilensMcpCatalog();
        ConsilensServerClient client = new ConsilensServerClient(config.serverBaseUri(), config.serverApiKey(),
                jsonMapper, config.connectTimeout(), config.requestTimeout());

        if (config.transport() == McpRuntimeConfig.Transport.HTTP) {
            startHttp(config, jsonMapper, catalog, client);
            return;
        }

        StdioServerTransportProvider transport = new StdioServerTransportProvider(jsonMapper);
        ConsilensMcpServerFactory.configure(McpServer.sync(transport), catalog, client, jsonMapper,
                config.requestTimeout()).build();
        Thread.currentThread().join();
    }

    private static void startHttp(McpRuntimeConfig config,
                                  McpJsonMapper jsonMapper,
                                  ConsilensMcpCatalog catalog,
                                  ConsilensServerClient client) throws Exception {
        HttpServletStreamableServerTransportProvider transport = HttpServletStreamableServerTransportProvider.builder()
                .jsonMapper(jsonMapper)
                .mcpEndpoint(config.mcpEndpoint())
                .securityValidator(DefaultServerTransportSecurityValidator.builder()
                        .allowedHosts(config.allowedHosts())
                        .allowedOrigins(config.allowedOrigins())
                        .build())
                .build();
        ConsilensMcpServerFactory.configure(McpServer.sync(transport), catalog, client, jsonMapper,
                config.requestTimeout()).build();

        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.NO_SESSIONS);
        context.setContextPath("/");
        context.addServlet(new ServletHolder(transport), config.mcpEndpoint());
        context.addServlet(new HealthServlet(), "/healthz");

        McpHttpServer server = new McpHttpServer(config.httpHost(), config.httpPort(), context);
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
        server.start();
        server.join();
    }

    static String versionLabel() {
        String version = ConsilensMcpApplication.class.getPackage().getImplementationVersion();
        return "consilens-mcp " + (version == null || version.isBlank() ? "dev" : version);
    }

    static class HealthServlet extends HttpServlet {

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType("application/json");
            response.getWriter().write("{\"status\":\"UP\"}");
        }
    }
}
