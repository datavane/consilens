package com.consilens.connector.clickhouse;

import com.consilens.connector.api.*;
import com.consilens.conncetor.base.AbstractDatabaseDialect;

import java.util.Map;

/**
 * ClickHouse database dialect implementation.
 * 
 * <p>
 * Provides ClickHouse-specific implementations of all dialect components
 * through
 * composition pattern:
 * <ul>
 * <li>{@link ClickHouseCapabilityProvider} - ClickHouse features and
 * capabilities</li>
 * <li>{@link ClickHouseSqlQueryGenerator} - ClickHouse SQL generation</li>
 * <li>{@link ClickHouseMetadataQueryGenerator} - ClickHouse metadata
 * queries</li>
 * <li>{@link ClickHouseDataTypeHandler} - ClickHouse data type handling</li>
 * </ul>
 * 
 * <p>
 * This dialect is typically created by {@link ClickHouseDatabaseDialectProvider}
 * and discovered via JDK {@code ServiceLoader}.
 * 
 * <pre>{@code
 * DatabaseDialect dialect = DialectFactory.getDialect("LCLICKHOUSE");
 * }</pre>
 * 
 * @since 1.0.0
 */
public class ClickHouseDatabaseDialect extends AbstractDatabaseDialect {

    private final ClickHouseCapabilityProvider capabilityProvider;
    private final ClickHouseSqlQueryGenerator sqlQueryGenerator;
    private final ClickHouseMetadataQueryGenerator metadataQueryGenerator;
    private final ClickHouseDataTypeHandler dataTypeHandler;

    /**
     * Constructs a new ClickHouse database dialect.
     * Initializes all ClickHouse-specific components.
     */
    public ClickHouseDatabaseDialect() {
        this(null);
    }
    
    /**
     * Constructs a new ClickHouse database dialect with normalization configuration.
     * 
     * @param normalizationConfig normalization configuration map
     */
    public ClickHouseDatabaseDialect(Map<String, ?> normalizationConfig) {
        // Initialize ClickHouse-specific components
        this.capabilityProvider = new ClickHouseCapabilityProvider();
        this.dataTypeHandler = new ClickHouseDataTypeHandler(capabilityProvider, normalizationConfig);
        this.sqlQueryGenerator = new ClickHouseSqlQueryGenerator(capabilityProvider, dataTypeHandler);
        this.metadataQueryGenerator = new ClickHouseMetadataQueryGenerator(capabilityProvider);
    }

    @Override
    public String getConnectorType() {
        return "clickhouse";
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
