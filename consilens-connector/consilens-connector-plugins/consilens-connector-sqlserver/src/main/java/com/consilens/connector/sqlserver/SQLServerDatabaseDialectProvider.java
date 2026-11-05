package com.consilens.connector.sqlserver;

import com.consilens.connector.api.ConnectorTypes;
import com.consilens.connector.api.DatabaseDialect;
import com.consilens.connector.api.DatabaseDialectProvider;

import java.util.Map;

public class SQLServerDatabaseDialectProvider implements DatabaseDialectProvider {

    @Override
    public String getConnectorType() {
        return ConnectorTypes.SQLSERVER;
    }

    @Override
    public boolean supportsJdbcUrl(String jdbcUrl) {
        return jdbcUrl.contains("sqlserver");
    }

    @Override
    public int getJdbcUrlMatchPriority() {
        return 70;
    }

    @Override
    public DatabaseDialect create() {
        return new SQLServerDatabaseDialect();
    }

    @Override
    public DatabaseDialect create(Map<String, ?> normalizationConfig) {
        return new SQLServerDatabaseDialect(normalizationConfig);
    }
}
