package com.consilens.connector.postgresql;

import com.consilens.connector.api.ConnectorTypes;
import com.consilens.connector.api.*;
import com.consilens.conncetor.base.AbstractDatabaseDialect;

import java.util.Map;

/**
 * PostgreSQL database dialect implementation.
 * 
 * <p>
 * Provides PostgreSQL-specific implementations of all dialect components
 * through
 * composition pattern:
 * <ul>
 * <li>{@link PostgreSQLCapabilityProvider} - PostgreSQL features and
 * capabilities</li>
 * <li>{@link PostgreSQLSqlQueryGenerator} - PostgreSQL SQL generation</li>
 * <li>{@link PostgreSQLMetadataQueryGenerator} - PostgreSQL metadata
 * queries</li>
 * <li>{@link PostgreSQLDataTypeHandler} - PostgreSQL data type handling</li>
 * </ul>
 * 
 * <p>
 * This dialect is typically created by {@link PostgreSQLDatabaseDialectProvider}
 * and discovered via JDK {@code ServiceLoader}.
 * 
 * <pre>{@code
 * DatabaseDialect dialect = DialectFactory.getDialect("LPOSTGRESQL");
 * }</pre>
 * 
 * @since 1.0.0
 */
public class PostgreSQLDatabaseDialect extends AbstractDatabaseDialect {

    private final PostgreSQLCapabilityProvider capabilityProvider;
    private final PostgreSQLSqlQueryGenerator sqlQueryGenerator;
    private final PostgreSQLMetadataQueryGenerator metadataQueryGenerator;
    private final PostgreSQLDataTypeHandler dataTypeHandler;
    private final PostgreSQLConnectionPoolOptimizer connectionPoolOptimizer;

    /**
     * Constructs a new PostgreSQL database dialect.
     * Initializes all PostgreSQL-specific components.
     */
    public PostgreSQLDatabaseDialect() {
        this(null);
    }
    
    /**
     * Constructs a new PostgreSQL database dialect with normalization configuration.
     * 
     * @param normalizationConfig normalization configuration map
     */
    public PostgreSQLDatabaseDialect(Map<String, ?> normalizationConfig) {
        // Initialize PostgreSQL-specific components
        this.capabilityProvider = new PostgreSQLCapabilityProvider();
        this.dataTypeHandler = new PostgreSQLDataTypeHandler(capabilityProvider, normalizationConfig);
        this.sqlQueryGenerator = new PostgreSQLSqlQueryGenerator(capabilityProvider, dataTypeHandler);
        this.metadataQueryGenerator = new PostgreSQLMetadataQueryGenerator(capabilityProvider);
        this.connectionPoolOptimizer = new PostgreSQLConnectionPoolOptimizer();
    }

    @Override
    public String getConnectorType() {
        return ConnectorTypes.POSTGRESQL;
    }

    @Override
    public String getJdbcDriverClassName() {
        return "org.postgresql.Driver";
    }

    @Override
    public int getDefaultPort() {
        return 5432;
    }

    @Override
    public boolean requiresTransactionForStreamingReads() {
        return true;
    }

    @Override
    public int getStructuredColumnJdbcType() {
        return java.sql.Types.OTHER;
    }

    @Override
    public String buildJdbcUrl(Map<String, Object> params) {
        String host = String.valueOf(params.get("host"));
        int port = params.get("port") != null ? ((Number) params.get("port")).intValue() : getDefaultPort();
        String database = params.get("database") != null ? String.valueOf(params.get("database")) : "";
        return "jdbc:postgresql://" + host + ":" + port + "/" + database;
    }

    @Override
    public DataSourceConfigBuilder getDataSourceConfigBuilder() {
        return new PostgreSQLDataSourceConfigBuilder();
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
