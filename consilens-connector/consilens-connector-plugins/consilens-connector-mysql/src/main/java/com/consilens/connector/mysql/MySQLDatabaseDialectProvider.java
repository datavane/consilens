package com.consilens.connector.mysql;

import com.consilens.connector.api.DatabaseDialect;
import com.consilens.connector.api.DatabaseDialectProvider;
import com.consilens.connector.api.enums.DatabaseType;

import java.util.Map;

public class MySQLDatabaseDialectProvider implements DatabaseDialectProvider {

    @Override
    public DatabaseType getDatabaseType() {
        return DatabaseType.MYSQL;
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
