package com.consilens.ai.spi;

import java.util.Map;

/**
 * SPI provider interface for creating {@link AIAnalyzer} instances.
 */
public interface AIAnalyzerProvider {

    /**
     * Returns the unique name identifying this provider.
     */
    String getName();

    /**
     * Creates a new {@link AIAnalyzer} with default configuration.
     */
    AIAnalyzer create();

    /**
     * Creates a new {@link AIAnalyzer} with the given configuration.
     *
     * @param config configuration key-value pairs
     */
    AIAnalyzer create(Map<String, ?> config);
}
