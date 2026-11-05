package com.consilens.connector.postgresql;

import com.consilens.connector.api.ConnectorTypes;
import com.consilens.connector.api.DatabaseDialect;
import com.consilens.connector.api.DatabaseDialectProvider;

import java.util.Map;

public class PostgreSQLDatabaseDialectProvider implements DatabaseDialectProvider {

    @Override
    public String getConnectorType() {
        return ConnectorTypes.POSTGRESQL;
    }

    @Override
    public boolean supportsJdbcUrl(String jdbcUrl) {
        return jdbcUrl.contains("postgresql") || jdbcUrl.contains("postgres");
    }

    @Override
    public int getJdbcUrlMatchPriority() {
        return 50;
    }

    @Override
    public DatabaseDialect create() {
        return new PostgreSQLDatabaseDialect();
    }

    @Override
    public DatabaseDialect create(Map<String, ?> normalizationConfig) {
        return new PostgreSQLDatabaseDialect(normalizationConfig);
    }
}
