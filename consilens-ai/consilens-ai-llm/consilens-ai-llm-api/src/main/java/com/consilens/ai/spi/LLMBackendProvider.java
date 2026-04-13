package com.consilens.ai.spi;

import java.util.Map;

/**
 * SPI provider interface for creating {@link LLMBackend} instances.
 */
public interface LLMBackendProvider {

    /**
     * Returns the unique name identifying this provider (e.g., "ollama", "noop").
     */
    String getName();

    /**
     * Creates a new {@link LLMBackend} with default configuration.
     */
    LLMBackend create();

    /**
     * Creates a new {@link LLMBackend} with the given configuration.
     *
     * @param config configuration key-value pairs
     */
    LLMBackend create(Map<String, ?> config);
}
