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

    /**
     * Whether this provider recognizes the given JDBC URL. Implementations
     * report their own URL scheme/token so the factory does not need a
     * database-specific if/else chain.
     *
     * @param jdbcUrl lowercase JDBC URL
     * @return true when this provider should handle the URL
     */
    default boolean supportsJdbcUrl(String jdbcUrl) {
        return false;
    }

    /**
     * Precedence when several providers recognize the same URL (lower wins).
     * The default keeps non-overlapping providers equal.
     *
     * @return match priority
     */
    default int getJdbcUrlMatchPriority() {
        return 100;
    }

    default DatabaseDialect create(Map<String, ?> normalizationConfig) {
        return create();
    }
}
