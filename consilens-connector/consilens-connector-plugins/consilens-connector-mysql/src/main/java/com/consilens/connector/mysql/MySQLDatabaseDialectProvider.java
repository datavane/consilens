package com.consilens.connector.mysql;

import com.consilens.connector.api.DatabaseDialect;
import com.consilens.connector.api.DatabaseDialectProvider;

import java.util.Map;

public class MySQLDatabaseDialectProvider implements DatabaseDialectProvider {

    @Override
    public String getConnectorType() {
        return "mysql";
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
