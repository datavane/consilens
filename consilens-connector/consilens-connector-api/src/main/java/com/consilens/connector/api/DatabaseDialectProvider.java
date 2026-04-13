package com.consilens.connector.api;

import com.consilens.connector.api.enums.DatabaseType;

import java.util.Map;

public interface DatabaseDialectProvider {

    DatabaseType getDatabaseType();

    DatabaseDialect create();

    default DatabaseDialect create(Map<String, ?> normalizationConfig) {
        return create();
    }
}
