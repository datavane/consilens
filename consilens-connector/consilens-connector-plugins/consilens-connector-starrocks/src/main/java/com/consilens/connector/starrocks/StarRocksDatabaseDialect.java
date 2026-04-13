package com.consilens.connector.starrocks;

import com.consilens.connector.api.*;
import com.consilens.connector.api.enums.DatabaseType;
import com.consilens.conncetor.base.AbstractDatabaseDialect;

import java.util.Map;

/**
 * StarRocks database dialect implementation.
 * 
 * <p>
 * Provides StarRocks-specific implementations of all dialect components through
 * composition pattern:
 * <ul>
 * <li>{@link StarRocksCapabilityProvider} - StarRocks features and capabilities</li>
 * <li>{@link StarRocksSqlQueryGenerator} - StarRocks SQL generation</li>
 * <li>{@link StarRocksMetadataQueryGenerator} - StarRocks metadata queries</li>
 * <li>{@link StarRocksDataTypeHandler} - StarRocks data type handling</li>
 * </ul>
 * 
 * <p>
 * This dialect is typically created by {@link StarRocksDatabaseDialectProvider}
 * and discovered via JDK {@code ServiceLoader}.
 * 
 * <pre>{@code
 * DatabaseDialect dialect = DialectFactory.getDialect(DatabaseType.STARROCKS);
 * }</pre>
 * 
 * @since 1.0.0
 */
public class StarRocksDatabaseDialect extends AbstractDatabaseDialect {

    private final StarRocksCapabilityProvider capabilityProvider;
    private final StarRocksSqlQueryGenerator sqlQueryGenerator;
    private final StarRocksMetadataQueryGenerator metadataQueryGenerator;
    private final StarRocksDataTypeHandler dataTypeHandler;

    /**
     * Constructs a new StarRocks database dialect.
     * Initializes all StarRocks-specific components.
     */
    public StarRocksDatabaseDialect() {
        this(null);
    }
    
    /**
     * Constructs a new StarRocks database dialect with normalization configuration.
     * 
     * @param normalizationConfig normalization configuration map
     */
    public StarRocksDatabaseDialect(Map<String, ?> normalizationConfig) {
        // Initialize StarRocks-specific components
        this.capabilityProvider = new StarRocksCapabilityProvider();
        this.dataTypeHandler = new StarRocksDataTypeHandler(capabilityProvider, normalizationConfig);
        this.sqlQueryGenerator = new StarRocksSqlQueryGenerator(capabilityProvider, dataTypeHandler);
        this.metadataQueryGenerator = new StarRocksMetadataQueryGenerator(capabilityProvider);
    }

    @Override
    public DatabaseType getDatabaseType() {
        return DatabaseType.STARROCKS;
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
