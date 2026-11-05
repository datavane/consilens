package com.consilens.mcp;

import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/**
 * Runtime configuration for the MCP runner. Also the single source of truth
 * for operational defaults shared by the HTTP transport, server client and
 * tool executor.
 */
public record McpRuntimeConfig(Transport transport,
                               URI serverBaseUri,
                               String serverApiKey,
                               String httpHost,
                               int httpPort,
                               String mcpEndpoint,
                               Duration connectTimeout,
                               Duration requestTimeout,
                               List<String> allowedHosts,
                               List<String> allowedOrigins) {

    public static final String DEFAULT_SERVER_URL = "http://127.0.0.1:8080";
    public static final String DEFAULT_HTTP_HOST = "127.0.0.1";
    public static final int DEFAULT_HTTP_PORT = 8090;
    public static final String DEFAULT_MCP_ENDPOINT = "/mcp";
    public static final long DEFAULT_CONNECT_TIMEOUT_MS = 10_000L;
    public static final long DEFAULT_REQUEST_TIMEOUT_MS = 60_000L;
    public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofMillis(DEFAULT_CONNECT_TIMEOUT_MS);
    public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofMillis(DEFAULT_REQUEST_TIMEOUT_MS);
    public static final List<String> DEFAULT_ALLOWED_HOSTS =
            List.of("localhost:*", "127.0.0.1:*", "[::1]:*");
    public static final List<String> DEFAULT_ALLOWED_ORIGINS =
            List.of("http://localhost:*", "http://127.0.0.1:*", "http://[::1]:*");

    public static final int MAX_RETRY_ATTEMPTS = 3;
    public static final long INITIAL_RETRY_DELAY_MS = 250L;

    public static final Duration RUN_POLL_TIMEOUT = Duration.ofSeconds(120);
    public static final Duration RUN_POLL_INTERVAL = Duration.ofSeconds(1);

    public enum Transport {
        STDIO,
        HTTP
    }

    static McpRuntimeConfig parse(String[] args) {
        Transport transport = Transport.STDIO;
        URI serverBaseUri = URI.create(value("CONSILENS_SERVER_URL", DEFAULT_SERVER_URL));
        String serverApiKey = System.getenv("CONSILENS_SERVER_API_KEY");
        String httpHost = value("CONSILENS_MCP_HOST", DEFAULT_HTTP_HOST);
        int httpPort = parsePort(value("CONSILENS_MCP_PORT", String.valueOf(DEFAULT_HTTP_PORT)),
                "CONSILENS_MCP_PORT");
        String mcpEndpoint = value("CONSILENS_MCP_ENDPOINT", DEFAULT_MCP_ENDPOINT);
        Duration connectTimeout = Duration.ofMillis(parsePositiveLong(
                value("CONSILENS_MCP_CONNECT_TIMEOUT_MS", String.valueOf(DEFAULT_CONNECT_TIMEOUT_MS)),
                "CONSILENS_MCP_CONNECT_TIMEOUT_MS"));
        Duration requestTimeout = Duration.ofMillis(parsePositiveLong(
                value("CONSILENS_MCP_REQUEST_TIMEOUT_MS", String.valueOf(DEFAULT_REQUEST_TIMEOUT_MS)),
                "CONSILENS_MCP_REQUEST_TIMEOUT_MS"));
        List<String> allowedHosts = listValue("CONSILENS_MCP_ALLOWED_HOSTS", DEFAULT_ALLOWED_HOSTS);
        List<String> allowedOrigins = listValue("CONSILENS_MCP_ALLOWED_ORIGINS", DEFAULT_ALLOWED_ORIGINS);

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--transport".equals(arg)) {
                transport = transport(next(args, ++i, arg));
            } else if ("--server-url".equals(arg)) {
                serverBaseUri = URI.create(next(args, ++i, arg));
            } else if ("--server-api-key".equals(arg)) {
                serverApiKey = next(args, ++i, arg);
            } else if ("--http-host".equals(arg)) {
                httpHost = next(args, ++i, arg);
            } else if ("--http-port".equals(arg)) {
                httpPort = parsePort(next(args, ++i, arg), arg);
            } else if ("--mcp-endpoint".equals(arg)) {
                mcpEndpoint = next(args, ++i, arg);
            } else if ("--connect-timeout-ms".equals(arg)) {
                connectTimeout = Duration.ofMillis(parsePositiveLong(next(args, ++i, arg), arg));
            } else if ("--request-timeout-ms".equals(arg)) {
                requestTimeout = Duration.ofMillis(parsePositiveLong(next(args, ++i, arg), arg));
            } else if ("--allowed-hosts".equals(arg)) {
                allowedHosts = parseList(next(args, ++i, arg), arg);
            } else if ("--allowed-origins".equals(arg)) {
                allowedOrigins = parseList(next(args, ++i, arg), arg);
            } else if ("--help".equals(arg) || "-h".equals(arg)) {
                printUsageAndExit();
            } else if ("--version".equals(arg)) {
                printVersionAndExit();
            } else {
                throw new IllegalArgumentException("Unknown argument: " + arg);
            }
        }
        if (!mcpEndpoint.startsWith("/")) {
            throw new IllegalArgumentException("--mcp-endpoint must start with /");
        }
        if (httpHost.isBlank()) {
            throw new IllegalArgumentException("--http-host must not be blank");
        }
        return new McpRuntimeConfig(transport, serverBaseUri, serverApiKey, httpHost, httpPort, mcpEndpoint,
                connectTimeout, requestTimeout, List.copyOf(allowedHosts), List.copyOf(allowedOrigins));
    }

    private static Transport transport(String value) {
        if ("stdio".equalsIgnoreCase(value)) {
            return Transport.STDIO;
        }
        if ("http".equalsIgnoreCase(value) || "streamable-http".equalsIgnoreCase(value)) {
            return Transport.HTTP;
        }
        throw new IllegalArgumentException("Unsupported transport: " + value);
    }

    private static String value(String envName, String fallback) {
        String value = System.getenv(envName);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String next(String[] args, int index, String option) {
        if (index >= args.length || args[index].startsWith("--")) {
            throw new IllegalArgumentException(option + " requires a value");
        }
        return args[index];
    }

    private static int parsePort(String value, String name) {
        long port = parsePositiveLong(value, name);
        if (port > 65535) {
            throw new IllegalArgumentException(name + " must be between 1 and 65535");
        }
        return (int) port;
    }

    private static long parsePositiveLong(String value, String name) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed <= 0) {
                throw new IllegalArgumentException(name + " must be positive");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " must be a number", exception);
        }
    }

    private static List<String> listValue(String envName, List<String> fallback) {
        String value = System.getenv(envName);
        return value == null || value.isBlank() ? fallback : parseList(value, envName);
    }

    private static List<String> parseList(String value, String name) {
        List<String> values = Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .toList();
        if (values.isEmpty()) {
            throw new IllegalArgumentException(name + " must include at least one value");
        }
        return values;
    }

    private static void printUsageAndExit() {
        System.out.println("Usage: consilens-mcp [--transport stdio|http] [--server-url URL] "
                + "[--server-api-key KEY] [--http-host HOST] [--http-port PORT] [--mcp-endpoint PATH] "
                + "[--connect-timeout-ms MS] [--request-timeout-ms MS] "
                + "[--allowed-hosts HOSTS] [--allowed-origins ORIGINS]");
        System.exit(0);
    }

    private static void printVersionAndExit() {
        System.out.println(ConsilensMcpApplication.versionLabel());
        System.exit(0);
    }
}
