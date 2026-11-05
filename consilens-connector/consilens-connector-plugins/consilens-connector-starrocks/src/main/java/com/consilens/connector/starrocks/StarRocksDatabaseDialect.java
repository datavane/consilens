package com.consilens.connector.starrocks;

import com.consilens.connector.api.ConnectorTypes;
import com.consilens.connector.api.*;
import com.consilens.conncetor.base.AbstractDatabaseDialect;
import com.consilens.conncetor.base.MySQLProtocolConnectionPoolOptimizer;

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
 * DatabaseDialect dialect = DialectFactory.getDialect("LSTARROCKS");
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
    public String getConnectorType() {
        return ConnectorTypes.STARROCKS;
    }

    @Override
    public String getJdbcDriverClassName() {
        return "com.mysql.cj.jdbc.Driver";
    }

    @Override
    public int getDefaultPort() {
        return 9030;
    }

    @Override
    public String buildJdbcUrl(Map<String, Object> params) {
        String host = String.valueOf(params.get("host"));
        int port = params.get("port") != null ? ((Number) params.get("port")).intValue() : getDefaultPort();
        String database = params.get("database") != null ? String.valueOf(params.get("database")) : "";
        return "jdbc:mysql://" + host + ":" + port + "/" + database;
    }

    /**
     * 校验和比较依赖 MD5(GROUP_CONCAT(...))，必须抬高 group_concat_max_len，
     * 否则行数一多拼接结果被静默截断，真实差异会被漏检。
     */
    @Override
    public ConnectionPoolOptimizer getConnectionPoolOptimizer() {
        return new MySQLProtocolConnectionPoolOptimizer();
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
