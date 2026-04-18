package com.consilens.connector.tidb;

import com.consilens.connector.api.DatabaseDialect;
import com.consilens.connector.api.DatabaseDialectProvider;

import java.util.Map;

public class TiDBDatabaseDialectProvider implements DatabaseDialectProvider {

    @Override
    public String getConnectorType() {
        return "tidb";
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
