package com.consilens.connector.postgresql;

import com.consilens.connector.api.DatabaseDialect;
import com.consilens.connector.api.DatabaseDialectProvider;

import java.util.Map;

public class PostgreSQLDatabaseDialectProvider implements DatabaseDialectProvider {

    @Override
    public String getConnectorType() {
        return "postgresql";
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
