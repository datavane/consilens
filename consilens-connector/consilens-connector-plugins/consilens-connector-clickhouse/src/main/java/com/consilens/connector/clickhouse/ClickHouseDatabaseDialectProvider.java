package com.consilens.connector.clickhouse;

import com.consilens.connector.api.ConnectorTypes;
import com.consilens.connector.api.DatabaseDialect;
import com.consilens.connector.api.DatabaseDialectProvider;

import java.util.Map;

public class ClickHouseDatabaseDialectProvider implements DatabaseDialectProvider {

    @Override
    public String getConnectorType() {
        return ConnectorTypes.CLICKHOUSE;
    }

    @Override
    public boolean supportsJdbcUrl(String jdbcUrl) {
        return jdbcUrl.contains("clickhouse");
    }

    @Override
    public int getJdbcUrlMatchPriority() {
        return 140;
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
