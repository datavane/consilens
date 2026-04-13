package com.consilens.connector.doris;

import com.consilens.connector.api.*;
import com.consilens.connector.api.enums.DatabaseType;
import com.consilens.conncetor.base.AbstractDatabaseDialect;

import java.util.Map;

/**
 * Doris database dialect implementation.
 * 
 * <p>
 * Provides Doris-specific implementations of all dialect components through
 * composition pattern:
 * <ul>
 * <li>{@link DorisCapabilityProvider} - Doris features and capabilities</li>
 * <li>{@link DorisSqlQueryGenerator} - Doris SQL generation</li>
 * <li>{@link DorisMetadataQueryGenerator} - Doris metadata queries</li>
 * <li>{@link DorisDataTypeHandler} - Doris data type handling</li>
 * </ul>
 * 
 * <p>
 * This dialect is typically created by {@link DorisDatabaseDialectProvider}
 * and discovered via JDK {@code ServiceLoader}.
 * 
 * <pre>{@code
 * DatabaseDialect dialect = DialectFactory.getDialect(DatabaseType.DORIS);
 * }</pre>
 * 
 * @since 1.0.0
 */
public class DorisDatabaseDialect extends AbstractDatabaseDialect {

    private final DorisCapabilityProvider capabilityProvider;
    private final DorisSqlQueryGenerator sqlQueryGenerator;
    private final DorisMetadataQueryGenerator metadataQueryGenerator;
    private final DorisDataTypeHandler dataTypeHandler;

    /**
     * Constructs a new Doris database dialect.
     * Initializes all Doris-specific components.
     */
    public DorisDatabaseDialect() {
        this(null);
    }
    
    /**
     * Constructs a new Doris database dialect with normalization configuration.
     * 
     * @param normalizationConfig normalization configuration map
     */
    public DorisDatabaseDialect(Map<String, ?> normalizationConfig) {
        // Initialize Doris-specific components
        this.capabilityProvider = new DorisCapabilityProvider();
        this.dataTypeHandler = new DorisDataTypeHandler(capabilityProvider, normalizationConfig);
        this.sqlQueryGenerator = new DorisSqlQueryGenerator(capabilityProvider, dataTypeHandler);
        this.metadataQueryGenerator = new DorisMetadataQueryGenerator(capabilityProvider);
    }

    @Override
    public DatabaseType getDatabaseType() {
        return DatabaseType.DORIS;
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

    // TransactionManager uses the default implementation from
    // AbstractDatabaseDialect
    // No need to override getTransactionManager()
}
