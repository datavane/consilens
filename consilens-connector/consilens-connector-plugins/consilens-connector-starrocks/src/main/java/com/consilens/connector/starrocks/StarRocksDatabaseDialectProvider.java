package com.consilens.connector.starrocks;

import com.consilens.connector.api.ConnectorTypes;
import com.consilens.connector.api.DatabaseDialect;
import com.consilens.connector.api.DatabaseDialectProvider;

import java.util.Map;

public class StarRocksDatabaseDialectProvider implements DatabaseDialectProvider {

    @Override
    public String getConnectorType() {
        return ConnectorTypes.STARROCKS;
    }

    @Override
    public boolean supportsJdbcUrl(String jdbcUrl) {
        return jdbcUrl.contains("starrocks")
                || (jdbcUrl.contains("mysql") && jdbcUrl.contains(":9030"));
    }

    @Override
    public int getJdbcUrlMatchPriority() {
        return 30;
    }

    @Override
    public DatabaseDialect create() {
        return new StarRocksDatabaseDialect();
    }

    @Override
    public DatabaseDialect create(Map<String, ?> normalizationConfig) {
        return new StarRocksDatabaseDialect(normalizationConfig);
    }
}
