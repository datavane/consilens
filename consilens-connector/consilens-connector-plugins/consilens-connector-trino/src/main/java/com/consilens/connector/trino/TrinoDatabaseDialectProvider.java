package com.consilens.connector.trino;

import com.consilens.connector.api.DatabaseDialect;
import com.consilens.connector.api.DatabaseDialectProvider;

import java.util.Map;

public class TrinoDatabaseDialectProvider implements DatabaseDialectProvider {

    @Override
    public String getConnectorType() {
        return "trino";
    }

    @Override
    public DatabaseDialect create() {
        return new TrinoDatabaseDialect();
    }

    @Override
    public DatabaseDialect create(Map<String, ?> normalizationConfig) {
        return new TrinoDatabaseDialect(normalizationConfig);
    }
}
