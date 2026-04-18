package com.consilens.connector.presto;

import com.consilens.connector.api.DatabaseDialect;
import com.consilens.connector.api.DatabaseDialectProvider;

import java.util.Map;

public class PrestoDatabaseDialectProvider implements DatabaseDialectProvider {

    @Override
    public String getConnectorType() {
        return "presto";
    }

    @Override
    public DatabaseDialect create() {
        return new PrestoDatabaseDialect();
    }

    @Override
    public DatabaseDialect create(Map<String, ?> normalizationConfig) {
        return new PrestoDatabaseDialect(normalizationConfig);
    }
}
