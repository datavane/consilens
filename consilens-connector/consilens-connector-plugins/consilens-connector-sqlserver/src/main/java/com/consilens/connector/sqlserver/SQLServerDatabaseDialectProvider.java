package com.consilens.connector.sqlserver;

import com.consilens.connector.api.DatabaseDialect;
import com.consilens.connector.api.DatabaseDialectProvider;
import com.consilens.connector.api.enums.DatabaseType;

import java.util.Map;

public class SQLServerDatabaseDialectProvider implements DatabaseDialectProvider {

    @Override
    public DatabaseType getDatabaseType() {
        return DatabaseType.SQL_SERVER;
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
