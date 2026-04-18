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
        String connectorType = config != null ? normalizeType(config.getType()) : null;
        if (connectorType == null || connectorType.isEmpty()) {
            throw new ConnectorException("Connector type is required");
        }
        ConnectorProvider provider = findProvider(connectorType)
                .orElseThrow(() -> new ConnectorException("Unsupported connector type: " + connectorType));
        return provider.create(config);
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

    private static String normalizeType(String type) {
        return type == null ? null : type.trim().toLowerCase(Locale.ROOT);
    }
}
