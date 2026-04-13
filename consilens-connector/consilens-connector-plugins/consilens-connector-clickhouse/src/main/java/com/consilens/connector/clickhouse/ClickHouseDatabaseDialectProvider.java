package com.consilens.connector.clickhouse;

import com.consilens.connector.api.DatabaseDialect;
import com.consilens.connector.api.DatabaseDialectProvider;
import com.consilens.connector.api.enums.DatabaseType;

import java.util.Map;

public class ClickHouseDatabaseDialectProvider implements DatabaseDialectProvider {

    @Override
    public DatabaseType getDatabaseType() {
        return DatabaseType.CLICKHOUSE;
    }

    @Override
    public DatabaseDialect create() {
        return new ClickHouseDatabaseDialect();
    }

    @Override
    public DatabaseDialect create(Map<String, ?> normalizationConfig) {
        return new ClickHouseDatabaseDialect(normalizationConfig);
    }
}
