package com.consilens.connector.tidb;

import com.consilens.connector.api.ConnectorTypes;
import com.consilens.connector.api.DatabaseDialect;
import com.consilens.connector.api.DatabaseDialectProvider;

import java.util.Map;

public class TiDBDatabaseDialectProvider implements DatabaseDialectProvider {

    @Override
    public String getConnectorType() {
        return ConnectorTypes.TIDB;
    }

    @Override
    public boolean supportsJdbcUrl(String jdbcUrl) {
        return jdbcUrl.contains("tidb")
                || (jdbcUrl.contains("mysql") && jdbcUrl.contains(":4000"));
    }

    @Override
    public int getJdbcUrlMatchPriority() {
        return 10;
    }

    @Override
    public DatabaseDialect create() {
        return new TiDBDatabaseDialect();
    }

    @Override
    public DatabaseDialect create(Map<String, ?> normalizationConfig) {
        return new TiDBDatabaseDialect(normalizationConfig);
    }
}
