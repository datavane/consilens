package com.consilens.connector.starrocks;

import com.consilens.connector.api.DatabaseDialect;
import com.consilens.connector.api.DatabaseDialectProvider;
import com.consilens.connector.api.enums.DatabaseType;

import java.util.Map;

public class StarRocksDatabaseDialectProvider implements DatabaseDialectProvider {

    @Override
    public DatabaseType getDatabaseType() {
        return DatabaseType.STARROCKS;
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
