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
        String key = buildKey(format, type);
        SinkProvider provider = providers.get(key);
        if (provider == null) {
            throw new IllegalArgumentException(
                    "No SinkProvider found for format=" + format + ", type=" + type
                            + ". Available: " + providers.keySet());
        }
        return provider.create();
    }

    public boolean supports(String format, String type) {
        return providers.containsKey(buildKey(format, type));
    }

    private String buildKey(String format, String type) {
        return format.toLowerCase() + ":" + type.toLowerCase();
    }
}
