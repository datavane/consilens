package com.consilens.mcp.server;

import com.consilens.mcp.McpRuntimeConfig;
import com.consilens.mcp.catalog.ConsilensMcpCatalog;
import com.consilens.mcp.client.ConsilensServerClient;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.spec.McpSchema;

import java.time.Duration;

public final class ConsilensMcpServerFactory {

    private ConsilensMcpServerFactory() {
    }

    public static <S extends McpServer.SyncSpecification<S>> S configure(S spec,
                                                                         ConsilensMcpCatalog catalog,
                                                                         ConsilensServerClient client) {
        return configure(spec, catalog, client, McpJsonDefaults.getMapper(),
                McpRuntimeConfig.DEFAULT_REQUEST_TIMEOUT);
    }

    public static <S extends McpServer.SyncSpecification<S>> S configure(S spec,
                                                                         ConsilensMcpCatalog catalog,
                                                                         ConsilensServerClient client,
                                                                         McpJsonMapper jsonMapper,
                                                                         Duration requestTimeout) {
        ConsilensToolExecutor toolExecutor = new ConsilensToolExecutor(client, catalog, jsonMapper);
        ConsilensResourceReader resourceReader = new ConsilensResourceReader(client, jsonMapper);
        ConsilensPromptProvider promptProvider = new ConsilensPromptProvider();

        spec.serverInfo("consilens-mcp", "0.1-SNAPSHOT")
                .instructions("Expose Consilens server atomic compare capabilities as MCP tools and resources.")
                .jsonMapper(jsonMapper)
                .requestTimeout(requestTimeout)
                .capabilities(McpSchema.ServerCapabilities.builder()
                        .tools(false)
                        .resources(false, false)
                        .prompts(false)
                        .build())
                .strictToolNameValidation(true);

        catalog.tools().forEach(tool -> spec.toolCall(tool,
                (exchange, request) -> toolExecutor.execute(request.name(), request.arguments())));
        catalog.resourceTemplates().forEach(template -> spec.resourceTemplates(
                new io.modelcontextprotocol.server.McpServerFeatures.SyncResourceTemplateSpecification(
                        template,
                        (exchange, request) -> resourceReader.read(request.uri()))));
        catalog.prompts().forEach(prompt -> spec.prompts(
                new io.modelcontextprotocol.server.McpServerFeatures.SyncPromptSpecification(
                        prompt,
                        (exchange, request) -> promptProvider.getPrompt(request.name()))));

        return spec;
    }
}
