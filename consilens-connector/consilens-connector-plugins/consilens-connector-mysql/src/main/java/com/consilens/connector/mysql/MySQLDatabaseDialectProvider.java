package com.consilens.connector.mysql;

import com.consilens.connector.api.ConnectorTypes;
import com.consilens.connector.api.DatabaseDialect;
import com.consilens.connector.api.DatabaseDialectProvider;

import java.util.Map;

public class MySQLDatabaseDialectProvider implements DatabaseDialectProvider {

    @Override
    public String getConnectorType() {
        return ConnectorTypes.MYSQL;
    }

    @Override
    public boolean supportsJdbcUrl(String jdbcUrl) {
        return jdbcUrl.contains("mysql");
    }

    @Override
    public int getJdbcUrlMatchPriority() {
        return 40;
    }

    @Override
    public DatabaseDialect create() {
        return new MySQLDatabaseDialect();
    }

    @Override
    public DatabaseDialect create(Map<String, ?> normalizationConfig) {
        return new MySQLDatabaseDialect(normalizationConfig);
    }
}
