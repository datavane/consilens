package com.consilens.ai.spi;

import com.consilens.spi.PluginManager;

import java.util.Map;
import java.util.Set;

/**
 * Manages {@link LLMBackend} instances loaded via SPI.
 */
public class LLMBackendManager {

    private static final String MANAGER_NAME = "llm-backend";

    private static final LLMBackendManager INSTANCE = new LLMBackendManager();

    private final PluginManager<String, LLMBackendProvider, LLMBackend> pluginManager;

    private LLMBackendManager() {
        pluginManager = PluginManager.load(
                LLMBackendProvider.class,
                LLMBackendProvider::getName,
                LLMBackendProvider::create,
                LLMBackendProvider::create,
                MANAGER_NAME
        );
    }

    public static LLMBackendManager getInstance() {
        return INSTANCE;
    }

    /**
     * Returns the named backend using a shared singleton instance.
     */
    public LLMBackend get(String name) {
        return pluginManager.get(name);
    }

    /**
     * Creates a fresh backend instance.
     */
    public LLMBackend create(String name) {
        return pluginManager.create(name);
    }

    /**
     * Creates a configured backend instance.
     */
    public LLMBackend create(String name, Map<String, ?> config) {
        return pluginManager.create(name, config);
    }

    /**
     * Returns LLM backend provider names discovered from the classpath.
     */
    public Set<String> supportedNames() {
        return pluginManager.getSupportedKeys();
    }
}
