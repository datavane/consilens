package com.consilens.mcp.transport;

import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;

public class McpHttpServer {

    private final Server server;

    public McpHttpServer(String host, int port, ServletContextHandler contextHandler) {
        this.server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setHost(host);
        connector.setPort(port);
        server.addConnector(connector);
        this.server.setHandler(contextHandler);
    }

    public void start() throws Exception {
        server.start();
    }

    public void join() throws InterruptedException {
        server.join();
    }

    public void stop() {
        try {
            server.stop();
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to stop MCP HTTP server", exception);
        }
    }
}
