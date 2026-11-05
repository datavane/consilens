package com.consilens.sink.api;

import java.util.HashMap;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * Loads all SinkProviders via Java SPI and provides Sink instances by format+type.
 */
public class SinkRegistry {

    private final Map<String, SinkProvider> providers = new HashMap<>();

    public SinkRegistry() {
        ServiceLoader<SinkProvider> loader = ServiceLoader.load(SinkProvider.class);
        for (SinkProvider provider : loader) {
            String key = buildKey(provider.getFormat(), provider.getType());
            providers.put(key, provider);
        }
    }

    public Sink create(String format, String type) {
        if (!supports(format, type)) {
            throw new IllegalArgumentException(
                    "No SinkProvider found for format=" + format + ", type=" + type
                            + ". Available: " + providers.keySet());
        }
        return providers.get(buildKey(format, type)).create();
    }

    /**
     * Whether a provider is registered for the given format/type pair.
     */
    public boolean supports(String format, String type) {
        if (format == null || type == null) {
            return false;
        }
        return providers.containsKey(buildKey(format, type));
    }

    /**
     * Registered {@code format:type} keys, useful for configuration diagnostics.
     */
    public String availableKeys() {
        return providers.keySet().toString();
    }

    private String buildKey(String format, String type) {
        return format.trim().toLowerCase() + ":" + type.trim().toLowerCase();
    }
}
