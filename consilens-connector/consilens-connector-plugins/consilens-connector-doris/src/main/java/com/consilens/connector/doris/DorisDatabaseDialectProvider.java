package com.consilens.connector.doris;

import com.consilens.connector.api.DatabaseDialect;
import com.consilens.connector.api.DatabaseDialectProvider;

import java.util.Map;

public class DorisDatabaseDialectProvider implements DatabaseDialectProvider {

    @Override
    public String getConnectorType() {
        return "doris";
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
