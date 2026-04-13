package com.consilens.ai.backend;

import com.consilens.ai.spi.LLMBackend;
import com.consilens.ai.spi.LLMBackendProvider;

import java.util.Map;

/**
 * SPI provider for the no-op backend.
 */
public class NoopBackendProvider implements LLMBackendProvider {

    @Override
    public String getName() {
        return "noop";
    }

    @Override
    public LLMBackend create() {
        return new NoopBackend();
    }

    @Override
    public LLMBackend create(Map<String, ?> config) {
        return new NoopBackend();
    }
}
