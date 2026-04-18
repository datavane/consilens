package com.consilens.connector.api;

import java.util.Map;

public interface DatabaseDialectProvider {

    /**
     * Get the connector type identifier this provider handles (e.g. "mysql", "postgresql").
     *
     * @return lowercase connector type string
     */
    String getConnectorType();

    DatabaseDialect create();

    default DatabaseDialect create(Map<String, ?> normalizationConfig) {
        return create();
    }
}
