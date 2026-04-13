package com.consilens.connector.tidb;

import com.consilens.connector.api.*;
import com.consilens.connector.api.enums.DatabaseType;
import com.consilens.conncetor.base.AbstractDatabaseDialect;

import java.util.Map;

/**
 * TiDB database dialect implementation.
 * 
 * <p>
 * Provides TiDB-specific components for SQL generation, metadata retrieval,
 * and data type handling.
 * </p>
 * 
 * @since 1.0.0
 */
public class TiDBDatabaseDialect extends AbstractDatabaseDialect {

    private final TiDBCapabilityProvider capabilityProvider;
    private final TiDBDataTypeHandler dataTypeHandler;
    private final TiDBSqlQueryGenerator sqlQueryGenerator;
    private final TiDBMetadataQueryGenerator metadataQueryGenerator;
    
    /**
     * Constructs a new TiDB database dialect.
     */
    public TiDBDatabaseDialect() {
        this(null);
    }
    
    /**
     * Constructs a new TiDB database dialect with normalization configuration.
     * 
     * @param normalizationConfig normalization configuration map
     */
    public TiDBDatabaseDialect(Map<String, ?> normalizationConfig) {
        this.capabilityProvider = new TiDBCapabilityProvider();
        this.dataTypeHandler = new TiDBDataTypeHandler(capabilityProvider, normalizationConfig);
        this.sqlQueryGenerator = new TiDBSqlQueryGenerator(capabilityProvider, dataTypeHandler);
        this.metadataQueryGenerator = new TiDBMetadataQueryGenerator(capabilityProvider);
    }

    @Override
    public DatabaseType getDatabaseType() {
        return DatabaseType.TIDB;
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

    @Override
    public CapabilityProvider getCapabilityProvider() {
        return capabilityProvider;
    }
}
