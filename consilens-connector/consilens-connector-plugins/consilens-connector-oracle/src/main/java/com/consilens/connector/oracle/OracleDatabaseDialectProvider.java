package com.consilens.connector.oracle;

import com.consilens.connector.api.DatabaseDialect;
import com.consilens.connector.api.DatabaseDialectProvider;

import java.util.Map;

public class OracleDatabaseDialectProvider implements DatabaseDialectProvider {

    @Override
    public String getConnectorType() {
        return "oracle";
    }

    @Override
    public DatabaseDialect create() {
        return new OracleDatabaseDialect();
    }

    @Override
    public DatabaseDialect create(Map<String, ?> normalizationConfig) {
        return new OracleDatabaseDialect(normalizationConfig);
    }
}
