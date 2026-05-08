package com.consilens.ai.backend;

import com.consilens.ai.spi.LLMBackend;
import com.consilens.ai.spi.LLMBackendProvider;

import java.util.Map;

/**
 * SPI provider for the DeepSeek backend.
 */
public class DeepSeekBackendProvider implements LLMBackendProvider {

    private static final String KEY_BASE_URL = "baseUrl";
    private static final String KEY_MODEL = "model";
    private static final String KEY_API_KEY = "apiKey";

    @Override
    public String getName() {
        return "deepseek";
    }

    @Override
    public LLMBackend create() {
        return new DeepSeekBackend();
    }

    @Override
    public LLMBackend create(Map<String, ?> config) {
        if (config == null || config.isEmpty()) {
            return create();
        }
        String baseUrl = valueOrDefault(config, KEY_BASE_URL, "https://api.deepseek.com");
        String model = valueOrDefault(config, KEY_MODEL, "deepseek-chat");
        String apiKey = valueOrEnv(config, KEY_API_KEY, "DEEPSEEK_API_KEY");
        return new DeepSeekBackend(baseUrl, model, apiKey);
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
}
