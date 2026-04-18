package com.consilens.connector.starrocks;

import com.consilens.connector.api.DatabaseDialect;
import com.consilens.connector.api.DatabaseDialectProvider;

import java.util.Map;

public class StarRocksDatabaseDialectProvider implements DatabaseDialectProvider {

    @Override
    public String getConnectorType() {
        return "starrocks";
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
