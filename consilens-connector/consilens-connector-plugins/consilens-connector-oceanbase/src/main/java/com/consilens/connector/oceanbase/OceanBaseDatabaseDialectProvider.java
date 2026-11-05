package com.consilens.connector.oceanbase;

import com.consilens.connector.api.ConnectorTypes;
import com.consilens.connector.api.DatabaseDialect;
import com.consilens.connector.api.DatabaseDialectProvider;

import java.util.Map;

/**
 * OceanBase database dialect provider.
 *
 * <p>
 * Creates OceanBase-specific dialect instances.
 * This provider is discovered via JDK {@code ServiceLoader}.
 * </p>
 *
 * @since 1.0.0
 */
public class OceanBaseDatabaseDialectProvider implements DatabaseDialectProvider {

    @Override
    public String getConnectorType() {
        return ConnectorTypes.OCEANBASE;
    }

    @Override
    public DatabaseDialect create() {
        return new OceanBaseDatabaseDialect();
    }

    @Override
    public DatabaseDialect create(Map<String, ?> normalizationConfig) {
        return new OceanBaseDatabaseDialect(normalizationConfig);
    }
}
