package com.consilens.connector.trino;

import com.consilens.connector.api.*;
import com.consilens.conncetor.base.AbstractDatabaseDialect;

import java.util.Map;

/**
 * Trino database dialect implementation.
 */
public class TrinoDatabaseDialect extends AbstractDatabaseDialect {

    private final TrinoCapabilityProvider capabilityProvider;
    private final TrinoSqlQueryGenerator sqlQueryGenerator;
    private final TrinoMetadataQueryGenerator metadataQueryGenerator;
    private final TrinoDataTypeHandler dataTypeHandler;

    public TrinoDatabaseDialect() {
        this(null);
    }
    
    /**
     * Constructs a new Trino database dialect with normalization configuration.
     * 
     * @param normalizationConfig normalization configuration map
     */
    public TrinoDatabaseDialect(Map<String, ?> normalizationConfig) {
        this.capabilityProvider = new TrinoCapabilityProvider();
        this.dataTypeHandler = new TrinoDataTypeHandler(capabilityProvider, normalizationConfig);
        this.sqlQueryGenerator = new TrinoSqlQueryGenerator(capabilityProvider, dataTypeHandler);
        this.metadataQueryGenerator = new TrinoMetadataQueryGenerator(capabilityProvider);
    }

    @Override
    public String getConnectorType() {
        return "trino";
    }

    @Override
    public CapabilityProvider getCapabilityProvider() {
        return capabilityProvider;
    }

    @Override
    public SqlQueryGenerator getSqlQueryGenerator() {
        return sqlQueryGenerator;
    }

    @Override
    public MetadataQueryGenerator getMetadataQueryGenerator() {
        return metadataQueryGenerator;
    }

    @Override
    public DataTypeHandler getDataTypeHandler() {
        return dataTypeHandler;
    }
}
