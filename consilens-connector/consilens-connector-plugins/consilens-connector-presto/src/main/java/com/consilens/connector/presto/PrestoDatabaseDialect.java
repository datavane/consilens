package com.consilens.connector.presto;

import com.consilens.connector.api.*;
import com.consilens.connector.api.enums.DatabaseType;
import com.consilens.conncetor.base.AbstractDatabaseDialect;

import java.util.Map;

/**
 * Presto database dialect implementation.
 */
public class PrestoDatabaseDialect extends AbstractDatabaseDialect {

    private final PrestoCapabilityProvider capabilityProvider;
    private final PrestoSqlQueryGenerator sqlQueryGenerator;
    private final PrestoMetadataQueryGenerator metadataQueryGenerator;
    private final PrestoDataTypeHandler dataTypeHandler;

    public PrestoDatabaseDialect() {
        this(null);
    }
    
    /**
     * Constructs a new Presto database dialect with normalization configuration.
     * 
     * @param normalizationConfig normalization configuration map
     */
    public PrestoDatabaseDialect(Map<String, ?> normalizationConfig) {
        this.capabilityProvider = new PrestoCapabilityProvider();
        this.dataTypeHandler = new PrestoDataTypeHandler(capabilityProvider, normalizationConfig);
        this.sqlQueryGenerator = new PrestoSqlQueryGenerator(capabilityProvider, dataTypeHandler);
        this.metadataQueryGenerator = new PrestoMetadataQueryGenerator(capabilityProvider);
    }

    @Override
    public DatabaseType getDatabaseType() {
        return DatabaseType.PRESTO;
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
