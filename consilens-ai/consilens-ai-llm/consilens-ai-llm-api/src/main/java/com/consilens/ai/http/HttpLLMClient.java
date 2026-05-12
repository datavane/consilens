package com.consilens.ai.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;

/**
 * Thin HTTP client wrapper over OkHttp for LLM backend communication.
 */
@Slf4j
public class HttpLLMClient {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient client;
    private final ObjectMapper objectMapper;

    public HttpLLMClient() {
        this(Duration.ofSeconds(10), Duration.ofSeconds(120), Duration.ofSeconds(30), null);
    }

    public HttpLLMClient(Duration timeout) {
        this(timeout != null ? timeout : Duration.ofSeconds(10),
                timeout != null ? timeout : Duration.ofSeconds(120),
                timeout != null ? timeout : Duration.ofSeconds(30),
                timeout);
    }

    private HttpLLMClient(Duration connectTimeout, Duration readTimeout, Duration writeTimeout, Duration callTimeout) {
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(connectTimeout)
                .readTimeout(readTimeout)
                .writeTimeout(writeTimeout);
        if (callTimeout != null) {
            builder.callTimeout(callTimeout);
        }
        this.client = builder.build();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Sends a POST request with a JSON body and returns the parsed response.
     *
     * @param url  the endpoint URL
     * @param body the request body object (will be serialized to JSON)
     * @return the parsed JSON response
     * @throws IOException if the request fails
     */
    public JsonNode post(String url, Object body) throws IOException {
        return post(url, body, Map.of());
    }

    public JsonNode post(String url, Object body, Map<String, String> headers) throws IOException {
        String jsonBody = objectMapper.writeValueAsString(body);
        RequestBody requestBody = RequestBody.create(jsonBody, JSON);
        Request.Builder requestBuilder = new Request.Builder().url(url).post(requestBody);
        applyHeaders(requestBuilder, headers);
        Request request = requestBuilder.build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code() + " from " + url + ": " + response.message());
            }
            String responseBody = response.body() != null ? response.body().string() : "{}";
            return objectMapper.readTree(responseBody);
        }
    }

    /**
     * Sends a GET request and returns the parsed JSON response.
     *
     * @param url the URL to probe
     * @return the parsed JSON response
     * @throws IOException if the request fails
     */
    public JsonNode get(String url) throws IOException {
        return get(url, Map.of());
    }

    public JsonNode get(String url, Map<String, String> headers) throws IOException {
        Request.Builder requestBuilder = new Request.Builder().url(url).get();
        applyHeaders(requestBuilder, headers);
        Request request = requestBuilder.build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code() + " from " + url);
            }
            String responseBody = response.body() != null ? response.body().string() : "{}";
            return objectMapper.readTree(responseBody);
        }
    }

    /**
     * Checks whether the given URL is reachable.
     *
     * @param url the URL to probe
     * @return {@code true} if the server responds with a 2xx status
     */
    public boolean isReachable(String url) {
        return isReachable(url, Map.of());
    }

    public boolean isReachable(String url, Map<String, String> headers) {
        try {
            Request.Builder requestBuilder = new Request.Builder().url(url).get();
            applyHeaders(requestBuilder, headers);
            Request request = requestBuilder.build();
            try (Response response = client.newCall(request).execute()) {
                return response.isSuccessful();
            }
        } catch (Exception e) {
            log.debug("Reachability check failed for {}: {}", url, e.getMessage());
            return false;
        }
    }

    private void applyHeaders(Request.Builder requestBuilder, Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return;
        }
        headers.forEach((key, value) -> {
            if (key != null && !key.isBlank() && value != null && !value.isBlank()) {
                requestBuilder.header(key, value);
            }
        });
    }
}
