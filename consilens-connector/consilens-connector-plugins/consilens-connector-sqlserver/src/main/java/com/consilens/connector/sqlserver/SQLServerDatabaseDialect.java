package com.consilens.connector.sqlserver;

import com.consilens.connector.api.*;
import com.consilens.conncetor.base.AbstractDatabaseDialect;

import java.util.Map;

/**
 * SQL Server database dialect implementation.
 */
public class SQLServerDatabaseDialect extends AbstractDatabaseDialect {

    private final SQLServerCapabilityProvider capabilityProvider;
    private final SQLServerSqlQueryGenerator sqlQueryGenerator;
    private final SQLServerMetadataQueryGenerator metadataQueryGenerator;
    private final SQLServerDataTypeHandler dataTypeHandler;
    private final SQLServerConnectionPoolOptimizer connectionPoolOptimizer;

    public SQLServerDatabaseDialect() {
        this(null);
    }
    
    /**
     * Constructs a new SQL Server database dialect with normalization configuration.
     * 
     * @param normalizationConfig normalization configuration map
     */
    public SQLServerDatabaseDialect(Map<String, ?> normalizationConfig) {
        this.capabilityProvider = new SQLServerCapabilityProvider();
        this.dataTypeHandler = new SQLServerDataTypeHandler(capabilityProvider, normalizationConfig);
        this.sqlQueryGenerator = new SQLServerSqlQueryGenerator(capabilityProvider, dataTypeHandler);
        this.metadataQueryGenerator = new SQLServerMetadataQueryGenerator(capabilityProvider);
        this.connectionPoolOptimizer = new SQLServerConnectionPoolOptimizer();
    }

    @Override
    public String getConnectorType() {
        return "sqlserver";
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

    @Override
    public ConnectionPoolOptimizer getConnectionPoolOptimizer() {
        return connectionPoolOptimizer;
    }
}
