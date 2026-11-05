package com.consilens.mcp.client;

import com.consilens.mcp.McpRuntimeConfig;
import io.modelcontextprotocol.json.McpJsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public class ConsilensServerClient {

    private static final int MAX_RETRY_ATTEMPTS = McpRuntimeConfig.MAX_RETRY_ATTEMPTS;
    private static final long INITIAL_RETRY_DELAY_MS = McpRuntimeConfig.INITIAL_RETRY_DELAY_MS;

    private final URI serverBaseUri;
    private final String serverApiKey;
    private final McpJsonMapper jsonMapper;
    private final HttpClient httpClient;
    private final Duration requestTimeout;

    public ConsilensServerClient(URI serverBaseUri, String serverApiKey, McpJsonMapper jsonMapper) {
        this(serverBaseUri, serverApiKey, jsonMapper,
                McpRuntimeConfig.DEFAULT_CONNECT_TIMEOUT, McpRuntimeConfig.DEFAULT_REQUEST_TIMEOUT);
    }

    public ConsilensServerClient(URI serverBaseUri,
                                 String serverApiKey,
                                 McpJsonMapper jsonMapper,
                                 Duration connectTimeout,
                                 Duration requestTimeout) {
        this(serverBaseUri, serverApiKey, jsonMapper, HttpClient.newBuilder()
                .connectTimeout(requirePositive(connectTimeout, "connectTimeout"))
                .build(), requestTimeout);
    }

    ConsilensServerClient(URI serverBaseUri, String serverApiKey, McpJsonMapper jsonMapper, HttpClient httpClient) {
        this(serverBaseUri, serverApiKey, jsonMapper, httpClient, McpRuntimeConfig.DEFAULT_REQUEST_TIMEOUT);
    }

    ConsilensServerClient(URI serverBaseUri,
                          String serverApiKey,
                          McpJsonMapper jsonMapper,
                          HttpClient httpClient,
                          Duration requestTimeout) {
        this.serverBaseUri = requireValidBaseUri(serverBaseUri);
        this.serverApiKey = serverApiKey;
        this.jsonMapper = jsonMapper;
        this.httpClient = httpClient;
        this.requestTimeout = requirePositive(requestTimeout, "requestTimeout");
    }

    public Map<String, Object> post(String path, Map<String, Object> body) {
        HttpRequest.Builder builder = baseRequest(path)
                .POST(HttpRequest.BodyPublishers.ofString(writeJson(body)));
        return send(builder.build());
    }

    public Map<String, Object> get(String path) {
        return send(baseRequest(path).GET().build());
    }

    public Map<String, Object> getArtifact(String artifactId) {
        return get("/v1/artifacts/" + encode(artifactId));
    }

    public Map<String, Object> getArtifactContent(String artifactId) {
        return get("/v1/artifacts/" + encode(artifactId) + "/content");
    }

    public Map<String, Object> getConfig(String configId) {
        return get("/v1/configs/" + encode(configId));
    }

    private HttpRequest.Builder baseRequest(String path) {
        URI uri = resolvePath(path);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(requestTimeout)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("X-Trace-Id", newTraceId());
        if (serverApiKey != null && !serverApiKey.isBlank()) {
            // 必须与服务端 ApiKeyAuthenticationFilter 读取的请求头一致，
            // 否则默认安全配置下 MCP 的每个请求都会 401。
            builder.header("X-Consilens-Api-Key", serverApiKey);
        }
        return builder;
    }

    private Map<String, Object> send(HttpRequest request) {
        // 只有幂等请求可以安全重试；POST /v1/plan|validate|diagnose 等每次都会创建
        // artifact，超时/5xx 后重发会产生重复工件。429 表示服务端未受理，可重试。
        boolean idempotent = isIdempotent(request.method());
        for (int attempt = 1; attempt <= MAX_RETRY_ATTEMPTS; attempt++) {
            try {
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                boolean retryableStatus = isRetryableStatus(response.statusCode())
                        && (idempotent || response.statusCode() == 429);
                if (retryableStatus && attempt < MAX_RETRY_ATTEMPTS) {
                    waitBeforeRetry(attempt, request);
                    continue;
                }
                ApiResponse apiResponse = readApiResponse(response);
                if (response.statusCode() < 200 || response.statusCode() >= 300 || !apiResponse.isSuccess()) {
                    throw new ConsilensServerException(response.statusCode(), errorCode(apiResponse),
                            traceId(apiResponse, response), message(apiResponse, response));
                }
                return data(apiResponse);
            } catch (IOException exception) {
                if (idempotent && attempt < MAX_RETRY_ATTEMPTS) {
                    waitBeforeRetry(attempt, request);
                    continue;
                }
                throw new ConsilensServerException(0, "SERVER_IO_ERROR", traceId(request), exception.getMessage());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new ConsilensServerException(0, "SERVER_INTERRUPTED", traceId(request),
                        "Consilens server request interrupted");
            }
        }
        throw new IllegalStateException("Unreachable retry state");
    }

    private boolean isIdempotent(String method) {
        return "GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method);
    }

    private ApiResponse readApiResponse(HttpResponse<String> response) {
        try {
            return jsonMapper.readValue(response.body(), ApiResponse.class);
        } catch (IOException exception) {
            throw new ConsilensServerException(response.statusCode(), "INVALID_SERVER_RESPONSE",
                    response.headers().firstValue("X-Trace-Id").orElse(null),
                    "Invalid Consilens server response");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> data(ApiResponse response) {
        Object data = response.getData();
        if (data instanceof Map) {
            return new LinkedHashMap<>((Map<String, Object>) data);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("data", data);
        return result;
    }

    private String writeJson(Map<String, Object> body) {
        try {
            return jsonMapper.writeValueAsString(body == null ? Map.of() : body);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Invalid JSON body", exception);
        }
    }

    private String message(ApiResponse apiResponse, HttpResponse<String> response) {
        if (apiResponse.getError() != null && !apiResponse.getError().isBlank()) {
            return apiResponse.getError();
        }
        return "Consilens server request failed with HTTP " + response.statusCode();
    }

    private String errorCode(ApiResponse apiResponse) {
        return apiResponse.getErrorCode() == null || apiResponse.getErrorCode().isBlank()
                ? "SERVER_ERROR"
                : apiResponse.getErrorCode();
    }

    private String traceId(ApiResponse apiResponse, HttpResponse<String> response) {
        if (apiResponse.getTraceId() != null && !apiResponse.getTraceId().isBlank()) {
            return apiResponse.getTraceId();
        }
        return response.headers().firstValue("X-Trace-Id").orElse(null);
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String newTraceId() {
        return "mcp-" + UUID.randomUUID();
    }

    private String traceId(HttpRequest request) {
        return request.headers().firstValue("X-Trace-Id").orElse(null);
    }

    private boolean isRetryableStatus(int statusCode) {
        return statusCode == 408 || statusCode == 429 || statusCode >= 500;
    }

    private void waitBeforeRetry(int attempt, HttpRequest request) {
        try {
            Thread.sleep(INITIAL_RETRY_DELAY_MS * (1L << (attempt - 1)));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ConsilensServerException(0, "SERVER_INTERRUPTED", traceId(request),
                    "Consilens server request interrupted");
        }
    }

    private URI resolvePath(String path) {
        String requestPath = path.startsWith("/") ? path : "/" + path;
        return baseDirectoryUri().resolve("." + requestPath);
    }

    private URI baseDirectoryUri() {
        String rawPath = serverBaseUri.getRawPath();
        if (rawPath == null || rawPath.isBlank() || rawPath.endsWith("/")) {
            return serverBaseUri;
        }
        return URI.create(serverBaseUri.toASCIIString() + "/");
    }

    private static Duration requirePositive(Duration duration, String name) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return duration;
    }

    private static URI requireValidBaseUri(URI serverBaseUri) {
        if (serverBaseUri == null || serverBaseUri.getScheme() == null || serverBaseUri.getHost() == null) {
            throw new IllegalArgumentException("Consilens server URL must include scheme and host");
        }
        String scheme = serverBaseUri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException("Consilens server URL must use http or https");
        }
        if (serverBaseUri.getRawUserInfo() != null) {
            throw new IllegalArgumentException("Consilens server URL must not embed credentials");
        }
        if (serverBaseUri.getRawQuery() != null || serverBaseUri.getRawFragment() != null) {
            throw new IllegalArgumentException("Consilens server URL must not include query or fragment");
        }
        return serverBaseUri;
    }
}
