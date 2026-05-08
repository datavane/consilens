package com.consilens.cli.ai;

import com.consilens.ai.spi.LLMBackend;
import com.consilens.ai.spi.LLMBackendManager;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Resolves an LLM backend from CLI options and environment defaults.
 */
public class LLMBackendResolver {

    public LLMBackend resolve(AIBackendOptions options) {
        AIBackendOptions effective = options == null ? AIBackendOptions.builder().build() : options;
        String backend = firstNonBlank(effective.getBackend(), env("CONSILENS_AI_BACKEND"), "noop");
        Map<String, Object> config = new LinkedHashMap<>();
        put(config, "model", firstNonBlank(effective.getModel(), env("CONSILENS_AI_MODEL"), null));
        put(config, "baseUrl", firstNonBlank(effective.getBaseUrl(), env("CONSILENS_AI_BASE_URL"), backendDefaultBaseUrl(backend)));
        put(config, "apiKey", firstNonBlank(effective.getApiKey(), apiKeyEnv(backend), null));
        put(config, "timeout", firstNonBlank(effective.getTimeout(), env("CONSILENS_AI_TIMEOUT"), null));
        put(config, "temperature", effective.getTemperature());
        put(config, "maxTokens", effective.getMaxTokens());
        try {
            return LLMBackendManager.getInstance().create(backend, config);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Unknown or unavailable AI backend: " + backend, e);
        }
    }

    private String backendDefaultBaseUrl(String backend) {
        if ("ollama".equalsIgnoreCase(backend)) {
            return firstNonBlank(env("OLLAMA_BASE_URL"), "http://localhost:11434");
        }
        return null;
    }

    private String apiKeyEnv(String backend) {
        if ("openai".equalsIgnoreCase(backend)) {
            return env("OPENAI_API_KEY");
        }
        if ("deepseek".equalsIgnoreCase(backend)) {
            return env("DEEPSEEK_API_KEY");
        }
        return null;
    }

    private void put(Map<String, Object> config, String key, Object value) {
        if (value != null && !String.valueOf(value).trim().isEmpty()) {
            config.put(key, value);
        }
    }

    private String env(String name) {
        return System.getenv(name);
    }

    private String firstNonBlank(String first, String second) {
        return firstNonBlank(first, second, null);
    }

    private String firstNonBlank(String first, String second, String third) {
        if (first != null && !first.trim().isEmpty()) {
            return first.trim();
        }
        if (second != null && !second.trim().isEmpty()) {
            return second.trim();
        }
        if (third != null && !third.trim().isEmpty()) {
            return third.trim();
        }
        return null;
    }
}
