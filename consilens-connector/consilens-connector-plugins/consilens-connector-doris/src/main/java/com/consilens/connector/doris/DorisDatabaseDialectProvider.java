package com.consilens.connector.doris;

import com.consilens.connector.api.ConnectorTypes;
import com.consilens.connector.api.DatabaseDialect;
import com.consilens.connector.api.DatabaseDialectProvider;

import java.util.Map;

public class DorisDatabaseDialectProvider implements DatabaseDialectProvider {

    @Override
    public String getConnectorType() {
        return ConnectorTypes.DORIS;
    }

    @Override
    public boolean supportsJdbcUrl(String jdbcUrl) {
        return jdbcUrl.contains("doris");
    }

    @Override
    public int getJdbcUrlMatchPriority() {
        return 20;
    }

    @Override
    public DatabaseDialect create() {
        return new DorisDatabaseDialect();
    }

    @Override
    public DatabaseDialect create(Map<String, ?> normalizationConfig) {
        return new DorisDatabaseDialect(normalizationConfig);
    }
}
