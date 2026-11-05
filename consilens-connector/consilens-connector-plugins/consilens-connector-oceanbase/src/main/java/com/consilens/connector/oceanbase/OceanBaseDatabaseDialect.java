package com.consilens.connector.oceanbase;

import com.consilens.connector.api.ConnectorTypes;
import com.consilens.connector.api.*;
import com.consilens.conncetor.base.AbstractDatabaseDialect;

import java.util.Map;

/**
 * OceanBase database dialect implementation.
 *
 * <p>
 * Provides OceanBase-specific implementations of all dialect components through
 * composition pattern:
 * <ul>
 * <li>{@link OceanBaseCapabilityProvider} - OceanBase features and capabilities</li>
 * <li>{@link OceanBaseSqlQueryGenerator} - OceanBase SQL generation</li>
 * <li>{@link OceanBaseMetadataQueryGenerator} - OceanBase metadata queries</li>
 * <li>{@link OceanBaseDataTypeHandler} - OceanBase data type handling</li>
 * <li>{@link OceanBaseConnectionPoolOptimizer} - OceanBase connection pool optimization</li>
 * </ul>
 *
 * <p>
 * OceanBase is highly compatible with MySQL in MySQL mode. This dialect leverages
 * MySQL-compatible syntax while accounting for OceanBase-specific behaviors:
 * <ul>
 * <li>OceanBase supports FULL OUTER JOIN natively (unlike MySQL)</li>
 * <li>OceanBase supports Common Table Expressions (CTE)</li>
 * <li>OceanBase has its own system databases and internal schemas</li>
 * </ul>
 *
 * <p>
 * This dialect is typically created by {@link OceanBaseDatabaseDialectProvider}
 * and discovered via JDK {@code ServiceLoader}.
 *
 * <pre>{@code
 * DatabaseDialect dialect = DialectFactory.getDialect("OCEANBASE");
 * }</pre>
 *
 * @since 1.0.0
 */
public class OceanBaseDatabaseDialect extends AbstractDatabaseDialect {

    private final OceanBaseCapabilityProvider capabilityProvider;
    private final OceanBaseSqlQueryGenerator sqlQueryGenerator;
    private final OceanBaseMetadataQueryGenerator metadataQueryGenerator;
    private final OceanBaseDataTypeHandler dataTypeHandler;
    private final OceanBaseConnectionPoolOptimizer connectionPoolOptimizer;

    /**
     * Constructs a new OceanBase database dialect.
     * Initializes all OceanBase-specific components.
     */
    public OceanBaseDatabaseDialect() {
        this(null);
    }

    /**
     * Constructs a new OceanBase database dialect with normalization configuration.
     *
     * @param normalizationConfig normalization configuration map
     */
    public OceanBaseDatabaseDialect(Map<String, ?> normalizationConfig) {
        this.capabilityProvider = new OceanBaseCapabilityProvider();
        this.dataTypeHandler = new OceanBaseDataTypeHandler(capabilityProvider, normalizationConfig);
        this.sqlQueryGenerator = new OceanBaseSqlQueryGenerator(capabilityProvider, dataTypeHandler);
        this.metadataQueryGenerator = new OceanBaseMetadataQueryGenerator(capabilityProvider);
        this.connectionPoolOptimizer = new OceanBaseConnectionPoolOptimizer();
    }

    @Override
    public String getConnectorType() {
        return ConnectorTypes.OCEANBASE;
    }

    @Override
    public String getJdbcDriverClassName() {
        return "com.mysql.cj.jdbc.Driver";
    }

    @Override
    public int getDefaultPort() {
        return 2881;
    }

    @Override
    public String buildJdbcUrl(Map<String, Object> params) {
        String host = String.valueOf(params.get("host"));
        int port = params.get("port") != null ? ((Number) params.get("port")).intValue() : getDefaultPort();
        String database = params.get("database") != null ? String.valueOf(params.get("database")) : "";
        return "jdbc:mysql://" + host + ":" + port + "/" + database;
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
