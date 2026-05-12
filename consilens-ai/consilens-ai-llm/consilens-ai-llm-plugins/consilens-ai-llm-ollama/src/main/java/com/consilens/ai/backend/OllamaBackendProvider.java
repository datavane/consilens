package com.consilens.ai.backend;

import com.consilens.ai.spi.LLMBackend;
import com.consilens.ai.spi.LLMBackendProvider;

import java.time.Duration;
import java.util.Map;

/**
 * SPI provider for the Ollama backend.
 */
public class OllamaBackendProvider implements LLMBackendProvider {

    private static final String KEY_BASE_URL = "baseUrl";
    private static final String KEY_MODEL = "model";
    private static final String KEY_TIMEOUT = "timeout";
    private static final String KEY_TEMPERATURE = "temperature";
    private static final String KEY_MAX_TOKENS = "maxTokens";

    @Override
    public String getName() {
        return "ollama";
    }

    @Override
    public LLMBackend create() {
        return new OllamaBackend();
    }

    @Override
    public LLMBackend create(Map<String, ?> config) {
        if (config == null || config.isEmpty()) {
            return create();
        }
        String baseUrl = config.containsKey(KEY_BASE_URL) ? String.valueOf(config.get(KEY_BASE_URL)) : "http://localhost:11434";
        String model = config.containsKey(KEY_MODEL) ? String.valueOf(config.get(KEY_MODEL)) : "qwen2.5:7b";
        return new OllamaBackend(baseUrl, model,
                duration(config.get(KEY_TIMEOUT)),
                doubleValue(config.get(KEY_TEMPERATURE)),
                integerValue(config.get(KEY_MAX_TOKENS)));
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
