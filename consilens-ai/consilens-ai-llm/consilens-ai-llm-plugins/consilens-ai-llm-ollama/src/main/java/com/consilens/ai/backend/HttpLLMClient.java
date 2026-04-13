package com.consilens.ai.backend;

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

/**
 * Thin HTTP client wrapper over OkHttp for LLM backend communication.
 */
@Slf4j
public class HttpLLMClient {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient client;
    private final ObjectMapper objectMapper;

    public HttpLLMClient() {
        this.client = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ofSeconds(120))
                .writeTimeout(Duration.ofSeconds(30))
                .build();
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
        String jsonBody = objectMapper.writeValueAsString(body);
        RequestBody requestBody = RequestBody.create(jsonBody, JSON);
        Request request = new Request.Builder()
                .url(url)
                .post(requestBody)
                .build();
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
     * @param url the endpoint URL
     * @return the parsed JSON response
     * @throws IOException if the request fails
     */
    public JsonNode get(String url) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .get()
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code() + " from " + url);
            }
            String responseBody = response.body() != null ? response.body().string() : "{}";
            return objectMapper.readTree(responseBody);
        }
    }

    /**
     * Checks whether the given base URL is reachable.
     *
     * @param url the URL to probe
     * @return {@code true} if the server responds with a 2xx status
     */
    public boolean isReachable(String url) {
        try {
            Request request = new Request.Builder().url(url).get().build();
            try (Response response = client.newCall(request).execute()) {
                return response.isSuccessful();
            }
        } catch (Exception e) {
            log.debug("Reachability check failed for {}: {}", url, e.getMessage());
            return false;
        }
    }
}
