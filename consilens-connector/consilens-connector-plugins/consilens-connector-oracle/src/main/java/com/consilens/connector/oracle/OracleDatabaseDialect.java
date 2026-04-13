package com.consilens.connector.oracle;

import com.consilens.connector.api.*;
import com.consilens.connector.api.enums.DatabaseType;
import com.consilens.conncetor.base.AbstractDatabaseDialect;

import java.util.Map;

/**
 * Oracle database dialect implementation.
 */
public class OracleDatabaseDialect extends AbstractDatabaseDialect {

    private final OracleCapabilityProvider capabilityProvider;
    private final OracleSqlQueryGenerator sqlQueryGenerator;
    private final OracleMetadataQueryGenerator metadataQueryGenerator;
    private final OracleDataTypeHandler dataTypeHandler;
    private final OracleConnectionPoolOptimizer connectionPoolOptimizer;

    public OracleDatabaseDialect() {
        this(null);
    }
    
    /**
     * Constructs a new Oracle database dialect with normalization configuration.
     * 
     * @param normalizationConfig normalization configuration map
     */
    public OracleDatabaseDialect(Map<String, ?> normalizationConfig) {
        this.capabilityProvider = new OracleCapabilityProvider();
        this.dataTypeHandler = new OracleDataTypeHandler(capabilityProvider, normalizationConfig);
        this.sqlQueryGenerator = new OracleSqlQueryGenerator(capabilityProvider, dataTypeHandler);
        this.metadataQueryGenerator = new OracleMetadataQueryGenerator(capabilityProvider);
        this.connectionPoolOptimizer = new OracleConnectionPoolOptimizer();
    }

    @Override
    public DatabaseType getDatabaseType() {
        return DatabaseType.ORACLE;
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
