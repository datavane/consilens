package com.consilens.connector.clickhouse;

import com.consilens.connector.api.DatabaseDialect;
import com.consilens.connector.api.DatabaseDialectProvider;

import java.util.Map;

public class ClickHouseDatabaseDialectProvider implements DatabaseDialectProvider {

    @Override
    public String getConnectorType() {
        return "clickhouse";
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
