package com.consilens.ai.backend;

import com.consilens.ai.spi.LLMBackend;
import com.consilens.ai.spi.LLMBackendProvider;

import java.util.Map;

/**
 * SPI provider for the Ollama backend.
 */
public class OllamaBackendProvider implements LLMBackendProvider {

    private static final String KEY_BASE_URL = "baseUrl";
    private static final String KEY_MODEL = "model";

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
        return new OllamaBackend(baseUrl, model);
    }
}
