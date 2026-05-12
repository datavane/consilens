package com.consilens.ai.backend;

import com.consilens.ai.spi.LLMBackend;
import com.consilens.ai.spi.LLMBackendProvider;

import java.time.Duration;
import java.util.Map;

/**
 * SPI provider for the OpenAI backend.
 */
public class OpenAIBackendProvider implements LLMBackendProvider {

    private static final String KEY_BASE_URL = "baseUrl";
    private static final String KEY_MODEL = "model";
    private static final String KEY_API_KEY = "apiKey";
    private static final String KEY_TIMEOUT = "timeout";
    private static final String KEY_TEMPERATURE = "temperature";
    private static final String KEY_MAX_TOKENS = "maxTokens";

    @Override
    public String getName() {
        return "openai";
    }

    @Override
    public LLMBackend create() {
        return new OpenAIBackend();
    }

    @Override
    public LLMBackend create(Map<String, ?> config) {
        if (config == null || config.isEmpty()) {
            return create();
        }
        String baseUrl = valueOrDefault(config, KEY_BASE_URL, "https://api.openai.com/v1");
        String model = valueOrDefault(config, KEY_MODEL, "gpt-4.1-mini");
        String apiKey = valueOrEnv(config, KEY_API_KEY, "OPENAI_API_KEY");
        return new OpenAIBackend(baseUrl, model, apiKey,
                duration(config.get(KEY_TIMEOUT)),
                doubleValue(config.get(KEY_TEMPERATURE)),
                integerValue(config.get(KEY_MAX_TOKENS)));
    }

    private String valueOrDefault(Map<String, ?> config, String key, String defaultValue) {
        Object value = config.get(key);
        if (value == null) {
            return defaultValue;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? defaultValue : text;
    }

    private String valueOrEnv(Map<String, ?> config, String key, String envName) {
        Object value = config.get(key);
        if (value == null) {
            return System.getenv(envName);
        }
        String text = String.valueOf(value);
        return text.isBlank() ? System.getenv(envName) : text;
    }

    private Duration duration(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        String text = String.valueOf(value).trim();
        if (text.endsWith("ms")) {
            return Duration.ofMillis(Long.parseLong(text.substring(0, text.length() - 2)));
        }
        if (text.endsWith("s")) {
            return Duration.ofSeconds(Long.parseLong(text.substring(0, text.length() - 1)));
        }
        if (text.startsWith("P")) {
            return Duration.parse(text);
        }
        return Duration.ofSeconds(Long.parseLong(text));
    }

    private Double doubleValue(Object value) {
        return value == null || String.valueOf(value).isBlank() ? null : Double.valueOf(String.valueOf(value));
    }

    private Integer integerValue(Object value) {
        return value == null || String.valueOf(value).isBlank() ? null : Integer.valueOf(String.valueOf(value));
    }
}
