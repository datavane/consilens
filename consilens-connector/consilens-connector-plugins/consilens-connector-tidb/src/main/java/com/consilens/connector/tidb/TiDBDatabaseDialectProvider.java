package com.consilens.connector.tidb;

import com.consilens.connector.api.DatabaseDialect;
import com.consilens.connector.api.DatabaseDialectProvider;
import com.consilens.connector.api.enums.DatabaseType;

import java.util.Map;

public class TiDBDatabaseDialectProvider implements DatabaseDialectProvider {

    @Override
    public DatabaseType getDatabaseType() {
        return DatabaseType.TIDB;
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
