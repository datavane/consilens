package com.consilens.ai.spi;

import com.consilens.spi.PluginManager;

import java.util.Map;

/**
 * Manages {@link AIAnalyzer} instances loaded via SPI.
 */
public class AIAnalyzerManager {

    private static final String MANAGER_NAME = "ai-analyzer";

    private static final AIAnalyzerManager INSTANCE = new AIAnalyzerManager();

    private final PluginManager<String, AIAnalyzerProvider, AIAnalyzer> pluginManager;

    private AIAnalyzerManager() {
        pluginManager = PluginManager.load(
                AIAnalyzerProvider.class,
                AIAnalyzerProvider::getName,
                AIAnalyzerProvider::create,
                AIAnalyzerProvider::create,
                MANAGER_NAME
        );
    }

    public static AIAnalyzerManager getInstance() {
        return INSTANCE;
    }

    /**
     * Returns the named analyzer, using a shared singleton instance.
     */
    public AIAnalyzer get(String name) {
        return pluginManager.get(name);
    }

    /**
     * Creates a fresh analyzer instance for the given name.
     */
    public AIAnalyzer create(String name) {
        return pluginManager.create(name);
    }

    /**
     * Creates a configured analyzer instance.
     */
    public AIAnalyzer create(String name, Map<String, ?> config) {
        return pluginManager.create(name, config);
    }
}
