package com.consilens.core.compare.registry;

import com.consilens.connector.api.ConnectorException;
import com.consilens.connector.api.config.ConnectorConfig;
import com.consilens.connector.api.spi.ConnectorAdapter;
import com.consilens.connector.api.spi.ConnectorProvider;
import com.consilens.connector.api.spi.ConnectorRegistry;
import com.consilens.spi.KeyedRegistry;

import java.util.Locale;
import java.util.Optional;

public class DefaultConnectorRegistry implements ConnectorRegistry {

    private static volatile KeyedRegistry<String, ConnectorProvider> registry;

    @Override
    public ConnectorAdapter create(ConnectorConfig config) throws ConnectorException {
        ConnectorProvider provider = resolveProvider(config)
                .orElseThrow(() -> new ConnectorException("Unsupported connector configuration: " + describe(config)));
        return provider.create(withResolvedType(config, provider.getType()));
    }

    @Override
    public Optional<ConnectorProvider> findProvider(String type) {
        if (type == null || type.trim().isEmpty()) {
            return Optional.empty();
        }
        ensureInitialized();
        return Optional.ofNullable(registry.find(normalizeType(type)));
    }

    private static void ensureInitialized() {
        if (registry == null) {
            synchronized (DefaultConnectorRegistry.class) {
                if (registry == null) {
                    registry = KeyedRegistry.load(
                            ConnectorProvider.class,
                            provider -> normalizeType(provider.getType()),
                            "ConnectorProvider");
                }
            }
        }
    }

    private Optional<ConnectorProvider> resolveProvider(ConnectorConfig config) {
        ensureInitialized();

        String explicitType = config != null ? normalizeType(config.getType()) : null;
        if (explicitType != null && !explicitType.isEmpty()) {
            ConnectorProvider provider = registry.find(explicitType);
            if (provider != null) {
                return Optional.of(provider);
            }
        }

        for (ConnectorProvider provider : registry.providers()) {
            if (provider.supports(config)) {
                return Optional.of(provider);
            }
        }
        return Optional.empty();
    }

    private String describe(ConnectorConfig config) {
        if (config == null) {
            return "<null>";
        }
        String type = config.getType();
        Object url = config.getConnection() != null ? config.getConnection().get("url") : null;
        return "type=" + type + ", url=" + url;
    }

    private ConnectorConfig withResolvedType(ConnectorConfig config, String resolvedType) {
        if (config == null) {
            return null;
        }
        if (config.getType() != null && !config.getType().trim().isEmpty()) {
            return config;
        }
        return ConnectorConfig.builder()
                .type(resolvedType)
                .name(config.getName())
                .connection(config.getConnection())
                .resource(config.getResource())
                .readOptions(config.getReadOptions())
                .build();
    }

    private static String normalizeType(String type) {
        return type == null ? null : type.trim().toLowerCase(Locale.ROOT);
    }
}
