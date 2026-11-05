package com.consilens.connector.trino;

import com.consilens.connector.api.ConnectorTypes;
import com.consilens.connector.api.DatabaseDialect;
import com.consilens.connector.api.DatabaseDialectProvider;

import java.util.Map;

public class TrinoDatabaseDialectProvider implements DatabaseDialectProvider {

    @Override
    public String getConnectorType() {
        return ConnectorTypes.TRINO;
    }

    @Override
    public boolean supportsJdbcUrl(String jdbcUrl) {
        return jdbcUrl.contains("trino");
    }

    @Override
    public int getJdbcUrlMatchPriority() {
        return 160;
    }

    @Override
    public DatabaseDialect create() {
        return new TrinoDatabaseDialect();
    }

    @Override
    public DatabaseDialect create(Map<String, ?> normalizationConfig) {
        return new TrinoDatabaseDialect(normalizationConfig);
    }
}
