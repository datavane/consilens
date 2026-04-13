package com.consilens.connector.sqlite;

import com.consilens.connector.api.DatabaseDialect;
import com.consilens.connector.api.DatabaseDialectProvider;
import com.consilens.connector.api.enums.DatabaseType;

import java.util.Map;

public class SQLiteDatabaseDialectProvider implements DatabaseDialectProvider {

    @Override
    public DatabaseType getDatabaseType() {
        return DatabaseType.SQLITE;
    }

    @Override
    public DatabaseDialect create() {
        return new SQLiteDatabaseDialect();
    }

    @Override
    public DatabaseDialect create(Map<String, ?> normalizationConfig) {
        return new SQLiteDatabaseDialect(normalizationConfig);
    }
}
