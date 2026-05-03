package com.consilens.connector.api;

import java.util.Locale;
import java.util.Optional;
import java.util.ServiceLoader;

/**
 * Resolves {@link DatabaseDialect} instances through Java SPI.
 */
public final class DatabaseDialects {

    private DatabaseDialects() {
    }

    public static Optional<DatabaseDialect> find(String connectorType) {
        if (connectorType == null || connectorType.isBlank()) {
            return Optional.empty();
        }
        String normalized = connectorType.trim().toLowerCase(Locale.ROOT);
        ServiceLoader<DatabaseDialectProvider> loader = ServiceLoader.load(DatabaseDialectProvider.class);
        for (DatabaseDialectProvider provider : loader) {
            if (normalized.equals(provider.getConnectorType())) {
                return Optional.of(provider.create());
            }
        }
        return Optional.empty();
    }

    public static DatabaseDialect require(String connectorType) {
        return find(connectorType)
                .orElseThrow(() -> new IllegalArgumentException("No DatabaseDialectProvider found for connectorType=" + connectorType));
    }
}
