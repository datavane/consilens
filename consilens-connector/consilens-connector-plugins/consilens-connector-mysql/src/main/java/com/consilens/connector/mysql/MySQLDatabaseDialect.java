package com.consilens.connector.mysql;

import com.consilens.connector.api.*;
import com.consilens.connector.api.enums.DatabaseType;
import com.consilens.conncetor.base.AbstractDatabaseDialect;

import java.util.Map;

/**
 * MySQL database dialect implementation.
 * 
 * <p>
 * Provides MySQL-specific implementations of all dialect components through
 * composition pattern:
 * <ul>
 * <li>{@link MySQLCapabilityProvider} - MySQL features and capabilities</li>
 * <li>{@link MySQLSqlQueryGenerator} - MySQL SQL generation</li>
 * <li>{@link MySQLMetadataQueryGenerator} - MySQL metadata queries</li>
 * <li>{@link MySQLDataTypeHandler} - MySQL data type handling</li>
 * </ul>
 * 
 * <p>
 * This dialect is typically created by {@link MySQLDatabaseDialectProvider}
 * and discovered via JDK {@code ServiceLoader}.
 * 
 * <pre>{@code
 * DatabaseDialect dialect = DialectFactory.getDialect(DatabaseType.MYSQL);
 * }</pre>
 * 
 * @since 1.0.0
 */
public class MySQLDatabaseDialect extends AbstractDatabaseDialect {

    private final MySQLCapabilityProvider capabilityProvider;
    private final MySQLSqlQueryGenerator sqlQueryGenerator;
    private final MySQLMetadataQueryGenerator metadataQueryGenerator;
    private final MySQLDataTypeHandler dataTypeHandler;
    private final MySQLConnectionPoolOptimizer connectionPoolOptimizer;

    /**
     * Constructs a new MySQL database dialect.
     * Initializes all MySQL-specific components.
     */
    public MySQLDatabaseDialect() {
        this(null);
    }
    
    /**
     * Constructs a new MySQL database dialect with normalization configuration.
     * 
     * @param normalizationConfig normalization configuration map
     */
    public MySQLDatabaseDialect(Map<String, ?> normalizationConfig) {
        // Initialize MySQL-specific components
        this.capabilityProvider = new MySQLCapabilityProvider();
        this.dataTypeHandler = new MySQLDataTypeHandler(capabilityProvider, normalizationConfig);
        this.sqlQueryGenerator = new MySQLSqlQueryGenerator(capabilityProvider, dataTypeHandler);
        this.metadataQueryGenerator = new MySQLMetadataQueryGenerator(capabilityProvider);
        this.connectionPoolOptimizer = new MySQLConnectionPoolOptimizer();
    }

    @Override
    public DatabaseType getDatabaseType() {
        return DatabaseType.MYSQL;
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
